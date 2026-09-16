import { describe, expect, it } from 'vitest'
import { TAB_ITEMS, decorateTabs } from './TabLayout'

/**
 * 只测 `decorateTabs` —— `TabLayout` 本身渲染不起来(要 router 上下文、zustand、
 * 一个 `useEffect`)。这里不渲染组件, 只断言那个映射。
 *
 * `TAB_ITEMS` 本身(有哪四项、路径是什么)由 `routes.test.ts` 与路由表交叉验证,
 * 不在这里重复 —— 同一件事测两遍, 两遍的口径迟早会不一样。
 */
describe('decorateTabs · 未读角标挂在哪一项上', () => {
  it('有未读时挂在「聊天」上', () => {
    const items = decorateTabs(TAB_ITEMS, 3)
    expect(items.find((i) => i.to === '/chat')?.badge).toBeTruthy()
  })

  it('其余三项一个都不挂 —— 挂错了照样能跑, 只是没人看得懂', () => {
    const items = decorateTabs(TAB_ITEMS, 3)
    for (const item of items.filter((i) => i.to !== '/chat')) {
      expect(item.badge, `${item.to} 不该有角标`).toBeUndefined()
    }
  })

  it('没有未读时一个都不挂', () => {
    for (const item of decorateTabs(TAB_ITEMS, 0)) {
      expect(item.badge).toBeUndefined()
    }
  })

  it('不改原数组 —— TAB_ITEMS 是模块级常量, 被改一次就永久带着角标', () => {
    decorateTabs(TAB_ITEMS, 5)
    expect(TAB_ITEMS.every((i) => i.badge === undefined)).toBe(true)
  })

  it('四项一个不少 —— 映射不能顺手丢掉谁', () => {
    expect(decorateTabs(TAB_ITEMS, 1).map((i) => i.to))
      .toEqual(TAB_ITEMS.map((i) => i.to))
  })
})
