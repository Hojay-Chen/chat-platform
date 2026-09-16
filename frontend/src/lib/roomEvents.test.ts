import { describe, expect, it } from 'vitest'
import { classifyRoomEvent } from './roomEvents'

const ME = 'conv-mine'
const OTHER = 'conv-other'

describe('classifyRoomEvent · 该不该插进当前消息流', () => {
  it('本会话的消息插进去', () => {
    const r = classifyRoomEvent('companion_message', { conversationId: ME, messageId: 'm1' }, ME)
    expect(r.applyToRoom).toBe(true)
  })

  it('别段会话的消息不插进来 —— 否则 B 的消息会出现在 A 的聊天记录里', () => {
    const r = classifyRoomEvent('companion_message', { conversationId: OTHER, messageId: 'm1' }, ME)
    expect(r.applyToRoom).toBe(false)
  })

  it('没有 conversationId 的事件算当前会话的 —— 老事件流里有这种帧', () => {
    // 一律丢掉的话, typing 这类不带会话 id 的提示就永远不出现
    expect(classifyRoomEvent('companion_typing', { typing: true }, ME).applyToRoom).toBe(true)
  })

  it('还没打开任何会话时, 带会话 id 的事件一条都不适用', () => {
    expect(classifyRoomEvent('companion_message', { conversationId: ME }, undefined).applyToRoom)
      .toBe(false)
  })
})

describe('classifyRoomEvent · 该不该刷新会话列表（这一组是那个 bug 的回归测试）', () => {
  it('★ 别段会话来了消息, 列表也必须刷新', () => {
    // 这就是修掉的那个 bug 本身。老实现里这一句的答案是 false ——
    // `if (convId !== activeConvId) return` 把整条事件丢掉了, 而
    // `refreshConversations()` 在那个 return 之后。
    //
    // 症状: 你正看着 A 的聊天记录, B 发来一条消息, 列表上 B 那一行不动、角标不亮。
    // 修法不是"在 return 之前补一句刷新"(那只是把 bug 挪个位置), 而是承认这两件事
    // 本来就不该共用一个条件。
    const r = classifyRoomEvent('companion_message', { conversationId: OTHER, messageId: 'm1' }, ME)
    expect(r.applyToRoom).toBe(false) // 不插进消息流
    expect(r.refreshList).toBe(true)  // 但列表要刷新
  })

  it('本会话来了消息, 两个都要 —— 摘要与排序也变了', () => {
    const r = classifyRoomEvent('companion_message', { conversationId: ME }, ME)
    expect(r.applyToRoom).toBe(true)
    expect(r.refreshList).toBe(true)
  })

  it('自己发出去的消息落库了, 列表同样要刷新', () => {
    expect(classifyRoomEvent('message_created', { conversationId: ME }, ME).refreshList).toBe(true)
  })

  it('typing / 已读回执不刷列表 —— 它们不改排序, 也不改摘要', () => {
    // 每次对方打字都拉一遍全量会话列表是没有意义的请求
    for (const e of ['companion_typing', 'message_read', 'user_message_status']) {
      expect(classifyRoomEvent(e, { conversationId: ME }, ME).refreshList, e).toBe(false)
    }
  })

  it('认不出的事件名不刷列表 —— 宁可少拉一次, 也不要为未知事件做全量请求', () => {
    expect(classifyRoomEvent('some_future_event', { conversationId: ME }, ME).refreshList).toBe(false)
  })

  it('事件体是 null / 缺字段也不炸 —— 事件来自网络, 形状不由前端保证', () => {
    expect(() => classifyRoomEvent('companion_message', null, ME)).not.toThrow()
    expect(classifyRoomEvent('companion_message', null, ME).refreshList).toBe(true)
    expect(classifyRoomEvent('companion_message', { conversationId: 42 }, ME).applyToRoom).toBe(false)
  })
})
