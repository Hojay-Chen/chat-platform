import { AlertCircle } from 'lucide-react'

/**
 * 资料页 tab 内部的三态。
 *
 * 老面板在加载时**什么都不画**: 一个空白区域。空白和"它真的没有记忆"长得一模一样,
 * 而这两种情况的下一步动作完全相反 —— 一个该等, 一个该去聊两句。所以加载态必须是
 * 看得见的东西。
 */

export function PanelLoading({ label = '加载中…' }: { label?: string }) {
  return (
    <p className="py-8 text-center text-xs text-ink-faint">{label}</p>
  )
}

/**
 * 骨架屏的一根条。
 *
 * <h2>为什么不是一个转圈</h2>
 *
 * 一个圈只说"在忙", 不说"忙完了会出来什么"。骨架按**最终布局的形状**摆, 于是内容
 * 到位时不会有跳动 —— 眼睛已经知道该看哪儿了。这是应用那几屏(市场、详情、会话)
 * 原先只有一句「正在加载…」的那一处的问题: 那句字既不是形状, 也不说会出来什么。
 *
 * 它自己不含 `aria` 语义 —— 读屏该听到的是外层那一句"正在加载", 而不是一排空盒子。
 */
export function Skeleton({ className = '' }: { className?: string }) {
  return <span aria-hidden className={`skeleton ${className}`} />
}

/**
 * 一列骨架行 —— 头像 + 两行字 + 右侧一小块。
 *
 * 三种应用列表(参与者、可分享的人、可以开的应用)都是这个形状, 所以它被摆在这里
 * 一次, 而不是每屏各写一遍。
 */
export function SkeletonRows({ rows = 3, avatar = false }: { rows?: number; avatar?: boolean }) {
  return (
    <div className="space-y-2" aria-hidden>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-3 rounded-lg border border-line px-3 py-2.5">
          {avatar && <Skeleton className="h-9 w-9 shrink-0 rounded-lg" />}
          <span className="min-w-0 flex-1 space-y-1.5">
            <Skeleton className="h-3 w-2/5" />
            <Skeleton className="h-2.5 w-3/5" />
          </span>
          <Skeleton className="h-6 w-14 shrink-0 rounded-lg" />
        </div>
      ))}
    </div>
  )
}

/**
 * 面板级的错误。**不复用页面级的错误条** —— 页面级那一条带「知道了」和重试,
 * 而这里只是一块数据没拿到, 说清楚哪一块就够了, 给一个点了重拉整页的按钮是过度反应。
 */
export function PanelError({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-2 rounded-xl border border-danger/30 bg-danger/10 px-3 py-2.5 text-xs text-danger">
      <AlertCircle size={14} className="mt-0.5 shrink-0" />
      <span className="min-w-0 flex-1">{message}</span>
    </div>
  )
}

/** 小组标题 —— 四个面板里重复出现十几次的形状 */
export function PanelSection({
  title,
  hint,
  children,
}: {
  title: string
  hint?: string
  children: React.ReactNode
}) {
  return (
    <section>
      <div className="mb-2">
        <h4 className="text-sm font-medium text-ink">{title}</h4>
        {hint && <p className="text-xs text-ink-faint">{hint}</p>}
      </div>
      <div className="space-y-1.5">{children}</div>
    </section>
  )
}

/** 面板里的空态文案 —— 比 `EmptyState` 轻, 因为它嵌在一屏已经有别的内容的页面里 */
export function PanelEmpty({ children }: { children: React.ReactNode }) {
  return <p className="rounded-lg bg-sunken px-3 py-2 text-xs text-ink-faint">{children}</p>
}
