/**
 * Luxera Host Protocol v1 —— 宿主页面与「被嵌进来的应用」之间那条线上跑的东西。
 *
 * <h2>为什么需要一层协议, 而不是让应用直接调平台的 API</h2>
 *
 * §5.1 把这条边界写成了两句硬话: 应用**不能**直接操作 Chat 的 React state、不能读 Chat 的
 * DOM、不能调 Chat 的私有接口。三条禁令合起来只有一个实现方式 —— 应用能做的每一件事都必须
 * 是**向宿主请求**, 由宿主决定做不做、怎么做。这层协议就是那个"请求"的形状。
 *
 * 于是它必须满足两件互相拉扯的事:
 *
 * <ul>
 *   <li><b>应用说的是意图, 不是实现。</b> `share.request` 的意思是"我想让用户把这一场分享出去",
 *       而不是"给我一个选好友的弹窗"。所以协议里没有一个字提到 ShareSheet、Overlay、路由 ——
 *       那些是宿主的实现, 换掉它们(Web 验证版 → §5.3 的原生 Bridge)不该动协议。</li>
 *   <li><b>每一个字段都要能被验。</b> 这条线的另一头是第三方代码, 它可能发任何东西:
 *       别的协议的帧、半截 JSON、`null`、一个自己编的 `capability`。所以 {@link parseRequest}
 *       是**白名单式**的 —— 对照一张写死的表逐项检查, 而不是"取到就用、取不到就默认"。
 *       后者正是"某个应用悄悄拿到了它不该有的能力"的入口。</li>
 * </ul>
 *
 * <h2>为什么这一层是三个 kind, 不是 RPC 框架</h2>
 *
 * `request` 有回执, `event` 是宿主单方面广播, `notify` 是应用单方面上报(不需要回执)。
 * 三种就够描述 §15 的六个 Bridge。做成"调用远端方法"那种形状会立刻需要 IDL、需要版本协商、
 * 需要错误码空间 —— 而这三样东西的维护成本, 在只有六个能力的今天全是白付的。
 *
 * <h2>版本号在每一帧上, 不在握手里</h2>
 *
 * 握手协商版本有一个众所周知的坏结局: 协商过一次之后就再没人看它。逐帧带版本号意味着
 * <b>混版客户端</b>(宿主刚更新、应用的 iframe 还是旧的)在任何一帧上都会被认出来, 而不是
 * 在第一帧之后一路错下去。代价是每帧多 20 字节。
 */

/** 协议标识。它出现在**每一帧**上, 见类注释最后一段。 */
export const HOST_PROTOCOL = 'luxera.host.v1'

/** 应用可以请求的能力。**这张表是白名单本身** —— 不在里面的 capability 一律拒。 */
export const CAPABILITIES = [
  /** 应用说自己画好了 —— 宿主据此收起加载态。没有回执之外的作用。 */
  'app.ready',
  /** 把这一层应用关掉, 由宿主决定"关掉"是什么(返回上一页 / 收起抽屉 / 关弹窗)。 */
  'app.close',
  /** 我在这个平台上是谁。**只有公开身份**, 见 §15 的禁令清单。 */
  'user.me',
  /** 我此刻在哪个应用、哪一场、摆在哪种容器里、被授予了哪些宿主能力。 */
  'session.context',
  /** §7 把聊天浮窗叫出来(可带一个要打开的会话)。 */
  'chat.openWindow',
  /** §7 把浮窗缩回最小态。 */
  'chat.minimize',
  /** 让宿主跳到某段对话去 —— 与 openWindow 不同, 这是"离开应用"。 */
  'chat.openConversation',
  /** §9 请求分享。宿主会打开 ShareSheet, 用户选人、写附言、发送。 */
  'share.request',
] as const

export type HostCapability = (typeof CAPABILITIES)[number]

const CAPABILITY_SET: ReadonlySet<string> = new Set(CAPABILITIES)

/** 宿主可以推给应用的事件。第三方应用据此更新自己的界面。 */
export const HOST_EVENTS = [
  /** 宿主把浮窗最小化/展开了。应用可据此让出或收回自己的底部区域。 */
  'chat.windowChanged',
  /** 用户在 ShareSheet 里取消了分享。 */
  'share.cancelled',
  /** 会话被结束/失效 —— 应用应当收摊, 而不是继续画一盘不存在的棋。 */
  'session.ended',
] as const

export type HostEventName = (typeof HOST_EVENTS)[number]

/** 请求帧: 应用 → 宿主。 */
export interface HostRequest {
  protocol: typeof HOST_PROTOCOL
  kind: 'request'
  /** 应用生成的关联 id。宿主原样回填 —— 这是"哪一问对应哪一答"的唯一依据。 */
  id: string
  capability: HostCapability
  input: unknown
}

/** 回执帧: 宿主 → 应用。成功与失败共用 `id`, 靠 `ok` 分。 */
export interface HostResponse {
  protocol: typeof HOST_PROTOCOL
  kind: 'response'
  id: string
  ok: boolean
  result?: unknown
  error?: { code: string; message: string }
}

/** 事件帧: 宿主 → 应用。没有 id, 也没有回执。 */
export interface HostEvent {
  protocol: typeof HOST_PROTOCOL
  kind: 'event'
  event: HostEventName
  payload?: unknown
}

/** 上报帧: 应用 → 宿主, 不需要回执。 */
export interface HostNotify {
  protocol: typeof HOST_PROTOCOL
  kind: 'notify'
  event: string
  payload?: unknown
}

/**
 * 关联 id 的长度上限。
 *
 * 它存在的理由不是安全, 而是**可诊断性**: 一个把整段 JSON 塞进 id 的应用会让回执帧大到
 * 刷屏, 而那时真正的问题是它的 id 生成写错了。
 */
const MAX_ID = 64

/** 一个入站帧的解析结果: 认出来了就给出请求, 认不出来给出**为什么**。 */
export type ParseResult =
  | { ok: true; request: HostRequest }
  | { ok: false; reason: string; id?: string }

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/**
 * 把一个来路不明的值解析成请求帧 —— <b>认不出就是认不出, 绝不猜</b>。
 *
 * <h2>为什么返回原因而不是 null</h2>
 *
 * 调用方要把拒绝的理由写进日志。只返回 `null` 的话, 那条日志只能是"收到一个无法解析的帧",
 * 而它不能区分三种完全不同的故障: 对面发的是别的协议(版本不一致)、对面发的是事件帧(代码
 * 分支写错了)、对面发了一个表里没有的 capability(它在新版 SDK 上, 或者它是个攻击者)。
 * 这三种的处置完全不同, 所以理由必须活到日志里。
 *
 * <p>认不出时**不抛异常**: 一个畸形帧不该让宿主的消息监听器炸掉, 而那个监听器是全局的 ——
 * 它一炸, 所有应用的所有能力一起失效。
 */
export function parseRequest(data: unknown): ParseResult {
  if (!isRecord(data)) return { ok: false, reason: '不是对象' }

  const protocol = data.protocol
  if (protocol !== HOST_PROTOCOL) {
    return { ok: false, reason: `协议不认识: ${String(protocol)}` }
  }

  const kind = data.kind
  if (kind !== 'request') return { ok: false, reason: `不是请求帧: ${String(kind)}` }

  const id = data.id
  if (typeof id !== 'string' || id.length === 0 || id.length > MAX_ID) {
    return { ok: false, reason: 'id 缺失或长度不合法' }
  }

  const capability = data.capability
  if (typeof capability !== 'string' || !CAPABILITY_SET.has(capability)) {
    // id 已经验过了, 所以这一条能带上它 —— 应用至少能知道是哪一问被拒了。
    return { ok: false, reason: `没有这个能力: ${String(capability)}`, id }
  }

  return {
    ok: true,
    request: {
      protocol: HOST_PROTOCOL,
      kind: 'request',
      id,
      capability: capability as HostCapability,
      input: data.input,
    },
  }
}

/** 一个入站值是不是**事件或上报**帧 —— 宿主自己不需要, 但 SDK 侧要用同一套判据。 */
export function parseEvent(data: unknown): HostEvent | null {
  if (!isRecord(data)) return null
  if (data.protocol !== HOST_PROTOCOL || data.kind !== 'event') return null
  const event = data.event
  if (typeof event !== 'string') return null
  return { protocol: HOST_PROTOCOL, kind: 'event', event: event as HostEventName, payload: data.payload }
}

/** 成功回执。 */
export function okResponse(id: string, result?: unknown): HostResponse {
  return { protocol: HOST_PROTOCOL, kind: 'response', id, ok: true, result }
}

/**
 * 失败回执。
 *
 * `code` 是给**应用**看的(它要据此决定重试还是换个做法), `message` 是给**人**看的。
 * 两者都要: 只有 message 的话, 应用只能去匹配人类文案, 而那串文案是会改的。
 */
export function errorResponse(id: string, code: string, message: string): HostResponse {
  return { protocol: HOST_PROTOCOL, kind: 'response', id, ok: false, error: { code, message } }
}

/** 宿主推给应用的事件帧。 */
export function eventFrame(event: HostEventName, payload?: unknown): HostEvent {
  return { protocol: HOST_PROTOCOL, kind: 'event', event, payload }
}

/**
 * 这个能力要的东西长得对不对 —— 只验**形状**, 不验权限。
 *
 * <h2>为什么形状校验和应用层校验要分开</h2>
 *
 * 形状不对是**协议错误**(对面发的不是这个能力该发的字段), 处置是拒绝并说清; 权限不够是
 * **业务结果**(用户没给你这个权限), 处置是回一个业务错误。混在一起的话, 应用无法区分
 * "我参数写错了"和"用户不让我做这件事", 而这两种它要做的事完全不同。
 *
 * <p>`undefined` 与 `{}` 都算通过 —— 有四个能力本来就不需要参数。
 */
export function validateInput(capability: HostCapability, input: unknown): string | null {
  const object = input === undefined || input === null ? {} : input
  if (!isRecord(object)) return 'input 必须是一个对象'

  switch (capability) {
    case 'chat.openWindow': {
      const { conversationId, mode } = object
      if (conversationId !== undefined && conversationId !== null && typeof conversationId !== 'string') {
        return 'conversationId 必须是字符串'
      }
      if (mode !== undefined && mode !== null && mode !== 'MINIMIZED' && mode !== 'EXPANDED') {
        return 'mode 只能是 MINIMIZED 或 EXPANDED'
      }
      return null
    }
    case 'chat.openConversation': {
      if (typeof object.conversationId !== 'string' || object.conversationId.length === 0) {
        return '必须给出 conversationId'
      }
      return null
    }
    case 'share.request': {
      const { title, description, coverUrl, sessionId, metadata } = object
      if (title !== undefined && title !== null && typeof title !== 'string') return 'title 必须是字符串'
      if (description !== undefined && description !== null && typeof description !== 'string') {
        return 'description 必须是字符串'
      }
      if (coverUrl !== undefined && coverUrl !== null && typeof coverUrl !== 'string') {
        return 'coverUrl 必须是字符串'
      }
      if (sessionId !== undefined && sessionId !== null && typeof sessionId !== 'string') {
        return 'sessionId 必须是字符串'
      }
      if (metadata !== undefined && metadata !== null && !isRecord(metadata)) {
        return 'metadata 必须是对象'
      }
      return null
    }
    default:
      // app.ready / app.close / user.me / session.context / chat.minimize 不吃参数。
      // **多给的字段被忽略, 而不是报错** —— 那是向前兼容该有的形状: 新版 SDK 多传一个
      // 宿主还不认识的字段时, 它不该整条请求失败。
      return null
  }
}
