import { describe, expect, it } from 'vitest'
import { groupMessages } from './messageGroups'

const NOW = new Date('2026-09-16T15:30:00')

function msg(id: string, iso: string) {
  return { id, createdAt: iso }
}

describe('groupMessages · 日期分隔条', () => {
  it('空输入给空输出, 不是一条孤零零的"今天"', () => {
    expect(groupMessages([], NOW)).toEqual([])
  })

  it('首条消息之前必有一条分隔', () => {
    const rows = groupMessages([msg('m1', '2026-09-16T10:00:00')], NOW)
    expect(rows[0]).toEqual({ kind: 'separator', key: 'sep-0', label: '今天' })
  })

  it('同一天的多条消息中间不再插分隔', () => {
    const rows = groupMessages(
      [
        msg('m1', '2026-09-16T10:00:00'),
        msg('m2', '2026-09-16T11:00:00'),
        msg('m3', '2026-09-16T14:00:00'),
      ],
      NOW,
    )
    expect(rows.filter((r) => r.kind === 'separator')).toHaveLength(1)
  })

  it('跨天处插入新分隔', () => {
    const rows = groupMessages(
      [
        msg('m1', '2026-09-16T10:00:00'),
        msg('m2', '2026-09-16T11:00:00'),
        msg('m3', '2026-09-15T09:00:00'),
      ],
      NOW,
    )
    expect(rows.map((r) => (r.kind === 'separator' ? `[${r.label}]` : r.message.id))).toEqual([
      '[今天]', 'm1', 'm2', '[昨天]', 'm3',
    ])
  })

  it('消息按原顺序保留, 函数不重排', () => {
    // 后端给的是升序。这个函数若"顺手排一下", 会让消息流的语义和数据库脱节
    const rows = groupMessages(
      [msg('m1', '2026-09-16T10:00:00'), msg('m2', '2026-09-16T09:00:00')],
      NOW,
    )
    const ids = rows.filter((r) => r.kind === 'message').map((r) => r.message.id)
    expect(ids).toEqual(['m1', 'm2'])
  })

  it('分隔条的 key 与消息 id 不冲突', () => {
    // React 用它做 reconciliation; 撞了会导致分隔条在列表刷新时错位
    const rows = groupMessages(
      [msg('m1', '2026-09-16T10:00:00'), msg('m2', '2026-09-15T09:00:00')],
      NOW,
    )
    const keys = rows.map((r) => r.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('隔了三天给完整日期', () => {
    const rows = groupMessages([msg('m1', '2026-09-13T10:00:00')], NOW)
    expect(rows[0]).toMatchObject({ label: '2026年9月13日' })
  })
})
