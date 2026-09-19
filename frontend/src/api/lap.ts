import { getToken } from './client'

/**
 * LAP v1 —— 应用平台的 REST 面。
 *
 * 这个文件里没有"真人专用"的任何东西, 因为协议里本来就没有: 真人和数字人调的是同一个
 * `POST /api/v1/actions:execute`, 带同一个 `Idempotency-Key` 头, 拿到同一个 `ActionResponse`。
 * 页面能做的事, 数字人也做得到 —— 这正是这一层值得单独存在的理由。
 *
 * 与 `client.ts` 里的 `request()` 相比, 这里有两处必须不同:
 *
 * 1. **错误信封不一样。** 平台的失败响应是 `{status, error:{code, message}}`, 而 `error` 是个
 *    对象 —— 按 `data.error` 当字符串读会得到 `[object Object]`, 于是"为什么被拒"永远显示不出来。
 * 2. **写动作必须带幂等键。** 没有键就 400, 平台不会替客户端派生一个 ——
 *    「忘了带」是客户端 bug, 应当立刻暴露。
 */

export interface CapabilityView {
  capabilityId: string
  title?: string | null
  description?: string | null
  category?: string | null
}

export interface ApplicationView {
  applicationId: string
  version?: string | null
  name?: string | null
  description?: string | null
  category?: string | null
  capabilities?: string[]
}

export interface ActionSpec {
  actionId: string
  applicationId?: string
  capabilityId?: string
  description?: string | null
  permissionLevel?: 'READ' | 'WRITE' | 'EXECUTE'
  riskLevel?: string
  attention?: string
  inputSchema?: unknown
  agentHint?: string | null
}

export interface ResourceView {
  uri: string
  resourceType?: string
  applicationId?: string
  sessionId?: string
  state?: Record<string, unknown> | null
  version: number
  updatedAt?: string
  agentHint?: string | null
}

export interface ActionError {
  code: string
  message?: string | null
}

export interface ActionResponse<T = unknown> {
  status: string
  result?: T | null
  resource?: ResourceView | null
  events?: unknown[]
  error?: ActionError | null
}

/** 会话里"这是什么应用"那一栏 (§16)。只有 id 与版本 —— 名字与图标去详情页拿。 */
export interface AppRef {
  id: string
  version?: string | null
}

/**
 * 会话里"我是谁"那一栏 (§16)。
 *
 * `id` 是<b>参与者</b>的 id, 不是 principal 的 id —— 同一个人在同一个应用的两局里是两个参与者。
 * `principalId` 才是跨会话稳定的那个身份。
 */
export interface ParticipantRef {
  id: string
  principalType: string
  principalId: string
  role: string
}

/**
 * 打开一个应用的结果 —— 它 <b>就是</b> 一个会话, 形状见方案 §16。
 *
 * v2 之前这里叫 `InstallResponse`, 装着 `installationId`。安装没了之后这个名字跟着消失:
 * 界面上"打开"这个动作产生的唯一东西就是一个会话, 而会话 id 就是接下来每一步动作要用的那个。
 *
 * 这里<b>没有</b> `ownerPrincipalId`。那曾经是一个平铺字段, 但它问的是一个错的问题 ——
 * 一局里可以有好几个人, "主人"只是 `role === 'OWNER'` 的那一个参与者。把主人当成会话的属性,
 * 就等于把"一个会话属于一个人"这个旧假设又写回了类型里。
 */
export interface SessionResponse {
  sessionId: string
  application: AppRef
  participant: ParticipantRef
  status: string
  visibility: string
  joinPolicy: string
  minParticipants: number
  maxParticipants: number
  conversationId?: string | null
  participantCount: number
  capabilities: string[]
  createdAt?: string | null
  startedAt?: string | null
  endedAt?: string | null
  lastActiveAt?: string | null
}

/** §4.1 那张表的三列 —— 应用详情页只说这三件事, 不说十个状态。 */
export interface AvailabilityView {
  state: string
  inMarket: boolean
  allowsNewSession: boolean
  allowsExistingSession: boolean
}

/** §67 的呈现方式。平台只搬运这个词, 不解释它长什么样。 */
export type SurfaceType = 'FULL_PAGE' | 'EMBEDDED' | 'MODAL' | 'PANEL' | 'INLINE'

/** §18 的三种 UI 模式。`entry` 是<b>模板</b>, 变量只有 `{applicationId}` 与 `{sessionId}`。 */
export type UiMode = 'EMBEDDED' | 'REMOTE' | 'NATIVE'

export interface SurfaceView {
  type: SurfaceType
  entry: string
}

export interface UiView {
  type: UiMode
  entry: string
  minClientVersion?: string | null
  surfaces: SurfaceView[]
}

/**
 * 应用详情 —— 「应用市场」点进去看到的那一页。
 *
 * `status` 是十态原值(给"为什么它不能开"一个准确的说法), `availability` 是五态投影 +
 * 三列布尔(给"能不能打开"一个可执行的答案)。两个都给, 因为它们是给两种人看的。
 */
export interface ApplicationDetail {
  applicationId: string
  version?: string | null
  name?: string | null
  description?: string | null
  category?: string | null
  capabilities?: string[]
  actionCount: number
  status?: string | null
  availability: AvailabilityView
  ui: UiView
  /**
   * 应用声明的资源模板 —— `uriTemplate` 里只有 `{applicationId}` 与 `{sessionId}`
   * 两个变量, 客户端做且只做替换(与 `ui.surfaces[].entry` 同一条规矩)。
   *
   * 开一个还不存在的资源时必须靠它: 那时没有任何 URI 能指向它, 而 `/actions:execute`
   * 硬性要求 target 非空(否则选不出是哪个应用声明的动作)。
   */
  resources?: ResourceDeclView[]
}

export interface ResourceDeclView {
  type: string
  uriTemplate: string
  backing?: string | null
  agentHint?: string | null
}

export interface ParticipantView {
  participantId: string
  sessionId: string
  principalType: string
  principalId: string
  role: string
  status: string
  permissionProfile?: string | null
  capabilities?: string[]
  companionId?: string | null
  userId?: string | null
  joinedAt?: string | null
  leftAt?: string | null
}

/** 铸出来的那张票。<b>`token` 只在铸造响应里出现这一次</b> —— 页面必须当场把它给用户。 */
export interface InvitationView {
  invitationId: string
  sessionId: string
  role: string
  maxUses?: number | null
  usedCount: number
  status: string
  expiresAt?: string | null
  targetType?: string | null
  targetId?: string | null
}

export interface MintedInvitation extends InvitationView {
  token: string
  joinUrl: string
}

/** 兑票的结果 —— 一次加入之后, "我在这一场里的位置"。 */
export interface JoinResponse {
  participantId: string
  sessionId: string
  principalType: string
  principalId: string
  role: string
  status: string
  capabilities: string[]
}

/**
 * 没有信封时, 按 HTTP 状态给一句**人话**。
 *
 * 这一条是补一个实测出来的坑: 网关(nginx / Authelia)直接回的错误**不是**平台的
 * `{status, error:{code, message}}` 信封, 于是走到下面那个退回分支 —— 而那个分支
 * 原来把 `message` 写成 `undefined`, `LapError` 再把 message 兜到 code 上, 最后
 * 页面渲染 `${code} — ${message}` 就得到 **"HTTP_403 — HTTP_403"**: 同一个字符串
 * 印两遍, 一个字的信息都没有。六个页面(`Discover` / `ApplicationDetail` /
 * `AppSession` / `JoinSession` / `apps/board` ×2)都在渲染那一行, 所以修在这里一处,
 * 六处一起好。
 *
 * 401/403 单独说 —— 它不是"请求失败", 是**票没了**, 用户该做的事完全不同。
 */
function httpFallbackMessage(status: number): string {
  if (status === 401 || status === 403) return '登录已失效 —— 重新登录即可。'
  if (status === 404) return '这个地址不存在 —— 它可能已经下架了。'
  if (status >= 500) return `服务端出错了 (HTTP ${status}) —— 稍后再试。`
  return `请求失败 (HTTP ${status})。`
}

export class LapError extends Error {
  readonly code: string
  readonly status: string

  constructor(status: string, error: ActionError) {
    super(error.message || error.code)
    this.status = status
    this.code = error.code
  }
}

/**
 * 把 `LapError` 变成一行给用户看的话。
 *
 * `HTTP_403` 是**传输层**的码 —— 把它印在句子前面, 等于把网线露给用户看。实测的
 * 原文是 "HTTP_403 — HTTP_403"; 修掉重复之后仍然读作
 * "HTTP_403 — 登录已失效 —— 重新登录即可。"。只有当码是平台自己的**业务码**时
 * (`APPLICATION_NOT_FOUND`、`CELL_TAKEN` 这种)它才真的带信息, 那时才配上: 排查的
 * 人需要它, 而用户读到的仍然是一句完整的话 —— `apps/board.tsx` 里"被拒是常态,
 * 界面的责任是把平台的答案原样摆出来"那条, 说的正是这种码, 这里没有推翻它。
 *
 * 六个页面共用这一行, 所以它只能有一处定义 —— 同一句话在六个地方各写一遍,
 * 下次改文案必漏几个。
 */
export function describeLapError(e: unknown): string {
  if (e instanceof LapError) {
    return e.code.startsWith('HTTP_') ? e.message : `${e.code} — ${e.message}`
  }
  return e instanceof Error ? e.message : String(e)
}

async function send<T>(method: string, url: string, body?: unknown, key?: string): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (key) headers['Idempotency-Key'] = key

  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  })

  const text = await res.text()
  // **不能直接 `JSON.parse`**: 502/504 这类由网关直接回的错页是 HTML, 解析会抛
  // `SyntaxError` —— 于是用户看到的是"Unexpected token < in JSON", 而不是"服务端没起来"。
  // 那恰恰是最需要一句人话的时候。解析不了就当没有信封, 走下面的状态码分支。
  let data: unknown = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = null
  }

  if (!res.ok) {
    // 平台把失败原因放在 error.code / error.message 里; 没有信封时退回 HTTP 状态。
    const envelope = data as ActionResponse | null
    if (envelope?.error?.code) {
      throw new LapError(envelope.status ?? String(res.status), envelope.error)
    }
    throw new LapError(String(res.status), {
      code: 'HTTP_' + res.status,
      // 纯文本体就原样用它(后端偶尔这么回); 否则按状态码给一句人话 —— 两种都**不会**
      // 让 message 空着, 空着就会被 LapError 兜成 code, 页面于是印两遍同一个字符串。
      message: typeof data === 'string' && data ? data : httpFallbackMessage(res.status),
    })
  }
  return data as T
}

/** 写动作的键由调用方给 —— 重试时必须原样复用同一个键, 否则幂等无从谈起。 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

export const lap = {
  capabilities: () => send<CapabilityView[]>('GET', '/api/v1/capabilities'),

  applicationsOf: (capabilityId: string) =>
    send<ApplicationView[]>(
      'GET',
      `/api/v1/capabilities/${encodeURIComponent(capabilityId)}/applications`,
    ),

  /** 全部在架应用 —— 「应用市场」那一页。 */
  market: () => send<ApplicationView[]>('GET', '/api/v1/applications'),

  /**
   * 应用详情。**下架的应用这里也打得开** —— 详情页要能说出"它为什么打不开",
   * 而一个 404 说不出这句话。真正被拒的是"开一局新的"。
   */
  application: (applicationId: string) =>
    send<ApplicationDetail>('GET', `/api/v1/applications/${encodeURIComponent(applicationId)}`),

  actionsOf: (applicationId: string) =>
    send<ActionSpec[]>('GET', `/api/v1/applications/${encodeURIComponent(applicationId)}/actions`),

  /**
   * 打开应用 —— **每次都是一个新的会话**。
   *
   * 想续上一局的人该拿旧的 `sessionId` 回来, 而不是指望这里返回同一个: "打开"与"回到刚才那局"
   * 是两件事, 把它们合并会让"再开一局"永远做不到。
   *
   * 应用不可用(下架/废弃/还没上架)时平台给 `APPLICATION_NOT_AVAILABLE` —— 抛 LapError, 由页面
   * 决定怎么说。前端不预判: 判据在服务端一处, 复制一份到这里就会有一天两边不一致。
   */
  openSession: (applicationId: string, options?: { conversationId?: string }) =>
    send<SessionResponse>(
      'POST',
      `/api/v1/applications/${encodeURIComponent(applicationId)}/sessions`,
      options ?? {},
    ),

  /** 我参与的全部活跃会话 —— "我正在用的应用"那个列表。 */
  sessions: () => send<SessionResponse[]>('GET', '/api/v1/sessions'),

  /** 单独读一个会话。分享链接加入之后、以及任何深链接进来时用。 */
  session: (sessionId: string) =>
    send<SessionResponse>('GET', `/api/v1/sessions/${encodeURIComponent(sessionId)}`),

  /** 结束会话。不是"卸载" —— 应用还在市场里, 只是这一局散了。 */
  endSession: async (sessionId: string): Promise<void> => {
    await send<null>('DELETE', `/api/v1/sessions/${encodeURIComponent(sessionId)}`)
  },

  /**
   * 这一场里有谁。名单里含已经离开的人, 每个人带 `status` ——
   * "他中途走了"是这一局历史的一部分, 不该被过滤掉。
   */
  participantsOf: (sessionId: string) =>
    send<ParticipantView[]>('GET', `/api/v1/sessions/${encodeURIComponent(sessionId)}/participants`),

  /** 自己走。会话不会因此结束。 */
  leave: async (sessionId: string): Promise<void> => {
    await send<null>('DELETE', `/api/v1/sessions/${encodeURIComponent(sessionId)}/participants/me`)
  },

  /**
   * 铸一张邀请票。
   *
   * 响应里的 `token` 与 `joinUrl` <b>只出现这一次</b> —— 库里只有哈希, 丢了只能重铸一张。
   * 页面的义务是当场把它交给用户(复制/分享), 而不是"以后再显示"。
   *
   * 带了 `targetType` + `targetId` 就是**定向票**: 平台顺手给那一位发一条
   * `APPLICATION_INVITATION` 事件, 由对方自己决定来不来。不带就是普通的分享票。
   */
  invite: (
    sessionId: string,
    options?: { role?: string; maxUses?: number; targetType?: string; targetId?: string },
  ) =>
    send<MintedInvitation>(
      'POST',
      `/api/v1/sessions/${encodeURIComponent(sessionId)}/invitations`,
      options ?? {},
    ),

  invitationsOf: (sessionId: string) =>
    send<InvitationView[]>(
      'GET',
      `/api/v1/sessions/${encodeURIComponent(sessionId)}/invitations`,
    ),

  revokeInvitation: async (invitationId: string): Promise<void> => {
    await send<null>('DELETE', `/api/v1/invitations/${encodeURIComponent(invitationId)}`)
  },

  /**
   * 兑票进会话 —— **公开**端点, 需要登录但不需要先在会话里。
   *
   * 这里传的是 token 明文本身(分享链接里那 30 个字符)。平台侧拿它去比对哈希:
   * 拿到链接的人进得来, 但看不出这张票编号几号、谁铸的、给谁。
   */
  joinByToken: (token: string) =>
    send<JoinResponse>('POST', `/api/v1/join/${encodeURIComponent(token)}`),

  readResource: (uri: string) =>
    send<ResourceView[]>('GET', `/api/v1/resources?uri=${encodeURIComponent(uri)}`),

  /**
   * 这一场里的全部资源 —— <b>不按 URI 找</b>, 而是问"这一场有什么"。
   *
   * 界面组件不该知道 uriTemplate 拼出来的那条 URI 长什么样(`game://session/{id}` 还是
   * `gomoku://match/{id}`), 那是应用自己的命名, 平台不解释它。资源行自己带着 `uri`,
   * 从这一场里读出来即可。
   */
  resourcesOfSession: (sessionId: string) =>
    send<ResourceView[]>('GET', `/api/v1/resources?sessionId=${encodeURIComponent(sessionId)}`),

  /**
   * 唯一的动作入口。`key` 只对 WRITE / EXECUTE 需要; READ 传空 ——
   * 给读动作配一把键会让第二次读<b>重放</b>第一次的旧结果。
   */
  execute: (action: string, target: string | null, input: unknown, key?: string) =>
    send<ActionResponse>('POST', '/api/v1/actions:execute', { action, target, input }, key),
}
