import { useEffect } from 'react'
import { Compass, MessageSquare, User, Users } from 'lucide-react'
import { Outlet } from 'react-router-dom'
import { TabBar, type TabItem } from '@/components/ui/TabBar'
import { UnreadBadge } from '@/components/im/UnreadBadge'
import { useConversationStore, useTotalUnread } from '@/stores/conversations'

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

/** 未读角标挂在「聊天」上。写成常量而不是散在 JSX 里的字面量, 是为了它只有一个出处 */
const CHAT_TAB = '/chat'

/**
 * 会话列表的轮询周期。
 *
 * 与今天 `Chat.tsx` 里那个 `loadUnread` 同频(30s) —— 这是**一期可接受的代价**,
 * 不是终局: 正解是后端给一条按用户的 `GET /api/events`, 事件里带 conversationId,
 * 于是聊天 tab 与聊天室共用一条连接。
 *
 * 之所以先轮询而不是"每个伴侣开一条 SSE": 那是拿连接数换的, 二期加好友加群之后
 * 会失控。而轮询换掉的只是请求频率 —— 二期的切换点只在 `stores/conversations.ts`。
 */
const POLL_MS = 30_000

/**
 * 把未读角标挂到「聊天」上。
 *
 * 抽成纯函数纯粹是为了能被断言: `TabLayout` 本身渲染不起来(它要 router 上下文、
 * zustand 与一个 `useEffect`), 而"角标挂对了 tab 没有"是一条会静默出错的性质 ——
 * 挂到「通讯录」上照样能跑, 只是没人看得懂。
 *
 * `unread <= 0` 时不挂: 不挂的话 `TabBar` 会给每一项都套一个绝对定位的空 span,
 * 而 `UnreadBadge` 自己返回 null —— 那个空壳虽然看不见, 但它是错的形状。
 */
export function decorateTabs(items: TabItem[], unread: number): TabItem[] {
  return items.map((item) =>
    item.to === CHAT_TAB && unread > 0 ? { ...item, badge: <UnreadBadge count={unread} /> } : item,
  )
}

export default function TabLayout() {
  const load = useConversationStore((s) => s.load)
  const unread = useTotalUnread()

  /**
   * 轮询住在这里, 不住在 `ChatList` 里。
   *
   * `TabLayout` 的寿命是"用户在这个应用里", 而 `ChatList` 在切到「通讯录」时就卸载了。
   * 让页面持有轮询的后果是: 用户在通讯录里收到消息, 底部「聊天」上的红点不会亮 ——
   * 而未读角标存在的唯一意义就是"我在别的页面时也知道来消息了"。
   */
  useEffect(() => {
    load()
    const timer = setInterval(load, POLL_MS)
    return () => clearInterval(timer)
  }, [load])

  const items = decorateTabs(TAB_ITEMS, unread)

  return (
    <div className="min-h-dvh bg-surface">
      {/* 窄屏底部 tab bar 是 fixed, 内容要自己让出那 56px + 安全区;
          宽屏它变成左侧 w-16 竖栏, 让位方向随之换成 pl。 */}
      <div className="pb-14 md:pb-0 md:pl-16">
        <Outlet />
      </div>
      <TabBar items={items} />
    </div>
  )
}
