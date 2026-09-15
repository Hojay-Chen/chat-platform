const TOKEN_KEY = 'companion_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}
export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * G5 —— 双服务路由: 逻辑路径 → 带服务前缀的物理路径。
 *
 * G1 拆分后伴侣域(companions CRUD / memories / relationship / life / self /
 * reminders / notifications / user-model / state / reflections)随 digital-human
 * 迁到了 8091, 而会话/消息/事件流(conversations / messages / events / threads /
 * applications)与 auth、应用平台(/api/v1)仍在 8081。页面代码的 URL 引用保持不变,
 * 归属判定集中在这一处 —— 因为 G7 生产 nginx 要与这里**同一套**分流规则:
 * 前端物理 URL 带 `/agent` 前缀后, 开发(vite 代理)与生产(nginx)口径一致。
 *
 * 归类规则只有一句: {@code /api/companions} 及其子路径里, 除了
 * {@code conversations|messages|events|threads|applications} 是聊天平台(8081)的,
 * 其余全是仿真 Agent 平台(8091)的。这里不写死具体伴侣 id, 只用"路径段"判 ——
 * 因为判据是"哪个域", 而不是"哪个 id"。
 */

/** 聊天平台自己保留在 /api/companions/{id} 下的子路径 —— 永远走 8081。 */
const CHAT_KEPT_SEGMENTS = new Set(['conversations', 'messages', 'events', 'threads', 'applications'])

export function route(url: string): string {
  const prefix = '/api/companions'
  // 先切 query/fragment —— 再判断前缀(因为 query 会干扰 startsWith)
  const hashIdx = url.indexOf('#')
  const queryIdx = url.indexOf('?')
  const cutIdx =
    queryIdx === -1 ? hashIdx : hashIdx === -1 ? queryIdx : Math.min(queryIdx, hashIdx)
  const pathOnly = cutIdx === -1 ? url : url.slice(0, cutIdx)

  // 只处理 /api/companions 这条"两端交织"的前缀; 其余(/api/auth, /api/v1, …)天然 8081
  if (!pathOnly.startsWith(prefix)) return url

  // /api/companions 本身(列表/创建)与 /api/companions/compile|preview → 8091
  const rest = pathOnly.slice(prefix.length) // '' 或 '/…'
  if (rest === '' || rest === '/') return '/agent' + url

  // /api/companions/compile|preview → 8091(伴侣编译链, 无 id 段)
  if (rest === '/compile' || rest === '/preview') return '/agent' + url

  // /api/companions/{id} 与 /api/companions/{id}/{segment}…
  const m = rest.match(/^\/([^/]+)(\/([^/]+))?/)
  if (!m) return url
  const segment = m[3]

  // /api/companions/{id} 本身(详情/删除) → 8091
  if (!segment || segment === '') return '/agent' + url

  // chat 保留段永远走 8081; 其余伴侣域段 → 8091
  return CHAT_KEPT_SEGMENTS.has(segment) ? url : '/agent' + url
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(route(url), {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  })

  if (!res.ok) {
    let message = `请求失败 (${res.status})`
    try {
      const data = await res.json()
      if (data?.error) message = data.error
    } catch {
      /* ignore */
    }
    throw new Error(message)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export const api = {
  get: <T>(url: string) => request<T>('GET', url),
  post: <T>(url: string, body?: unknown) => request<T>('POST', url, body),
  put: <T>(url: string, body?: unknown) => request<T>('PUT', url, body),
  del: <T>(url: string) => request<T>('DELETE', url),
}

/** 持久事件流(GET /events), 断线自动重连 + 指数退避。
 *  §17: 增加 SSE 游标 —— 记录每条事件 id, 重连时携带 Last-Event-ID,
 *  服务器回放断线期间错过的消息(消息永不丢, 不靠整表重载补)。 */
export async function openEventStream(
  companionId: string,
  onEvent: (event: string, data: unknown, eventId?: string) => void,
): Promise<() => void> {
  let closed = false
  let controller: AbortController | null = null
  let retryMs = 1000
  let lastEventIdRef: string | null = null

  async function connect() {
    if (closed) return
    controller = new AbortController()
    const headers: Record<string, string> = {}
    const token = getToken()
    if (token) headers['Authorization'] = `Bearer ${token}`
    // 游标续传 —— 上次收到的事件 id
    if (lastEventIdRef) headers['Last-Event-ID'] = lastEventIdRef

    try {
      const res = await fetch(route(`/api/companions/${companionId}/events`), {
        method: 'GET',
        headers,
        cache: 'no-store',
        signal: controller.signal,
      })
      if (!res.ok || !res.body) throw new Error(`事件流连接失败 (${res.status})`)
      retryMs = 1000 // 连接成功 → 重置退避

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx: number
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          emitBlockWithId(block, onEvent, (id) => {
            lastEventIdRef = id
          })
        }
      }
    } catch (err) {
      if (closed) return
      // 心跳事件也会触发 onEvent('ping'), 不影响
    }
    // 断线重连(指数退避, 上限 30s)
    if (!closed) {
      setTimeout(connect, retryMs)
      retryMs = Math.min(retryMs * 2, 30000)
    }
  }

  connect()

  return () => {
    closed = true
    if (controller) controller.abort()
  }
}

function emitBlockWithId(
  block: string,
  onEvent: (event: string, data: unknown, eventId?: string) => void,
  onId: (id: string) => void,
) {
  let event = 'message'
  let data = ''
  let id: string | undefined
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
    else if (line.startsWith('id:')) id = line.slice(3).trim()
  }
  if (id) onId(id)
  if (!data) return
  try {
    onEvent(event, JSON.parse(data), id)
  } catch {
    onEvent(event, data, id)
  }
}
