import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { TabBar } from './TabBar'

/**
 * `MemoryRouter` 包住即可在 node 下静态渲染 —— `ApplicationCardBubble.test.tsx`
 * 已经证明这条路可行（那里也是包 `MemoryRouter` 测 `useNavigate`）。
 * 不必引 jsdom: `NavLink` 在无 DOM 的静态渲染里仍会算出 `href`。
 */
const render = (ui: React.ReactElement) =>
  renderToStaticMarkup(<MemoryRouter>{ui}</MemoryRouter>)

const items = [
  { to: '/chat', label: '聊天', icon: <span>1</span>, end: true },
  { to: '/contacts', label: '通讯录', icon: <span>2</span> },
  { to: '/discover', label: '发现', icon: <span>3</span> },
  { to: '/me', label: '我', icon: <span>4</span> },
]

describe('TabBar', () => {
  it('四个 tab 都在', () => {
    const out = render(<TabBar items={items} />)
    for (const label of ['聊天', '通讯录', '发现', '我']) expect(out).toContain(label)
  })

  it('每个 tab 都有 href', () => {
    const out = render(<TabBar items={items} />)
    for (const to of ['/chat', '/contacts', '/discover', '/me']) expect(out).toContain(`href="${to}"`)
  })

  it('没有 badge 就不渲染角标位置', () => {
    expect(render(<TabBar items={items} />)).not.toContain('absolute -right-2')
  })

  it('带 badge 时渲染出来', () => {
    const withBadge = [{ ...items[0], badge: <span>99+</span> }, ...items.slice(1)]
    expect(render(<TabBar items={withBadge} />)).toContain('99+')
  })

  it('窄屏底部横排、宽屏左侧竖排 —— 同一个组件两套 class, 不是两个组件', () => {
    const out = render(<TabBar items={items} />)
    expect(out).toContain('bottom-0')
    expect(out).toContain('md:w-16')
    expect(out).toContain('md:flex-col')
  })

  it('tab 项带安全区内边距 —— iPhone 的 Home 指示条会盖住它', () => {
    expect(render(<TabBar items={items} />)).toContain('pb-safe')
  })
})
