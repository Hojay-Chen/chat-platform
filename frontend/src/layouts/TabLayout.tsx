import { Compass, MessageSquare, User, Users } from 'lucide-react'
import { Outlet } from 'react-router-dom'
import { TabBar, type TabItem } from '@/components/ui/TabBar'

/**
 * 布局 A: 四 tab。
 *
 * 四项与微信一一对应 —— 聊天 / 通讯录 / 发现 / 我。这个对应关系是刻意的:
 * 用户不需要学新的信息架构, 他只需要发现「多出来的那个东西在哪」。
 *
 * `/chat` 上的 `end` 不能少: 少了它, 在 `/chat/:conversationId`（全屏那一支）
 * 时「聊天」tab 仍会算作激活 —— 而那时 tab bar 根本不在屏幕上。
 */
export const TAB_ITEMS: TabItem[] = [
  { to: '/chat', label: '聊天', icon: <MessageSquare size={22} />, end: true },
  { to: '/contacts', label: '通讯录', icon: <Users size={22} /> },
  { to: '/discover', label: '发现', icon: <Compass size={22} /> },
  { to: '/me', label: '我', icon: <User size={22} /> },
]

export default function TabLayout() {
  return (
    <div className="min-h-dvh bg-surface">
      {/* 窄屏底部 tab bar 是 fixed, 内容要自己让出那 56px + 安全区;
          宽屏它变成左侧 w-16 竖栏, 让位方向随之换成 pl。 */}
      <div className="pb-14 md:pb-0 md:pl-16">
        <Outlet />
      </div>
      <TabBar items={TAB_ITEMS} />
    </div>
  )
}
