import type { ReactNode } from 'react'
import { MessageSquare } from 'lucide-react'

/**
 * 登录 / 注册这一屏的外壳 —— 产品的**正门**。
 *
 * <h2>它此前是"数字伴侣"时代最后一块没换掉的招牌</h2>
 *
 * 视觉层重做之后, 这里仍然是一个光秃秃的 `<h1>Luxera Companion</h1>` 配一句
 * 「一个会记住你的数字伴侣」—— 而那个定位正是这次重做要拿掉的东西。仓 1 现在
 * 是一个微信式的聊天平台, 伴侣/Agent 只是它区别于微信的那块生态, 不是它本身。
 * 门面写着旧定位, 里面做得再对也是错的。
 *
 * <h2>三处具体的改动, 以及为什么</h2>
 *
 * - **加了品牌标记**: 一个 accent 方块 + 图标, 与仓 2 控制台顶部那个同构
 *   (`bg-accent text-accent-ink rounded-*` + lucide 图标)。两个前端因此看起来
 *   是一家做的 —— 重做之前它们连标记都没有, 只有一行字
 * - **字阶归位**: 原来是 `text-3xl` 常规字重, 不在设计系统的字阶上。改用
 *   `.page-title`(`text-2xl/600/tracking-tight`)—— 全站页面标题都是它
 * - **`min-h-screen` 保留**: 这一屏不是 `FullScreenLayout` 的子路由(它在两条
 *   布局路由之外), 自己就是根, 所以那个"页面滚不到底"的坑与它无关
 */
export default function AuthShell({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle?: string
  children: ReactNode
}) {
  return (
    <div className="flex min-h-screen items-center justify-center overflow-hidden bg-surface px-4">
      <div className="relative w-full max-w-sm">
        <div className="mb-8 text-center">
          <span className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-xl bg-accent text-accent-ink">
            <MessageSquare size={22} />
          </span>
          <h1 className="page-title">{title}</h1>
          {subtitle && <p className="mt-2 text-sm text-ink-soft">{subtitle}</p>}
        </div>
        <div className="card p-6">{children}</div>
      </div>
    </div>
  )
}
