/**
 * 连发聚合的窗口估算 —— 从 `Chat.tsx:242-257, 348-370` 抽出来的纯函数。
 *
 * 为什么这套东西必须存在（而不是"IM 不需要聚合，删掉"）:
 *
 * 后端的 `MessageController` 与 `Chat.tsx` 的注释是同一句话 —— **一次请求至多一次回复**。
 * 用户打"在吗" + "我想你了"再回车, 如果发两次请求, 对面就会回两次。而真人收到连发
 * 也只回一次 —— 所以聚合恰恰是在**仿真人类行为**, 不是数字人平台的特殊需求。
 * 删掉它是行为回归, 不是重构。
 *
 * 它今天完全没被测过, 因为它和 React 状态机缠在一起。抽出来之后:
 * "1.5 倍"、"800~2200 钳位"、"封顶从 batch 起点算" 这三条规则第一次可以被断言。
 */

/** 一次连发从第一句到发出的总时长上限 */
export const MAX_GATHER_MS = 2200
/** 窗口下限 —— 再快的用户也得等这么久, 否则等于没聚合 */
export const MIN_GATHER_MS = 800
/** 没有历史间隔时的默认窗口 */
export const DEFAULT_GATHER_MS = 1400
/** 超过一分钟的间隔不算"连发", 不参与估算 */
export const MAX_GAP_MS = 60000
/** 参与估算的最近间隔条数 */
export const GAP_WINDOW = 5

/**
 * 记一次发送间隔, 返回新的滑动窗口。
 *
 * 上限 5 条而不是全部 —— 用户十分钟前打字慢, 不该影响他现在打字快时的窗口。
 * 越界值直接丢弃: 间隔为 0（同一毫秒的两次点击）会把均值拉垮, 超过一分钟的根本
 * 不是连发。返回新数组而不是原地改, 是为了让这条规则可测。
 */
export function recordGap(gaps: readonly number[], gap: number): number[] {
  if (!(gap > 0 && gap < MAX_GAP_MS)) return [...gaps]
  const next = [...gaps, gap]
  return next.length > GAP_WINDOW ? next.slice(next.length - GAP_WINDOW) : next
}

/** 自适应静默窗口: 近期发送间隔均值的 1.5 倍, 钳在 800~max */
export function nextWindowMs(gaps: readonly number[], max = MAX_GATHER_MS): number {
  if (gaps.length === 0) return DEFAULT_GATHER_MS
  const avg = gaps.reduce((a, b) => a + b, 0) / gaps.length
  return Math.max(MIN_GATHER_MS, Math.min(max, avg * 1.5))
}

/**
 * 这一次该等多久。
 *
 * 与 `nextWindowMs` 的区别是那个"封顶从 batch 起点算": 用户已经连发了 2 秒,
 * 剩下的等待时间就不足一个完整窗口了。没有这一条, 一直发就一直等, 消息永远发不出去。
 */
export function nextDelay(
  gaps: readonly number[],
  elapsedMs: number,
  max = MAX_GATHER_MS,
): number {
  const remaining = Math.max(0, max - elapsedMs)
  return Math.min(nextWindowMs(gaps, max), remaining)
}

/**
 * 窗口策略 —— 一期只有数字人会话, 用 `'adaptive'`（零行为回归）。
 *
 * 二期的真人会话传 `0`: 人对人发消息不该有静默窗口, 回车即发。
 * 这个参数就是"同一套发送状态机服务两种会话"的开关。
 */
export function resolveDelay(
  mode: 'adaptive' | number,
  gaps: readonly number[],
  elapsedMs: number,
): number {
  return mode === 'adaptive' ? nextDelay(gaps, elapsedMs) : Math.max(0, mode)
}
