import { describe, expect, it } from 'vitest'
import { sortConversations, totalUnread } from './conversations'

const conv = (id: string, lastMessageAt: string | null, pinnedAt: string | null = null) => ({
  id,
  lastMessageAt,
  pinnedAt,
})

describe('sortConversations', () => {
  it('按最后一条消息时间倒序', () => {
    const out = sortConversations([
      conv('old', '2026-09-10T10:00:00'),
      conv('new', '2026-09-16T10:00:00'),
      conv('mid', '2026-09-13T10:00:00'),
    ])
    expect(out.map((c) => c.id)).toEqual(['new', 'mid', 'old'])
  })

  it('置顶的排在整组之前, 哪怕它很久没消息', () => {
    const out = sortConversations([
      conv('fresh', '2026-09-16T10:00:00'),
      conv('pinned', '2026-01-01T10:00:00', '2026-01-02T10:00:00'),
    ])
    expect(out.map((c) => c.id)).toEqual(['pinned', 'fresh'])
  })

  it('置顶组内部仍按消息时间排 —— 置顶提升整组, 不冻结顺序', () => {
    const out = sortConversations([
      conv('p1', '2026-09-10T10:00:00', '2026-01-01T00:00:00'),
      conv('p2', '2026-09-15T10:00:00', '2026-01-02T00:00:00'),
    ])
    expect(out.map((c) => c.id)).toEqual(['p2', 'p1'])
  })

  it('还没说过话的会话排在最后', () => {
    // 新建但没发过消息的会话冒到顶上, 会把真正有新消息的会话挤下去
    const out = sortConversations([
      conv('empty', null),
      conv('talked', '2026-09-16T10:00:00'),
    ])
    expect(out.map((c) => c.id)).toEqual(['talked', 'empty'])
  })

  it('两个都没消息时保持原顺序（不崩）', () => {
    const out = sortConversations([conv('a', null), conv('b', null)])
    expect(out.map((c) => c.id)).toEqual(['a', 'b'])
  })

  it('不改原数组', () => {
    const input = [conv('a', '2026-09-10T10:00:00'), conv('b', '2026-09-16T10:00:00')]
    sortConversations(input)
    expect(input.map((c) => c.id)).toEqual(['a', 'b'])
  })

  it('pinnedAt 为 null 视作未置顶', () => {
    const out = sortConversations([
      conv('a', '2026-09-16T10:00:00', null),
      conv('b', '2026-09-15T10:00:00', '2026-01-01T00:00:00'),
    ])
    expect(out.map((c) => c.id)).toEqual(['b', 'a'])
  })
})

describe('totalUnread · TabBar 上的红点', () => {
  it('累加所有会话的未读', () => {
    expect(totalUnread([{ unreadCount: 3 }, { unreadCount: 5 }])).toBe(8)
  })

  it('免打扰的会话不计入角标 —— 这正是"免打扰"的含义', () => {
    expect(totalUnread([{ unreadCount: 3 }, { unreadCount: 5, muted: true }])).toBe(3)
  })

  it('没有未读字段时当 0', () => {
    expect(totalUnread([{}, {}])).toBe(0)
  })

  it('空列表给 0', () => {
    expect(totalUnread([])).toBe(0)
  })
})
