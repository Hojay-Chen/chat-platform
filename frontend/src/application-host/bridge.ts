import type { HostEventName, HostRequest, HostCapability } from './protocol'
import {
  errorResponse,
  eventFrame,
  okResponse,
  parseRequest,
  validateInput,
} from './protocol'
import { isAllowed, missingPermission, type SessionContext } from './capabilities'

/**
 * ApplicationBridge —— 宿主这一侧的收件人。
 *
 * <h2>它的职责只有四件, 且顺序不可换</h2>
 *
 * <ol>
 *   <li><b>确认这一帧真的来自那个 iframe。</b> 见 {@link BridgeDeps.target}。</li>
 *   <li><b>认出它是什么</b>({@link parseRequest}) —— 认不出就丢掉, 连回执都不回。</li>
 *   <li><b>看这个容器给不给这个能力</b>({@link isAllowed}) —— 不给就回一个带权限名的错误。</li>
 *   <li><b>把参数形状不好的挡在门外</b>, 然后才交给能力实现。</li>
 * </ol>
 *
 * 3 与 4 的顺序是刻意的, 而且是"权限在前": 一个**没有分享权限**的应用传了一个字段类型
 * 写错的 `share.request`, 它该收到的是"你没有 SHARE 权限", 而不是"你的 coverUrl 该是字符串"。
 * 反过来的话, 应用会先去修一个它本来就做不成的事 —— 而且它从错误消息里能推断出宿主的参数
 * 校验细节, 那是它不需要知道的。
 *
 * <h2>这个类里没有一行碰 `window`</h2>
 *
 * 收发两端都是从外面传进来的({@link BridgeDeps.port} / {@link BridgeDeps.target})。
 * 这不是为了好看: 本仓前端测试跑在 `environment: 'node'` 上, 没有 jsdom, 所以任何直接读
 * 全局 `window` 的逻辑都**不可能被断言** —— 而"哪一帧被放行、哪一帧被拒"恰恰是这一层里
 * 最不能出错的东西(放行错了, 一个嵌进来的第三方页面就能替用户发消息)。
 *
 * <h2>为什么不回执的情况也不抛</h2>
 *
 * 一个认不出的帧**连回执都不回**。回执需要一个 `id`, 而认不出的帧恰恰没有可信的 `id` ——
 * 拿它回一句"解析失败"会把一个可以随便填的字段变成一条回给任意窗口的消息。那种"回复来路
 * 不明的消息"的行为是点击劫持类问题的经典起点。
 */

/** 宿主能回填的最小窗口接口。真实的 `MessageEvent.source` 与 `iframe.contentWindow` 都是它。 */
export interface MessageTarget {
  postMessage(data: unknown, targetOrigin: string): void
}

/** 消息来源的最小接口 —— 真实值是 `window`。 */
export interface MessagePort {
  addEventListener(type: 'message', listener: (event: MessageEventLike) => void): void
  removeEventListener(type: 'message', listener: (event: MessageEventLike) => void): void
}

export interface MessageEventLike {
  data: unknown
  source: unknown
}

/**
 * 一个能力的实现。
 *
 * 入参是**已经验过形状**的 `input`, 所以实现里可以放心地当对象读; 返回值会被原样装进回执的
 * `result`。抛出的异常会被转成 `ok:false` 的回执 —— 一个第三方应用触发的失败不该让宿主的
 * 错误边界把整个页面换成一屏白。
 */
export type CapabilityHandler = (input: Record<string, unknown>) => unknown | Promise<unknown>

/** 能力实现表。**缺一项 = 那个能力在这个宿主里没接**(回 `NOT_IMPLEMENTED`), 而不是"默认允许"。 */
export type HostHandlers = Partial<Record<HostCapability, CapabilityHandler>>

export interface BridgeDeps {
  /** 收消息的窗口 —— 真实的 `window`。 */
  port: MessagePort
  /**
   * 这一帧该来自哪个窗口。**每次调用都重新取**, 因为 iframe 会重挂。
   *
   * 返回 `null`(iframe 已经不在)时**整帧丢弃**。少了这道闸, 页面上任何一个被嵌进来的
   * 第三方页面都能往宿主发一帧 `share.request` 让别人收到邀请 —— 而宿主无从分辨。
   */
  target: () => MessageTarget | null
  /** 这个应用此刻在哪儿、被授予了什么。由宿主页面在挂载时算好传进来。 */
  session: () => SessionContext
  handlers: HostHandlers
  /** 认不出的帧往哪儿写。默认丢掉 —— 但线上一定要接上, 它是"对面版本比我们新"的唯一线索。 */
  onRejected?: (reason: string) => void
}

export interface ApplicationBridge {
  /** 推一个事件给应用。应用没在听也不会报错 —— postMessage 本来就是单向的。 */
  emit(event: HostEventName, payload?: unknown): void
  dispose(): void
}

const ORIGIN = '*'

/**
 * 装一个桥。返回的 {@link ApplicationBridge} 唯一要做的事是 `dispose()` —— 组件卸载时必须调,
 * 否则每挂一次 REMOTE 应用就往 window 上多压一个监听器, 而它们全都还活着。
 */
export function createApplicationBridge(deps: BridgeDeps): ApplicationBridge {
  const { port, target, session, handlers, onRejected } = deps

  const listener = (event: MessageEventLike) => {
    // ── 1. 是不是那个 iframe 发的 ──
    const app = target()
    if (!app || event.source !== app) return

    // ── 2. 认不认得出这是一帧什么 ──
    const parsed = parseRequest(event.data)
    if (!parsed.ok) {
      onRejected?.(parsed.reason)
      return
    }
    const request: HostRequest = parsed.request

    // 处理是异步的, 但**监听器本身不能是 async 的**: 一个 await 之后抛出来的异常不会被
    // 任何调用方接住, 它会变成一个 unhandled rejection。所以整段包在一个立即执行的
    // async 函数里, 并且自己 try/catch 到底。
    void (async () => {
      const reply = await handle(request)
      if (!reply) return
      const to = target()
      if (!to) return
      to.postMessage(reply, ORIGIN)
    })()
  }

  async function handle(request: HostRequest) {
    const ctx = session()

    // ── 3. 这个容器给不给这个能力 ──
    if (!isAllowed(request.capability, ctx.permissions)) {
      const missing = missingPermission(request.capability, ctx.permissions)
      // 上下文查询不走这道闸(见 capabilities.ts), 所以这里的 missing 不会是 null;
      // 真为 null 时也给出一个能读的码, 而不是 "PERMISSION_undefined"。
      return errorResponse(
        request.id,
        `PERMISSION_DENIED${missing ? ':' + missing : ''}`,
        missing
          ? `这个应用此刻没有 ${missing} 权限(${ctx.surface} 容器不授予它), 所以不能做这件事。`
          : '这个应用此刻不能做这件事。',
      )
    }

    // ── 4. 参数形状 ──
    const bad = validateInput(request.capability, request.input)
    if (bad) {
      return errorResponse(request.id, 'INVALID_INPUT', bad)
    }

    const input =
      request.input === undefined || request.input === null
        ? {}
        : (request.input as Record<string, unknown>)

    // 上下文由桥自己回答 —— 它是桥已经握着的那个东西, 让页面再实现一遍只会有一份可能不一致
    // 的副本。
    if (request.capability === 'session.context') {
      return okResponse(request.id, ctx)
    }

    const handler = handlers[request.capability]
    if (!handler) {
      return errorResponse(
        request.id,
        'NOT_IMPLEMENTED',
        `宿主还没有实现 ${request.capability}。`,
      )
    }

    try {
      return okResponse(request.id, await handler(input))
    } catch (e) {
      return errorResponse(
        request.id,
        'HOST_ERROR',
        e instanceof Error ? e.message : String(e),
      )
    }
  }

  port.addEventListener('message', listener)

  return {
    emit(event, payload) {
      const app = target()
      if (!app) return
      app.postMessage(eventFrame(event, payload), ORIGIN)
    },
    dispose() {
      port.removeEventListener('message', listener)
    },
  }
}
