import type { ReactNode } from 'react'
import { Navigate, type RouteObject } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import TabLayout from '@/layouts/TabLayout'
import FullScreenLayout from '@/layouts/FullScreenLayout'
import Login from '@/pages/Login'
import Register from '@/pages/Register'
import Companions from '@/pages/Companions'
import ChatList from '@/pages/chat/ChatList'
import ChatRoom from '@/pages/chat/ChatRoom'
import CompanionCreate from '@/pages/CompanionCreate'
import Chat from '@/pages/Chat'
import Settings from '@/pages/Settings'
import ApplicationMarket from '@/pages/ApplicationMarket'
import ApplicationDetail from '@/pages/ApplicationDetail'
import AppSession from '@/pages/AppSession'
import JoinSession from '@/pages/JoinSession'
import Me from '@/pages/me/Me'

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
    // 第 6 步换成 Contacts（同一份数据的另一种排法: 按人 vs 按最近消息）
    { path: '/contacts', element: <Companions /> },
    { path: '/discover', element: <ApplicationMarket /> },
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
    { path: '/applications/:applicationId', element: <ApplicationDetail /> },
    { path: '/applications/:applicationId/sessions/:sessionId', element: <AppSession /> },
    // 只有 sessionId 的那条路 —— 从分享链接兑票进来时走这里。
    // 会话自己知道它是什么应用(§16 的 `application.id`), 所以路径里不必再带一次。
    { path: '/sessions/:sessionId', element: <AppSession /> },
    // 分享链接本身。这条路径是公开的(持票即入), 但仍然要求登录 —— 票认的是"谁"
    { path: '/join/:token', element: <JoinSession /> },

    // ── 以下四条是老路径, 原样保留到各自的步骤再迁 ──────────────────
    // 不在这里提前改挂新路径, 是因为 Chat / Settings 读的是 `:id`（一个 companionId）,
    // 而新 IA 的 `/chat/:conversationId` 里那一段是会话 id —— 那是两回事,
    // 提前套上去只会写出一句要不了多久就得撤掉的谎。它们在第 5、6 步随新页面一起换。
    { path: '/companions/new', element: <CompanionCreate /> },
    { path: '/companions/:id', element: <Chat /> },
    { path: '/companions/:id/settings', element: <Settings /> },
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
  { path: '/companions', element: <Navigate to="/contacts" replace /> },
  // 「应用市场」这个名字保留, 但入口收进「发现」—— 与微信把小程序收进发现是同一种收法
  { path: '/applications', element: <Navigate to="/discover" replace /> },
  { path: '*', element: <Navigate to="/chat" replace /> },
]
