import { describe, expect, it } from 'vitest'
import { applyTheme, pickTheme } from './theme'

/**
 * 只测判定, 不测应用 —— `applyTheme` 要碰 `document`, 而 vitest 跑在 `environment: 'node'`
 * 下, 那里没有 `document`。"主题该是哪个"是纯逻辑, 能测; "class 加上去了没有"
 * 只能靠真机看一眼。把两者分开写, 前者就永远是可测的。
 */

describe('pickTheme', () => {
  it('没存过时跟随系统', () => {
    expect(pickTheme(null, true)).toBe('dark')
    expect(pickTheme(null, false)).toBe('light')
  })

  it('存过就以存的为准 —— 用户手动选过, 不该被系统设置再改回去', () => {
    expect(pickTheme('light', true)).toBe('light')
    expect(pickTheme('dark', false)).toBe('dark')
  })

  it('存了个不认识的值时退回系统偏好, 而不是崩或给出 undefined', () => {
    // 用户或扩展往 localStorage 里塞了什么都是可能的
    expect(pickTheme('solarized', true)).toBe('dark')
    expect(pickTheme('', false)).toBe('light')
  })
})

describe('applyTheme 在无 DOM 环境下不炸', () => {
  it('node 下调用是空操作 —— 模块被 import 时不该要求宿主有 document', () => {
    expect(() => applyTheme('dark')).not.toThrow()
  })
})
