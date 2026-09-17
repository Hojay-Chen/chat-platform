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

describe('classifyRoomEvent · 会话被销毁了（对面的 Agent 被删）', () => {
  it('★ 销毁的正是我开着的这段 → 必须离开房间', () => {
    // 用户很可能就是开着这个房间按的删除(手机上从会话页返回设置页)。不处理的话他会
    // 停在一段永远不再更新、也发不出去任何东西的记录上, 而没有任何东西告诉他为什么。
    const r = classifyRoomEvent('conversation_deleted', { conversationId: ME }, ME)
    expect(r.closeRoom).toBe(true)
    // 房间都要走了, 往它的消息流里插东西已经没有意义 —— 三个答案是互斥的意图
    expect(r.applyToRoom).toBe(false)
    // 但列表那一行得消失, 所以仍要刷新
    expect(r.refreshList).toBe(true)
  })

  it('销毁的是别段会话 → 不关我的房间, 只让列表少掉那一行', () => {
    const r = classifyRoomEvent('conversation_deleted', { conversationId: OTHER }, ME)
    expect(r.closeRoom).toBe(false)
    expect(r.applyToRoom).toBe(false)
    expect(r.refreshList).toBe(true)
  })

  it('没带 conversationId 的销毁事件不关房间 —— 认不出是哪一段, 贸然离开比留下更糟', () => {
    const r = classifyRoomEvent('conversation_deleted', {}, ME)
    expect(r.closeRoom).toBe(false)
    expect(r.refreshList).toBe(true)
  })

  it('还没打开任何会话时不会误判成"我这段被销毁了"', () => {
    expect(classifyRoomEvent('conversation_deleted', { conversationId: ME }, undefined).closeRoom)
      .toBe(false)
  })

  it('普通消息事件永远不会关房间 —— 这个答案是销毁独有的', () => {
    for (const e of ['companion_message', 'message_created', 'companion_typing', 'message_read']) {
      expect(classifyRoomEvent(e, { conversationId: ME }, ME).closeRoom, e).toBe(false)
    }
  })

  it('事件体畸形也不炸', () => {
    expect(() => classifyRoomEvent('conversation_deleted', null, ME)).not.toThrow()
    expect(classifyRoomEvent('conversation_deleted', null, ME).closeRoom).toBe(false)
    expect(classifyRoomEvent('conversation_deleted', { conversationId: 42 }, ME).closeRoom).toBe(false)
  })
})
