import type { ReactNode } from 'react'
import { Navigate, useParams, type RouteObject } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import TabLayout from '@/layouts/TabLayout'
import FullScreenLayout from '@/layouts/FullScreenLayout'
import Login from '@/pages/Login'
import Register from '@/pages/Register'
import ChatList from '@/pages/chat/ChatList'
import ChatRoom from '@/pages/chat/ChatRoom'
import Contacts from '@/pages/contacts/Contacts'
import AgentProfile from '@/pages/contacts/AgentProfile'
import AgentSettings from '@/pages/contacts/AgentSettings'
import CompanionCreate from '@/pages/CompanionCreate'
import Discover from '@/pages/Discover'
import ApplicationDetail from '@/pages/ApplicationDetail'
import AppSession from '@/pages/AppSession'
import JoinSession from '@/pages/JoinSession'
import Me from '@/pages/me/Me'
import Handle from '@/pages/me/Handle'
import Reminders from '@/pages/me/Reminders'
import Notifications from '@/pages/me/Notifications'
import Conversations from '@/pages/me/Conversations'

/**
 * 路由表。抽出来单独一个文件, 而不是写在 `App.tsx` 里, 是为了它**可以被断言**。
 *
 * 路由是这份界面里最容易被无声改坏的东西: 少一个 `end`、把全屏页挂进 tab 分支、
 * 让 `*` 落回一个不存在的路径 —— 这些都不会在编译期报错, 只会在某个用户点进去时
 * 变成一片空白。所以 `routes.test.ts` 直接对这张表做数据断言（不渲染, 原因见那个文件）。
 */

function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

/**
 * 老路径的落点 —— 把 `:参数` 从当前 URL 搬到新路径上。
 *
 * 一次性写死四条 `<Navigate to="/contacts/agent/xxx">` 是不行的: 那一段是**运行时的
 * companionId**, 不是字面量。而"从旧链接进来的人会被送到一个不存在的资料页"这件事,
 * 恰恰只会在有人真的用旧链接时才暴露 —— 也就是那些收藏过页面的老用户。
 */
function RedirectKeepParams({ to }: { to: string }) {
  const params = useParams()
  const path = to.replace(/:([A-Za-z0-9_]+)/g, (_, key: string) => params[key] ?? '')
  return <Navigate to={path} replace />
}

/**
 * 布局 A —— 四 tab。子路由就是 tab bar 上的四项, 一个不多一个不少:
 * tab bar 高亮的是"我在哪一栏", 而任何不在这一支下的页面都不该让某一栏亮起来。
 *
 * `/` 重定向到 `/chat` 而不是留在 `/`: 微信打开就是消息列表, 没有"首页"这个概念。
 */
export const tabRoutes: RouteObject = {
  element: (
    <RequireAuth>
      <TabLayout />
    </RequireAuth>
  ),
  children: [
    { path: '/', element: <Navigate to="/chat" replace /> },
    { path: '/chat', element: <ChatList /> },
    { path: '/contacts', element: <Contacts /> },
    { path: '/discover', element: <Discover /> },
    { path: '/me', element: <Me /> },
  ],
}

/**
 * 布局 B —— 全屏, 没有 tab bar。
 *
 * 与布局 A 是**同级的两条 RequireAuth 分支**, 不是一个嵌套在另一个里面 ——
 * react-router 7 里要让一组路由换掉父级外壳, 这是唯一干净的做法。
 *
 * `/chat/:conversationId` 挂进来时要注意一条硬约定: **它下面不许有静态子路由**。
 * 一旦有人加 `/chat/new`, react-router 会把 `new` 当成会话 id 匹配进来。
 * "发起群聊"这类入口用弹层, 不要用路由。
 *
 * `/contacts/**` 那三条**没有**这条约束 —— `:companionId` 是真的一个人, 而
 * `new` / `settings` 是被路由表静态匹配掉的, 走不到参数里去。
 */
export const fullScreenRoutes: RouteObject = {
  element: (
    <RequireAuth>
      <FullScreenLayout />
    </RequireAuth>
  ),
  children: [
    // 聊天室。**它下面不许有静态子路由** —— 一旦有人加 `/chat/new`,
    // react-router 会把 `new` 当成 conversationId 匹配进来。「发起群聊」用弹层, 不用路由。
    { path: '/chat/:conversationId', element: <ChatRoom /> },

    // 通讯录的下一层: 加一个 Agent, 看一个 Agent, 改一个 Agent
    { path: '/contacts/new', element: <CompanionCreate /> },
    { path: '/contacts/agent/:companionId', element: <AgentProfile /> },
    { path: '/contacts/agent/:companionId/settings', element: <AgentSettings /> },

    // 「我」的下一层: 提醒、通知、会话免打扰、账号ID。
    //
    // 它们**不在** tab 那一支, 因为它们不是"一栏", 是「我」里面的几项设置 ——
    // 进去之后底部不该还压着一条 tab bar。微信也是这个形状: 点进「设置」就没有 tab bar 了。
    { path: '/me/reminders', element: <Reminders /> },
    { path: '/me/notifications', element: <Notifications /> },
    // 会话免打扰的总览。**与聊天室里那个开关是同一份数据**, 不是第二处设置 ——
    // 加这一页的理由只有一个: 聊天室里那个开关要打开那一段对话才看得见, 而人不会
    // 没事去点开一段他没在等的对话, 于是"我把它设成免打扰了"这件事在他奇怪
    // "她怎么一直不回我"的时候一点线索都留不下。
    { path: '/me/conversations', element: <Conversations /> },
    // 改**自己的**账号ID。路径里的 `me` 与后端 `/api/persons/me/handle` 对齐 ——
    // 那个端点的形状本身就是它的安全性(没有第二个参数可以填错), 路径跟着它走,
    // 读的人就不会以为这里还能指定改谁
    { path: '/me/handle', element: <Handle /> },

    { path: '/applications/:applicationId', element: <ApplicationDetail /> },
    { path: '/applications/:applicationId/sessions/:sessionId', element: <AppSession /> },
    // 只有 sessionId 的那条路 —— 从分享链接兑票进来时走这里。
    // 会话自己知道它是什么应用(§16 的 `application.id`), 所以路径里不必再带一次。
    { path: '/sessions/:sessionId', element: <AppSession /> },
    // 分享链接本身。这条路径是公开的(持票即入), 但仍然要求登录 —— 票认的是"谁"
    { path: '/join/:token', element: <JoinSession /> },
  ],
}

export const routes: RouteObject[] = [
  { path: '/login', element: <Login /> },
  // 一期把注册页的 UI 做好, 后端 `AuthController.register` 暂时仍返回 403 ——
  // 二期开注册时只是一拨开关, 前端不用再动。
  { path: '/register', element: <Register /> },

  tabRoutes,
  fullScreenRoutes,

  // ── 老路径的落点 ────────────────────────────────────────────────
  // 新 IA 下"点一个伴侣"从通讯录进**资料页**, 不再是直接进聊天室 —— 所以这四条都
  // 落到 `/contacts/**`。老页面(`Chat.tsx` / `Settings.tsx` / `Companions.tsx`)已随
  // 第 6 步删除, 这里留下来的是给旧链接与人脑里的旧路径用的。
  //
  // `/companions/new` 必须排在 `/companions/:id` **前面**? 不必 —— react-router 按
  // 段的静态程度排名, 静态段永远赢过参数段, 与书写顺序无关。仍然按这个顺序写, 因为
  // 读的人会先看到它。
  { path: '/companions/new', element: <Navigate to="/contacts/new" replace /> },
  { path: '/companions/:id', element: <RedirectKeepParams to="/contacts/agent/:id" /> },
  {
    path: '/companions/:id/settings',
    element: <RedirectKeepParams to="/contacts/agent/:id/settings" />,
  },
  { path: '/companions', element: <Navigate to="/contacts" replace /> },
  // 「应用市场」这个名字保留, 但入口收进「发现」—— 与微信把小程序收进发现是同一种收法
  { path: '/applications', element: <Navigate to="/discover" replace /> },
  { path: '*', element: <Navigate to="/chat" replace /> },
]
