import { describe, expect, it } from 'vitest'
import { truncate } from './text'

describe('truncate', () => {
  it('短于上限时原样返回, 不加省略号', () => {
    expect(truncate('你好', 10)).toBe('你好')
  })

  it('刚好等于上限时也不加', () => {
    expect(truncate('12345', 5)).toBe('12345')
  })

  it('超出时截断并补省略号 —— 省略号告诉用户"被截了", 而不是"就这么短"', () => {
    expect(truncate('123456', 5)).toBe('12345…')
  })

  it('空串给空串', () => {
    expect(truncate('', 5)).toBe('')
  })

  it('n 为 0 时只剩省略号', () => {
    expect(truncate('abc', 0)).toBe('…')
  })

  it('按 UTF-16 码元截断 —— emoji 会被切开, 这是已知取舍', () => {
    // 记在这里是为了让下一个人知道这是**已知**行为而不是意外:
    // 修它要用 Intl.Segmenter, 而会话列表摘要截断不值得引这个复杂度
    expect(truncate('😀😀', 1)).toBe('\ud83d…')
  })
})
