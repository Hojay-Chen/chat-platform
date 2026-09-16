import { describe, expect, it } from 'vitest'
import { dateLabel, formatTime, isSameDay, relativeListTime, timeAgo } from './time'

/**
 * 这些函数是从 `Chat.tsx` 里原样搬出来的 —— 搬之前它们一条测试都没有,
 * 因为要测它们就得先渲染一个需要 fetch + SSE 的 1397 行组件。
 *
 * `now` 一律显式传入。相对时间的断言如果依赖测试运行时的真实时钟,
 * 就会在月初/月末/午夜前后随机变红, 那种红比没有测试更糟。
 */

/** 2026-09-16 是星期三 */
const NOW = new Date('2026-09-16T15:30:00')

/** 相对 NOW 偏移若干天/小时, 保持本地时区语义 */
function ago({ days = 0, hours = 0, minutes = 0 } = {}): string {
  return new Date(
    NOW.getTime() - days * 86400000 - hours * 3600000 - minutes * 60000,
  ).toISOString()
}

describe('isSameDay', () => {
  it('同一天的不同时刻算是同一天', () => {
    expect(isSameDay(new Date('2026-09-16T00:01'), new Date('2026-09-16T23:59'))).toBe(true)
  })

  it('跨过午夜就不是了', () => {
    expect(isSameDay(new Date('2026-09-15T23:59'), new Date('2026-09-16T00:01'))).toBe(false)
  })
})

describe('dateLabel · 消息流的日期分隔条', () => {
  it('今天', () => {
    expect(dateLabel(ago({ hours: 3 }), NOW)).toBe('今天')
  })

  it('昨天', () => {
    expect(dateLabel(ago({ days: 1 }), NOW)).toBe('昨天')
  })

  it('更早给完整日期', () => {
    expect(dateLabel(ago({ days: 3 }), NOW)).toBe('2026年9月13日')
  })

  it('昨天与更早的分界是日历天, 不是 24 小时', () => {
    // 只隔了 25 分钟, 但跨了午夜 —— 必须说"昨天", 不能说"今天"
    const justBeforeMidnight = new Date('2026-09-16T00:05:00')
    expect(dateLabel('2026-09-15T23:40:00', justBeforeMidnight)).toBe('昨天')
  })
})

describe('formatTime', () => {
  it('只出时刻', () => {
    expect(formatTime('2026-09-16T09:05:00')).toBe('09:05')
  })
})

describe('relativeListTime · 会话列表右上角', () => {
  it('今天给时刻', () => {
    expect(relativeListTime(ago({ hours: 3 }), NOW)).toBe('12:30')
  })

  it('昨天给"昨天" —— 不给星期几', () => {
    expect(relativeListTime(ago({ days: 1 }), NOW)).toBe('昨天')
  })

  it('一周内给星期几', () => {
    expect(relativeListTime(ago({ days: 3 }), NOW)).toBe('星期日')
  })

  it('刚好满一周就换成日期, 不再说星期几', () => {
    // 差 7 天已经是"上周"了, 再说"星期三"会和这周三混淆
    expect(relativeListTime(ago({ days: 7 }), NOW)).toBe('2026/9/9')
  })

  it('更早给短日期', () => {
    expect(relativeListTime(ago({ days: 10 }), NOW)).toBe('2026/9/6')
  })

  it('跨午夜但只隔 25 分钟 —— 是"昨天", 不是"00:05"', () => {
    // 这条钉的是"用日历天算, 不用毫秒差算"。去掉实现里的 startOfDay 它就会红。
    const justAfterMidnight = new Date('2026-09-16T00:05:00')
    expect(relativeListTime('2026-09-15T23:40:00', justAfterMidnight)).toBe('昨天')
  })
})

describe('timeAgo · 她最近 / 通知', () => {
  it('不到一分钟说"刚刚"', () => {
    expect(timeAgo(ago({ minutes: 0 }), NOW)).toBe('刚刚')
  })

  it('分钟', () => {
    expect(timeAgo(ago({ minutes: 12 }), NOW)).toBe('12 分钟前')
  })

  it('小时', () => {
    expect(timeAgo(ago({ hours: 3 }), NOW)).toBe('3 小时前')
  })

  it('天', () => {
    expect(timeAgo(ago({ days: 5 }), NOW)).toBe('5 天前')
  })

  it('满 30 天就换成日期', () => {
    expect(timeAgo(ago({ days: 40 }), NOW)).toBe('2026年8月7日')
  })

  it('59 分钟仍是分钟, 60 分钟才进位到小时', () => {
    expect(timeAgo(ago({ minutes: 59 }), NOW)).toBe('59 分钟前')
    expect(timeAgo(ago({ minutes: 60 }), NOW)).toBe('1 小时前')
  })
})
