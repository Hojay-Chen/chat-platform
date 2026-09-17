const TOKEN_KEY = 'companion_token'

/**
 * 单测跑在 `environment: 'node'` 下, 那里没有 localStorage。
 * 这不是为测试而加的开关 —— 它是"这个模块被 import 时不该碰宿主环境"这条规矩,
 * 路由表被测时 RequireAuth 会读 token, 没有这行整个 routes.test 起不来。
 * 真机上 localStorage 一定存在, 走的是同一条路径。
 */
function storage(): Storage | null {
  return typeof localStorage === 'undefined' ? null : localStorage
}

export function getToken(): string | null {
  return storage()?.getItem(TOKEN_KEY) ?? null
}
export function setToken(token: string) {
  storage()?.setItem(TOKEN_KEY, token)
}
export function clearToken() {
  storage()?.removeItem(TOKEN_KEY)
}

/**
 * G8 —— 前端不再做任何服务分流。
 *
 * 这里曾有过一个 {@code route()}: 给伴侣域请求打 `/agent` 前缀, 让它们绕过 8081 直连
 * 8091(G5 的设计)。那个设计是错的, 且不只是架构口味问题 —— 它按"路径段"猜端点归属,
 * 而 `conversations` 段两端都有端点:
 *
 *   GET  /api/companions/{id}/conversations                 → 8081(会话列表)
 *   POST /api/companions/{id}/conversations                 → 8081(建会话)
 *   POST /api/companions/{id}/conversations/first           → 8091(开/复用会话, 回 ConversationView)
 *   POST /api/companions/{id}/conversations/{cid}/chat      → 8091(SSE 流式聊天)
 *
 * 段规则把整段判给 8081, 于是 `conversations/first` 与 `.../chat` 打到了没有这两个端点
 * 的服务上 —— 主链路 404。
 *
 * 现在浏览器只跟聊天平台一个域名说话, 伴侣域请求由 8081 在**服务端**转发给 8091
 * (见后端 CompanionDomainProxyController)。那里判归属的依据是"8081 到底实现了什么"
 * (Spring 的 HandlerMapping 优先级), 而不是路径段, 因此不会再出现这类误判。
 *
 * 前端这一层保持纯粹: URL 就是它字面上的意思, 不加前缀、不改写。
 */

/**
 * 带上状态码与 hint 的请求错误。
 *
 * <h2>为什么需要它</h2>
 *
 * 改账号ID 会以三种方式失败, 而它们是**三件要采取不同行动的事**:
 *
 * - `400` 形状不对 —— 用户可以立刻改对, 提示里还带着一个可用的建议
 * - `409` 被别人占了 —— 换一个, 但原来的写法没毛病
 * - `429` 一年三次用完了 —— 此刻改不动, 唯一有用的话是"什么时候能再改"
 *
 * 只抛一个 `Error(message)` 的话, 这三者在界面上长得一模一样。而"修改失败"是一句
 * 用户没法据以行动的话。
 *
 * <h2>为什么仍然继承 Error, 并且 message 一个字没变</h2>
 *
 * 全仓有大量 `e instanceof Error ? e.message : '...'` 的写法, 它们今天都对。
 * 这个类只是**多带**了两个字段, 不是换一种错误 —— 所以那些调用点一行都不用改,
 * 需要区分的调用点自己去 `instanceof ApiError`。
 */
export class ApiError extends Error {
  readonly status: number
  /** 后端给的可执行下一步。没有时是 null —— 不是空串。 */
  readonly hint: string | null

  constructor(message: string, status: number, hint: string | null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.hint = hint
  }
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(url, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  })

  if (!res.ok) {
    let message = `请求失败 (${res.status})`
    let hint: string | null = null
    try {
      const data = await res.json()
      if (data?.error) message = data.error
      if (data?.hint) hint = data.hint
    } catch {
      /* ignore */
    }
    throw new ApiError(message, res.status, hint)
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
      const res = await fetch(`/api/companions/${companionId}/events`, {
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
