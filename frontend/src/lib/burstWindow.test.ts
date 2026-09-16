import { describe, expect, it } from 'vitest'
import {
  DEFAULT_GATHER_MS,
  MAX_GATHER_MS,
  MIN_GATHER_MS,
  nextDelay,
  nextWindowMs,
  recordGap,
  resolveDelay,
} from './burstWindow'

/**
 * 这套规则原先在 `Chat.tsx` 里以闭包 + ref 的形式存在, 一条测试都没有。
 * 抽成纯函数之后下面每一条都是新获得的确定性 —— 尤其是钳位那两条,
 * 它们决定了"用户打字极快"和"打字极慢"两端的行为。
 */

describe('recordGap · 滑动窗口', () => {
  it('正常间隔记录进去', () => {
    expect(recordGap([], 500)).toEqual([500])
  })

  it('0 间隔丢弃 —— 同一毫秒的两次点击会把均值拉垮', () => {
    expect(recordGap([300], 0)).toEqual([300])
  })

  it('负间隔丢弃', () => {
    expect(recordGap([300], -5)).toEqual([300])
  })

  it('满一分钟的间隔不算连发, 丢弃', () => {
    expect(recordGap([300], 60000)).toEqual([300])
    expect(recordGap([300], 59999)).toEqual([300, 59999])
  })

  it('只留最近 5 条 —— 十分钟前打字慢不该影响现在', () => {
    let gaps: number[] = []
    for (const g of [100, 200, 300, 400, 500, 600]) gaps = recordGap(gaps, g)
    expect(gaps).toEqual([200, 300, 400, 500, 600])
  })

  it('不改原数组', () => {
    const original = [100]
    recordGap(original, 200)
    expect(original).toEqual([100])
  })
})

describe('nextWindowMs · 自适应窗口', () => {
  it('没有历史时给默认 1400', () => {
    expect(nextWindowMs([])).toBe(DEFAULT_GATHER_MS)
  })

  it('取均值的 1.5 倍', () => {
    expect(nextWindowMs([1000])).toBe(1500)
    expect(nextWindowMs([600, 1400])).toBe(1500) // 均值 1000
  })

  it('均值 1500 的 1.5 倍是 2250, 被上限压到 2200', () => {
    expect(nextWindowMs([1000, 2000])).toBe(MAX_GATHER_MS)
  })

  it('打字极快时被下限托住 —— 不给 800 以下', () => {
    // 400 * 1.5 = 600, 低于下限
    expect(nextWindowMs([400])).toBe(MIN_GATHER_MS)
  })

  it('打字极慢时被上限压住', () => {
    // 2000 * 1.5 = 3000, 高于 2200
    expect(nextWindowMs([2000])).toBe(MAX_GATHER_MS)
  })

  it('5 条相同间隔仍取 1.5 倍', () => {
    expect(nextWindowMs([1000, 1000, 1000, 1000, 1000])).toBe(1500)
  })
})

describe('nextDelay · 封顶从 batch 起点算', () => {
  it('刚开始连发 —— 等一个完整窗口', () => {
    expect(nextDelay([], 0)).toBe(DEFAULT_GATHER_MS)
  })

  it('已经等了 2 秒 —— 只等剩下的 200ms', () => {
    expect(nextDelay([], 2000)).toBe(200)
  })

  it('已经用满 2200 —— 立刻发', () => {
    expect(nextDelay([], MAX_GATHER_MS)).toBe(0)
  })

  it('超时也不会返回负数', () => {
    // 没有这条, 一直发就一直等, 消息永远发不出去
    expect(nextDelay([], 5000)).toBe(0)
  })

  it('剩余时间比窗口短时, 取剩余时间', () => {
    // 窗口是 1500, 但只剩 200
    expect(nextDelay([1000], 2000)).toBe(200)
  })

  it('剩余时间比窗口长时, 取窗口', () => {
    expect(nextDelay([1000], 100)).toBe(1500)
  })
})

describe('resolveDelay · 两种会话共用一套发送状态机', () => {
  it("'adaptive' 走数字人会话的自适应窗口", () => {
    expect(resolveDelay('adaptive', [], 0)).toBe(DEFAULT_GATHER_MS)
  })

  it('固定 0 = 真人会话, 回车即发', () => {
    expect(resolveDelay(0, [], 0)).toBe(0)
  })

  it('固定窗口忽略间隔历史', () => {
    expect(resolveDelay(250, [5000, 5000], 0)).toBe(250)
  })

  it('负数被夹到 0', () => {
    expect(resolveDelay(-100, [], 0)).toBe(0)
  })
})
