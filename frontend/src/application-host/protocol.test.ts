import { describe, expect, it } from 'vitest'
import {
  CAPABILITIES,
  HOST_PROTOCOL,
  errorResponse,
  eventFrame,
  okResponse,
  parseEvent,
  parseRequest,
  validateInput,
} from './protocol'

/**
 * 协议层的断言 —— 这条线的另一头是**第三方代码**。
 *
 * <h2>为什么这个文件的重点全在"拒"上</h2>
 *
 * 放行一条合法请求是容易的, 而它的错法只有一种(功能不工作, 当场就发现)。真正会让平台出事的是
 * **放行了一条不该放行的**: 一个别的协议的帧被当成请求、一个表里没有的 capability 被当成已知、
 * 一个自己编的 id 被原样回填进一条 postMessage。这三种都不会报错, 只会表现为"某个应用做到了
 * 它不该做到的事"。
 *
 * <p>所以下面每一个 `ok: false` 的用例, 对应的都是上面某一类。
 */

/** 一帧合法请求。用例在它上面改一处, 于是"改动的那一处"就是失败的原因。 */
function frame(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    protocol: HOST_PROTOCOL,
    kind: 'request',
    id: 'p1-1',
    capability: 'app.ready',
    input: {},
    ...over,
  }
}

describe('parseRequest —— 白名单', () => {
  it('认出一帧合法请求, 并原样带出 input', () => {
    const r = parseRequest(frame({ capability: 'share.request', input: { title: '五子棋' } }))
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.request.capability).toBe('share.request')
    expect(r.request.id).toBe('p1-1')
    expect(r.request.input).toEqual({ title: '五子棋' })
  })

  it('表里没有的 capability 一律拒, 且带上 id', () => {
    // 这一条同时挡两种东西: 一个在新版 SDK 上跑的应用(它该知道宿主还不认识这个能力),
    // 以及一个自己编了一个 capability 的页面。两者的处置是一样的。
    const r = parseRequest(frame({ capability: 'chat.deleteEverything' }))
    expect(r.ok).toBe(false)
    if (r.ok) return
    // id 合法时**必须**带出来 —— 否则应用只知道"有一问被拒了", 不知道是哪一问。
    expect(r.id).toBe('p1-1')
    expect(r.reason).toContain('chat.deleteEverything')
  })

  it('协议不认识就拒 —— 这是"混版客户端"的唯一线索', () => {
    const r = parseRequest(frame({ protocol: 'luxera.host.v2' }))
    expect(r.ok).toBe(false)
    if (r.ok) return
    expect(r.reason).toContain('luxera.host.v2')
  })

  it('事件帧不会因为长得像请求而被放行', () => {
    // event 帧没有 id, 走的是另一个方向。把它当请求处理会回一条 id 为 undefined 的回执。
    const r = parseRequest({ protocol: HOST_PROTOCOL, kind: 'event', event: 'session.ended' })
    expect(r.ok).toBe(false)
    if (r.ok) return
    expect(r.reason).toContain('event')
  })

  it('不是对象的一切都被拒, 且不抛', () => {
    // 这个监听器是全局的 —— 它一炸, 所有应用的所有能力一起失效。所以"不抛"是硬要求,
    // 不是风格问题。
    for (const bad of [null, undefined, 42, 'hello', [], true]) {
      expect(() => parseRequest(bad)).not.toThrow()
      expect(parseRequest(bad).ok).toBe(false)
    }
  })

  it('id 缺失、为空、或过长都被拒 —— 且这一条不带 id', () => {
    for (const bad of [undefined, null, '', 123, 'x'.repeat(65)]) {
      const r = parseRequest(frame({ id: bad }))
      expect(r.ok).toBe(false)
      if (r.ok) return
      // id 本身不合法时不能把它回填进日志以外的任何地方, 所以这一条不带。
      expect(r.id).toBeUndefined()
    }
    // 边界另一侧: 恰好 64 是合法的。
    expect(parseRequest(frame({ id: 'x'.repeat(64) })).ok).toBe(true)
  })

  it('每一帧都必须带协议号 —— 不在握手里', () => {
    const missing = frame()
    delete missing.protocol
    expect(parseRequest(missing).ok).toBe(false)
  })

  it('白名单就是 CAPABILITIES 本身, 数一遍', () => {
    // 这张表是**协议的一部分**: 加一个能力就是改协议, 所以它的规模值得被钉住。
    // 不是"不能改", 而是"改的时候必须是有意的"。
    expect(CAPABILITIES).toHaveLength(8)
    for (const capability of CAPABILITIES) {
      expect(parseRequest(frame({ capability })).ok).toBe(true)
    }
  })
})

describe('parseEvent —— 与 parseRequest 共用一套判据', () => {
  it('认得出事件帧', () => {
    const e = parseEvent({ protocol: HOST_PROTOCOL, kind: 'event', event: 'share.cancelled' })
    expect(e?.event).toBe('share.cancelled')
  })

  it('缺协议号 / 缺 event / 不是对象 → null', () => {
    expect(parseEvent({ kind: 'event', event: 'x' })).toBeNull()
    expect(parseEvent({ protocol: HOST_PROTOCOL, kind: 'event' })).toBeNull()
    expect(parseEvent({ protocol: HOST_PROTOCOL, kind: 'request', id: 'a' })).toBeNull()
    expect(parseEvent(null)).toBeNull()
  })
})

describe('回执与事件帧', () => {
  it('成功回执带 result, 不带 error', () => {
    const r = okResponse('p1-9', { sent: true })
    expect(r).toEqual({
      protocol: HOST_PROTOCOL,
      kind: 'response',
      id: 'p1-9',
      ok: true,
      result: { sent: true },
    })
  })

  it('失败回执把 code 与 message 分开 —— 应用不能靠匹配人话来分支', () => {
    const r = errorResponse('p1-9', 'PERMISSION_DENIED:SHARE', '没有分享权限')
    expect(r.ok).toBe(false)
    expect(r.error?.code).toBe('PERMISSION_DENIED:SHARE')
    expect(r.error?.message).toBe('没有分享权限')
  })

  it('事件帧没有 id —— 它是单向广播, 不该被回执', () => {
    const f = eventFrame('chat.windowChanged', { mode: 'MINIMIZED' })
    expect(f).not.toHaveProperty('id')
    expect(f.kind).toBe('event')
  })
})

describe('validateInput —— 只验形状, 不验权限', () => {
  it('不需要参数的能力对 undefined 与 {} 都放行', () => {
    for (const c of ['app.ready', 'app.close', 'user.me', 'session.context', 'chat.minimize'] as const) {
      expect(validateInput(c, undefined)).toBeNull()
      expect(validateInput(c, {})).toBeNull()
    }
  })

  it('chat.openWindow 的 mode 只认两档', () => {
    expect(validateInput('chat.openWindow', {})).toBeNull()
    expect(validateInput('chat.openWindow', { mode: 'MINIMIZED' })).toBeNull()
    expect(validateInput('chat.openWindow', { mode: 'EXPANDED' })).toBeNull()
    expect(validateInput('chat.openWindow', { mode: 'FULLSCREEN' })).toContain('MINIMIZED')
    expect(validateInput('chat.openWindow', { conversationId: 7 })).toContain('conversationId')
  })

  it('chat.openConversation 必须要 conversationId —— 它没有"默认那段对话"', () => {
    expect(validateInput('chat.openConversation', { conversationId: 'c1' })).toBeNull()
    expect(validateInput('chat.openConversation', {})).toContain('conversationId')
    expect(validateInput('chat.openConversation', { conversationId: '' })).toContain('conversationId')
  })

  it('share.request 的五个字段都可以不给 —— 全都给了才验', () => {
    expect(validateInput('share.request', {})).toBeNull()
    expect(validateInput('share.request', { title: '棋', description: '来', sessionId: 's' })).toBeNull()
    expect(validateInput('share.request', { title: 5 })).toContain('title')
    expect(validateInput('share.request', { coverUrl: ['x'] })).toContain('coverUrl')
    // metadata 是给**同一个应用**自己认的暗号, 只要求它是对象。
    expect(validateInput('share.request', { metadata: { move: 3 } })).toBeNull()
    expect(validateInput('share.request', { metadata: 'move=3' })).toContain('metadata')
  })

  it('input 不是对象时拒 —— 包括数组', () => {
    expect(validateInput('share.request', 'hello')).toContain('对象')
    expect(validateInput('share.request', [1, 2])).toContain('对象')
  })

  it('多给的字段被忽略, 不是报错 —— 这是向前兼容的方向', () => {
    // 新版 SDK 多传一个宿主还不认识的字段时, 它不该整条请求失败。判严了的话,
    // 每一次 SDK 加字段都变成一次"旧宿主打死新应用"。
    expect(validateInput('share.request', { title: '棋', futureField: 1 })).toBeNull()
    expect(validateInput('chat.minimize', { why: 'x' })).toBeNull()
  })
})
