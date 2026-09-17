import { describe, expect, it, vi } from 'vitest'
import { HOST_PROTOCOL, type HostCapability, type HostResponse } from './protocol'
import { surfaceGrants, type SessionContext } from './capabilities'
import {
  createApplicationBridge,
  type MessageEventLike,
  type MessagePort,
  type MessageTarget,
} from './bridge'
import type { SurfaceType } from '@/api/lap'

/**
 * ApplicationBridge —— 宿主这一侧的收件人。
 *
 * <h2>为什么这一层必须被逐条断言</h2>
 *
 * 放行错了, 一个嵌进来的第三方页面就能**替用户发消息**: 那是这个平台里最贵的一次错误。
 * 而它不会抛异常、不会打日志、不会让任何测试变红 —— 唯一的症状是收件人收到了一条用户
 * 没发过的邀请, 而那时已经晚了。
 *
 * <p>这个文件里所有 `window` 都是假的。不是为了让测试好写, 而是因为真的 `window` 在这里
 * **根本读不到** —— 本仓前端测试跑在 `environment: 'node'` 上。收发两端都是注入的,
 * 所以下面每一格都是对**真实那段逻辑**的断言。
 */

/** 一个假的应用窗口。收下所有回执, 供断言。 */
class FakeApp implements MessageTarget {
  readonly frames: unknown[] = []
  postMessage(data: unknown): void {
    this.frames.push(data)
  }
  /** 最后一条回执 —— 大多数用例只关心这一条。 */
  get last(): HostResponse | undefined {
    return this.frames[this.frames.length - 1] as HostResponse | undefined
  }
}

/** 一个假的 window。 */
class FakePort implements MessagePort {
  private listeners: ((e: MessageEventLike) => void)[] = []
  addEventListener(_t: 'message', l: (e: MessageEventLike) => void): void {
    this.listeners.push(l)
  }
  removeEventListener(_t: 'message', l: (e: MessageEventLike) => void): void {
    this.listeners = this.listeners.filter((x) => x !== l)
  }
  get listening(): number {
    return this.listeners.length
  }
  /** 投一帧进屋。 */
  deliver(data: unknown, source: unknown): void {
    for (const l of [...this.listeners]) l({ data, source })
  }
}

function context(surface: SurfaceType, over: Partial<SessionContext> = {}): SessionContext {
  return {
    applicationId: 'com.luxera.tictactoe',
    sessionId: 'sess-1',
    surface,
    conversationId: 'conv-1',
    permissions: surfaceGrants(surface),
    ...over,
  }
}

function request(capability: HostCapability, input?: unknown, id = 'p1-1'): unknown {
  return { protocol: HOST_PROTOCOL, kind: 'request', id, capability, input }
}

/**
 * 把桥装起来。返回的都是用例要直接摸的东西。
 *
 * <p>{@code flush} 是必需的: 处理是异步的(能力实现可以是 async 的), 而监听器本身不是 ——
 * 一个 await 之后抛出来的异常不会被任何调用方接住。所以用例投完帧要**等一拍**才看回执。
 */
function harness(options: {
  surface?: SurfaceType
  session?: SessionContext
  handlers?: Parameters<typeof createApplicationBridge>[0]['handlers']
  target?: MessageTarget | null
}) {
  const app = new FakeApp()
  const port = new FakePort()
  const onRejected = vi.fn()
  let target: MessageTarget | null = options.target === undefined ? app : options.target

  const bridge = createApplicationBridge({
    port,
    target: () => target,
    session: () => options.session ?? context(options.surface ?? 'FULL_PAGE'),
    handlers: options.handlers ?? {},
    onRejected,
  })

  return {
    app,
    port,
    onRejected,
    bridge,
    /** 模拟 iframe 重挂 / 卸载 —— target 会变, 而桥不该因此失效或误判。 */
    setTarget: (t: MessageTarget | null) => {
      target = t
    },
    /** 从**那个应用**发一帧。 */
    send: async (data: unknown) => {
      port.deliver(data, app)
      await new Promise((r) => setTimeout(r, 0))
    },
    /** 从**别的窗口**发一帧。 */
    sendFrom: async (data: unknown, source: unknown) => {
      port.deliver(data, source)
      await new Promise((r) => setTimeout(r, 0))
    },
  }
}

describe('来源校验 —— 第一道闸', () => {
  it('不是那个 iframe 发的一律丢掉, 连理由都不记', async () => {
    // 页面上任何一个被嵌进来的第三方页面都能 postMessage。少了这一句, 它能替用户
    // 发一条分享 —— 而宿主无从分辨。
    const h = harness({ handlers: { 'app.ready': () => 'ok' } })
    await h.sendFrom(request('app.ready'), { postMessage() {} })

    expect(h.app.frames).toHaveLength(0)
    // 也不该进"认不出的帧"那条日志 —— 它认得出, 只是来路不对。混在一起会把
    // "有人在扫我们的窗口"淹在噪音里。
    expect(h.onRejected).not.toHaveBeenCalled()
  })

  it('target 为 null(iframe 已经不在)时整帧丢弃', async () => {
    const h = harness({ target: null, handlers: { 'app.ready': () => 'ok' } })
    await h.send(request('app.ready'))

    expect(h.app.frames).toHaveLength(0)
    expect(h.onRejected).not.toHaveBeenCalled()
  })

  it('回执发给**当下的** target, 不是发帧时的那个', async () => {
    // iframe 会重挂。把 target 写死成挂载时那一个, 结果是重挂之后所有回执都发给了一个
    // 已经不存在的窗口 —— 症状是应用一直转圈。
    //
    // 重挂之后帧也是**新那个 iframe** 发的, 所以这里两件事一起换: target 与 source。
    const first = new FakeApp()
    const second = new FakeApp()
    const h = harness({ target: first, handlers: { 'app.ready': () => 'ok' } })

    h.setTarget(second)
    await h.sendFrom(request('app.ready'), second)

    expect(first.frames).toHaveLength(0)
    expect(second.frames).toHaveLength(1)
    expect(second.last?.ok).toBe(true)
  })
})

describe('认帧 —— 认不出就不回', () => {
  it('别的协议的帧不回执, 只记理由', async () => {
    const h = harness({ handlers: { 'app.ready': () => 'ok' } })
    await h.send({ protocol: 'luxera.host.v9', kind: 'request', id: 'x', capability: 'app.ready' })

    expect(h.app.frames).toHaveLength(0)
    expect(h.onRejected).toHaveBeenCalledTimes(1)
  })

  it('表里没有的 capability 不回执', async () => {
    // 回执需要一个可信的 id, 而认不出的帧恰恰没有 —— 拿它回一句"解析失败"会把一个
    // 可以随便填的字段变成一条回给任意窗口的消息。
    const h = harness({})
    await h.send(request('chat.nuke' as HostCapability))

    expect(h.app.frames).toHaveLength(0)
    expect(h.onRejected).toHaveBeenCalledTimes(1)
  })

  it('畸形值不抛 —— 监听器是全局的, 它一炸所有应用一起失效', async () => {
    const h = harness({ handlers: { 'app.ready': () => 'ok' } })
    for (const bad of [null, 42, 'hello', [], undefined]) {
      await expect(h.send(bad)).resolves.toBeUndefined()
    }
    expect(h.app.frames).toHaveLength(0)
    expect(h.onRejected).toHaveBeenCalledTimes(5)
  })
})

describe('权限闸 —— 在形状校验之前', () => {
  it('EMBEDDED 里分享被拒, 错误码里带着缺的那一项', async () => {
    const handler = vi.fn(() => ({ sent: true }))
    const h = harness({ surface: 'EMBEDDED', handlers: { 'share.request': handler } })

    await h.send(request('share.request', { title: '棋' }))

    expect(handler).not.toHaveBeenCalled()
    expect(h.app.last?.ok).toBe(false)
    expect(h.app.last?.error?.code).toBe('PERMISSION_DENIED:SHARE')
    // 人话里要说清**为什么** —— "抽屉里不给"与"没有这个功能"对用户是两件事。
    expect(h.app.last?.error?.message).toContain('SHARE')
  })

  it('参数写错 + 没有权限 → 报的是没有权限, 不是参数错误', async () => {
    // 顺序反了的后果很具体: 应用会先去修一个它本来就做不成的事, 而且它从错误消息里
    // 能推断出宿主的参数校验细节 —— 那是它不需要知道的。
    const handler = vi.fn()
    const h = harness({ surface: 'INLINE', handlers: { 'share.request': handler } })

    await h.send(request('share.request', { title: 12345, coverUrl: ['nope'] }))

    expect(h.app.last?.error?.code).toBe('PERMISSION_DENIED:SHARE')
    expect(handler).not.toHaveBeenCalled()
  })

  it('有权限但参数写错 → 这时才报 INVALID_INPUT', async () => {
    const handler = vi.fn()
    const h = harness({ surface: 'FULL_PAGE', handlers: { 'share.request': handler } })

    await h.send(request('share.request', { title: 12345 }))

    expect(h.app.last?.error?.code).toBe('INVALID_INPUT')
    expect(h.app.last?.error?.message).toContain('title')
    expect(handler).not.toHaveBeenCalled()
  })

  it('EMBEDDED 里"我是谁"仍然能问 —— 被拒的理由是"还没接", 不是"没权限"', async () => {
    // 少了这几条, 一个嵌在别人页面里的应用连"我在这个平台上是人还是机器"都问不出来 ——
    // 而那是它决定要不要渲染登录入口的依据。
    const h = harness({ surface: 'EMBEDDED' })
    await h.send(request('user.me'))
    expect(h.app.last?.error?.code).toBe('NOT_IMPLEMENTED')
  })

  it('EMBEDDED 里分享被拒之后仍然能问自己有什么权限 —— 否则应用不知道该怎么退化', async () => {
    const h = harness({ surface: 'EMBEDDED' })
    await h.send(request('share.request', { title: '棋' }))
    await h.send(request('session.context', undefined, 'p1-2'))

    expect(h.app.last?.ok).toBe(true)
    expect((h.app.last?.result as SessionContext).permissions).toEqual([])
  })
})

describe('session.context 由桥自己回答', () => {
  it('不需要 handler, 且答案就是宿主手上那一份', async () => {
    const h = harness({ surface: 'PANEL' })
    await h.send(request('session.context'))

    expect(h.app.last?.ok).toBe(true)
    expect(h.app.last?.result).toEqual(context('PANEL'))
  })

  it('没有权限也不影响问上下文 —— 这两件事必须能分开', async () => {
    const h = harness({ surface: 'INLINE' })
    await h.send(request('session.context'))

    const result = h.app.last?.result as SessionContext
    expect(result.permissions).toEqual([])
    expect(result.surface).toBe('INLINE')
  })
})

describe('能力实现', () => {
  it('拿到的是验过形状的 input; 没给 input 就是 {}', async () => {
    const handler = vi.fn(() => 'done')
    const h = harness({ handlers: { 'chat.openWindow': handler } })

    await h.send(request('chat.openWindow'))
    expect(handler).toHaveBeenCalledWith({})

    await h.send(request('chat.openWindow', { mode: 'EXPANDED' }, 'p1-2'))
    expect(handler).toHaveBeenLastCalledWith({ mode: 'EXPANDED' })
  })

  it('异步实现会被等 —— 回执里是 resolve 之后的值', async () => {
    const h = harness({
      handlers: {
        'share.request': async () => {
          await new Promise((r) => setTimeout(r, 5))
          return { sent: true, conversationId: 'conv-9' }
        },
      },
    })

    await h.send(request('share.request', { title: '棋' }))
    // 只等一拍还不够(实现里还有 5ms), 所以这里多等一点。
    await new Promise((r) => setTimeout(r, 20))

    expect(h.app.last?.ok).toBe(true)
    expect(h.app.last?.result).toEqual({ sent: true, conversationId: 'conv-9' })
  })

  it('实现抛异常 → ok:false 的 HOST_ERROR, 而不是把页面炸掉', async () => {
    // 一个第三方应用触发的失败不该让宿主的错误边界把整个页面换成一屏白。
    const h = harness({
      handlers: {
        'share.request': () => {
          throw new Error('会话不存在')
        },
      },
    })

    await h.send(request('share.request', {}))

    expect(h.app.last?.ok).toBe(false)
    expect(h.app.last?.error?.code).toBe('HOST_ERROR')
    expect(h.app.last?.error?.message).toBe('会话不存在')
  })

  it('异步实现 reject 也一样接住', async () => {
    const h = harness({
      handlers: {
        'share.request': async () => {
          throw new Error('网络断了')
        },
      },
    })
    await h.send(request('share.request', {}))
    await new Promise((r) => setTimeout(r, 5))

    expect(h.app.last?.error?.code).toBe('HOST_ERROR')
  })

  it('没有实现的合法能力 → NOT_IMPLEMENTED, 而不是"默认允许"', async () => {
    const h = harness({ handlers: {} })
    await h.send(request('chat.minimize'))

    expect(h.app.last?.ok).toBe(false)
    expect(h.app.last?.error?.code).toBe('NOT_IMPLEMENTED')
  })
})

describe('emit / dispose', () => {
  it('emit 推的是事件帧, 且没有 id', async () => {
    const h = harness({})
    h.bridge.emit('chat.windowChanged', { mode: 'MINIMIZED' })

    expect(h.app.last).toMatchObject({
      protocol: HOST_PROTOCOL,
      kind: 'event',
      event: 'chat.windowChanged',
      payload: { mode: 'MINIMIZED' },
    })
    expect(h.app.last).not.toHaveProperty('id')
  })

  it('target 不在时 emit 静默 —— postMessage 本来就是单向的', async () => {
    const h = harness({ target: null })
    expect(() => h.bridge.emit('session.ended')).not.toThrow()
    expect(h.app.frames).toHaveLength(0)
  })

  it('dispose 之后不再收帧 —— 否则每挂一次就多压一个监听器, 而它们全都还活着', async () => {
    const handler = vi.fn(() => 'ok')
    const h = harness({ handlers: { 'app.ready': handler } })

    h.bridge.dispose()
    await h.send(request('app.ready'))

    expect(h.port.listening).toBe(0)
    expect(handler).not.toHaveBeenCalled()
    expect(h.app.frames).toHaveLength(0)
  })
})
