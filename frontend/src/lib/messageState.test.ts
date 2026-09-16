import { describe, expect, it } from 'vitest'
import { toMessage, userStatus } from './messageState'

describe('userStatus · 自己消息的状态', () => {
  it('SSE 推来的已读回执最优先', () => {
    expect(userStatus({ id: 'm1', deliveryStatus: 'DELIVERED' }, { m1: true }, {})).toBe('已读')
  })

  it('本地乐观的"发送中"压过服务端的旧状态', () => {
    // 服务端还停在 DELIVERED, 但用户刚按下发送 —— 显示"已发送"会让他以为已经出去了
    expect(userStatus({ id: 'm1', deliveryStatus: 'DELIVERED' }, {}, { m1: 'SENT' })).toBe('发送中')
  })

  it('本地乐观的"发送失败"压过服务端状态', () => {
    // 这条最要紧: 判错会让用户以为消息发出去了
    expect(userStatus({ id: 'm1', deliveryStatus: 'DELIVERED' }, {}, { m1: 'FAILED' })).toBe('发送失败')
  })

  it('服务端的三种终态都算已读', () => {
    for (const st of ['READ', 'DEFERRED', 'RESPONDED']) {
      expect(userStatus({ id: 'm1', deliveryStatus: st }, {}, {})).toBe('已读')
    }
  })

  it('没有任何状态时说"已发送", 不说"发送中"', () => {
    // 默认值必须是终态 —— 否则每条历史消息都会永远转圈
    expect(userStatus({ id: 'm1' }, {}, {})).toBe('已发送')
  })

  it('deliveryStatus 为 null 时不炸', () => {
    expect(userStatus({ id: 'm1', deliveryStatus: null }, {}, {})).toBe('已发送')
  })
})

describe('toMessage · 事件增量 upsert 的形状', () => {
  it('带上 conversationId 与 clientMessageId', () => {
    const m = toMessage('m1', 'c1', 'user', '你好', 'cid-1', new Date('2026-09-16T10:00:00'))
    expect(m).toEqual({
      id: 'm1',
      conversationId: 'c1',
      senderType: 'user',
      content: '你好',
      clientMessageId: 'cid-1',
      createdAt: new Date('2026-09-16T10:00:00').toISOString(),
    })
  })

  it('clientMessageId 可省 —— 对方发来的消息没有它', () => {
    const m = toMessage('m1', 'c1', 'companion', '在的', undefined, new Date('2026-09-16T10:00:00'))
    expect(m.clientMessageId).toBeUndefined()
  })

  it('createdAt 用传入的时钟, 不是真实当下', () => {
    // 可注入是为了让"它排在列表末尾"这条性质能被断言, 而不是靠运气
    const m = toMessage('m1', 'c1', 'user', 'x', undefined, new Date('2026-01-01T00:00:00'))
    expect(m.createdAt).toBe(new Date('2026-01-01T00:00:00').toISOString())
  })
})
