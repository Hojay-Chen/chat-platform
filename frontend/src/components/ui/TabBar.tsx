import type { ReactNode } from 'react'
import { NavLink } from 'react-router-dom'

/**
 * 四 tab 导航。
 *
 * **同一个组件两套形态**, 不是两个组件:
 * - 窄屏: 底部横排（手机拇指够得着）
 * - 宽屏 ≥768px: 左侧竖排图标栏（微信桌面版那套 —— 桌面窗口很宽时把手机布局横向拉伸
 *   是最容易露怯的做法, 一屏能放下 1400px 却让会话列表铺满它）
 *
 * 二期如果要加 tab（比如「群聊」独立成项）, 只是往 `items` 加一行 ——
 * 这正是把它做成数组驱动而不是写死四个 `<NavLink>` 的理由。
 */

export interface TabItem {
  to: string
  label: string
  icon: ReactNode
  /** 未读角标 */
  badge?: ReactNode
  /** 精确匹配 —— 只有「聊天」需要（`/chat` 不该在 `/chat/:id` 时也高亮成当前 tab） */
  end?: boolean
}

export function TabBar({ items }: { items: TabItem[] }) {
  return (
    <nav
      className="fixed inset-x-0 bottom-0 z-20 flex border-t border-line bg-raised pb-safe
                 md:inset-y-0 md:left-0 md:right-auto md:h-auto md:w-16 md:flex-col md:border-r md:border-t-0 md:pb-0"
    >
      {items.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.end}
          className={({ isActive }) =>
            `tab-item py-2 md:py-3 ${isActive ? 'text-accent' : 'text-ink-faint hover:text-ink'}`
          }
        >
          <span className="relative">
            {item.icon}
            {item.badge && <span className="absolute -right-2 -top-1.5">{item.badge}</span>}
          </span>
          <span>{item.label}</span>
        </NavLink>
      ))}
    </nav>
  )
}
