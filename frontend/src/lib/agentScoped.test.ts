import { describe, expect, it } from 'vitest'
import {
  isDone,
  mergeByAgent,
  sortNotifications,
  sortReminders,
  splitReminders,
  type PerAgent,
} from './agentScoped'
import type { Notification, Reminder } from '@/types'

const reminder = (id: string, remindAt: string, status = 'pending'): Reminder => ({
  id,
  type: 'user_set',
  title: id,
  remindAt,
  status,
})

const notification = (id: string, createdAt: string, read = false): Notification => ({
  id,
  type: 'proactive',
  title: id,
  read,
  createdAt,
})

describe('合并每个 Agent 的那一份', () => {
  it('每一行都带上它属于谁', () => {
    const groups: PerAgent<Reminder>[] = [
      { companionId: 'a', name: '晚晚', items: [reminder('r1', '2026-09-18T09:00:00')] },
      { companionId: 'b', name: '林夏', items: [reminder('r2', '2026-09-19T09:00:00')] },
    ]
    const rows = mergeByAgent(groups)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toMatchObject({ agentId: 'a', agentName: '晚晚' })
    expect(rows[1]).toMatchObject({ agentId: 'b', agentName: '林夏' })
  })

  it('两个 Agent 各有一条同名提醒时, 它们是两行而不是一行', () => {
    // 这正是 agentName 存在的理由: 不带归属的话用户看到两条一模一样的行, 会以为重复了
    const groups: PerAgent<Reminder>[] = [
      { companionId: 'a', name: '晚晚', items: [reminder('记得喝水', '2026-09-18T09:00:00')] },
      { companionId: 'b', name: '林夏', items: [reminder('记得喝水', '2026-09-19T09:00:00')] },
    ]
    const rows = mergeByAgent(groups)
    expect(rows.map((r) => r.item.title)).toEqual(['记得喝水', '记得喝水'])
    expect(rows.map((r) => r.agentName)).toEqual(['晚晚', '林夏'])
  })

  it('谁也没有时是空数组, 不是 undefined', () => {
    expect(mergeByAgent([])).toEqual([])
    expect(mergeByAgent([{ companionId: 'a', name: '晚晚', items: [] }])).toEqual([])
  })
})

describe('提醒的排序与分组', () => {
  it('没办完的在前, 各自按时间正序 —— 这是待办列表, 不是动态流', () => {
    const rows = mergeByAgent<Reminder>([
      {
        companionId: 'a',
        name: '晚晚',
        items: [
          reminder('晚了', '2026-09-25T09:00:00'),
          reminder('办完了', '2026-09-01T09:00:00', 'done'),
          reminder('早', '2026-09-18T09:00:00'),
        ],
      },
    ])
    expect(sortReminders(rows).map((r) => r.item.title)).toEqual(['早', '晚了', '办完了'])
  })

  it('分组不丢行', () => {
    const rows = mergeByAgent<Reminder>([
      {
        companionId: 'a',
        name: '晚晚',
        items: [
          reminder('p1', '2026-09-18T09:00:00'),
          reminder('d1', '2026-09-01T09:00:00', 'done'),
          reminder('p2', '2026-09-19T09:00:00'),
        ],
      },
    ])
    const { pending, done } = splitReminders(rows)
    expect(pending.map((r) => r.item.title)).toEqual(['p1', 'p2'])
    expect(done.map((r) => r.item.title)).toEqual(['d1'])
    expect(pending.length + done.length).toBe(rows.length)
  })

  it('只认 done 是"办完了", 别的状态(含未知的新状态)都算没办完', () => {
    // 后端加一个 'snoozed' 时, 它该出现在待办里, 而不是从两个分组的缝里掉下去
    expect(isDone(reminder('x', '2026-09-18T09:00:00', 'done'))).toBe(true)
    expect(isDone(reminder('x', '2026-09-18T09:00:00', 'snoozed'))).toBe(false)
    expect(isDone(reminder('x', '2026-09-18T09:00:00'))).toBe(false)
  })

  it('时间解析不出来的那条排到最后, 不把整个排序搅成 NaN', () => {
    const rows = mergeByAgent<Reminder>([
      {
        companionId: 'a',
        name: '晚晚',
        items: [reminder('坏的', '不是时间'), reminder('好的', '2026-09-18T09:00:00')],
      },
    ])
    expect(sortReminders(rows).map((r) => r.item.title)).toEqual(['好的', '坏的'])
  })

  it('不改原数组', () => {
    const rows = mergeByAgent<Reminder>([
      {
        companionId: 'a',
        name: '晚晚',
        items: [reminder('晚', '2026-09-25T09:00:00'), reminder('早', '2026-09-18T09:00:00')],
      },
    ])
    const before = rows.map((r) => r.item.title)
    sortReminders(rows)
    expect(rows.map((r) => r.item.title)).toEqual(before)
  })
})

describe('通知的排序', () => {
  it('最新的在前 —— 通知是流水, 最新发生的才值得看', () => {
    const rows = mergeByAgent<Notification>([
      {
        companionId: 'a',
        name: '晚晚',
        items: [
          notification('旧', '2026-09-10T09:00:00'),
          notification('新', '2026-09-16T09:00:00'),
          notification('中', '2026-09-13T09:00:00'),
        ],
      },
    ])
    expect(sortNotifications(rows).map((r) => r.item.title)).toEqual(['新', '中', '旧'])
  })

  it('未读的**不**被提到最上面', () => {
    // 打乱时间顺序去看流水, 会让人读不懂那几天发生了什么。
    // 未读该用视觉标出来(小圆点), 不是靠重排。
    const rows = mergeByAgent<Notification>([
      {
        companionId: 'a',
        name: '晚晚',
        items: [
          notification('新的已读', '2026-09-16T09:00:00', true),
          notification('旧的未读', '2026-09-10T09:00:00', false),
        ],
      },
    ])
    expect(sortNotifications(rows).map((r) => r.item.title)).toEqual(['新的已读', '旧的未读'])
  })

  it('跨 Agent 的通知按时间混排, 不是按 Agent 分块', () => {
    const rows = mergeByAgent<Notification>([
      { companionId: 'a', name: '晚晚', items: [notification('a-旧', '2026-09-10T09:00:00')] },
      { companionId: 'b', name: '林夏', items: [notification('b-新', '2026-09-16T09:00:00')] },
    ])
    expect(sortNotifications(rows).map((r) => r.item.title)).toEqual(['b-新', 'a-旧'])
  })
})
