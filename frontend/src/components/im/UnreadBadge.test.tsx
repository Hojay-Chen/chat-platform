import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { UnreadBadge } from './UnreadBadge'

/**
 * 一律用 `renderToStaticMarkup`, 不引 jsdom / testing-library —— 与仓里既有测试
 * （`SurfaceHost.test.tsx` / `ApplicationCardBubble.test.tsx`）同一套手法:
 * 这些件是纯 props 驱动的展示件, 静态标记就能把契约说清楚。
 */
const html = (count: number, muted = false) =>
  renderToStaticMarkup(<UnreadBadge count={count} muted={muted} />)

describe('UnreadBadge', () => {
  it('没有未读时什么都不画 —— 空 span 仍占位, 会让行高抖动', () => {
    expect(html(0)).toBe('')
    expect(html(-1)).toBe('')
  })

  it('有未读时出数字', () => {
    expect(html(3)).toContain('3')
  })

  it('99 仍然是 99', () => {
    expect(html(99)).toContain('99')
  })

  it('超过 99 收敛成 99+ —— 三位数会把列表挤歪', () => {
    expect(html(100)).toContain('99+')
    expect(html(5000)).toContain('99+')
  })

  it('免打扰只出点, 不出数字 —— 这正是"免打扰"的含义', () => {
    const out = html(7, true)
    expect(out).not.toContain('7')
    expect(out).toContain('rounded-full')
  })

  it('免打扰且无未读时仍然什么都不画', () => {
    expect(html(0, true)).toBe('')
  })
})
