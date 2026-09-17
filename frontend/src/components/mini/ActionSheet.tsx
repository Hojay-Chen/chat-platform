import type { ReactNode } from 'react'

/**
 * 从底部升起的面板 —— 小程序胶囊上 `···` 按下去之后出来的那张。
 *
 * <h2>为什么是底部而不是居中</h2>
 *
 * 同一个用途在 `SurfaceHost` 里已经有 `MODAL`(居中浮层, 有遮罩), 这里却没有复用它:
 * `···` 在微信里唤起的是**贴着底边**的一张单子, 而这张单子的内容(参与者、邀请链接、
 * 离开/结束)是"关于这一场会话的操作", 不是"你确定吗"那种需要打断的对话。
 * 贴底 = 拇指够得到, 且留着上面半屏看得见应用 —— 与 `PANEL` 不吃遮罩是同一个判断。
 *
 * 桌面端(≥640px)居中, 因为那时候没有"拇指够不够得到"这回事, 而一条横跨 1440px
 * 的底栏是难看的 —— `max-w-md` 同时管住了两种。
 *
 * <h2>点空白处收起</h2>
 *
 * 那层不是 `<div onClick>`, 是 `<button aria-label="收起">`。理由与 `Contacts.tsx`
 * 里 `AddMenu` 的遮罩完全一样: 键盘与读屏都能触发它。一个只能用鼠标关掉的浮层,
 * 对键盘用户就是一个陷阱。
 */
export default function ActionSheet({
  title,
  subtitle,
  onClose,
  children,
}: {
  title: string
  subtitle?: string
  onClose: () => void
  children: ReactNode
}) {
  return (
    <div
      data-testid="mini-sheet"
      className="fixed inset-0 z-50 flex items-end justify-center sm:items-center sm:p-4"
    >
      <button
        type="button"
        aria-label="收起"
        onClick={onClose}
        className="absolute inset-0 cursor-default bg-scrim/40"
      />
      <div className="animate-fadeUp relative flex max-h-[80vh] w-full max-w-md flex-col overflow-hidden rounded-t-2xl border border-line bg-raised shadow-pop sm:rounded-2xl">
        <header className="shrink-0 border-b border-line px-4 py-3">
          <div className="text-sm font-medium text-ink">{title}</div>
          {subtitle && <div className="mt-0.5 text-xs text-ink-faint">{subtitle}</div>}
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
      </div>
    </div>
  )
}

/** 面板里的一段 —— 标题 + 内容。分隔靠 `border-t`, 不靠间距堆叠。 */
export function SheetSection({
  label,
  action,
  children,
}: {
  label: string
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="border-b border-line py-2 last:border-b-0">
      <div className="flex items-center justify-between px-4 pb-1 pt-1">
        <span className="text-[11px] font-medium uppercase tracking-wide text-ink-faint">{label}</span>
        {action}
      </div>
      {children}
    </section>
  )
}
