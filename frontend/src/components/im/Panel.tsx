import type { ReactNode } from 'react'
import { X } from 'lucide-react'

/**
 * 聊天室「+」那一类面板的外框 —— 窄屏从底部升起, 宽屏从右侧滑出。
 *
 * <h2>为什么不复用 `Drawer.tsx`</h2>
 *
 * `Drawer` 是个通用右侧抽屉: 任何宽度下都从右边滑出、都带遮罩。而 IM 里这个面板的形态
 * 是随屏幕变的 —— 手机上从底部升起(拇指够得着, 微信就是这样的), 桌面上从右侧滑出。
 * 那不是同一个组件的两套 class 能表达的, 因为**遮罩的有无也跟着变**:
 *
 * - 窄屏底部升起时背后是聊天记录, 要压暗它, 否则两层的边界看不出来
 * - 宽屏右侧滑出时不压暗 —— 用户很可能还在读背后的消息, 而全屏遮罩会把它变成
 *   一个"必须先关掉才能看"的东西。这一条与 `SurfaceHost` 的 PANEL 是同一条理由
 *
 * 所以这里是一个新件, 而不是给 `Drawer` 加参数。`Drawer` 在 7 个抽屉全部归位之后删除。
 *
 * <h2>不吃 Esc</h2>
 *
 * 与 `SurfaceHost` 的 PANEL 一致: 用户可能正看着背后的页面。Esc 留给"关掉整个浮层"
 * 那一类(MODAL), 不留给抽屉。
 */
export function Panel({ open, title, onClose, children }: {
  open: boolean
  title?: string
  onClose: () => void
  children: ReactNode
}) {
  if (!open) return null

  return (
    <>
      {/* 遮罩只在窄屏存在 —— 见文件头。宽屏没有它, 于是也没有"点外面关闭" */}
      <button
        type="button"
        aria-label="收起"
        onClick={onClose}
        className="fixed inset-0 z-40 bg-scrim/40 md:hidden"
      />

      <aside
        className="fixed inset-x-0 bottom-0 z-50 flex max-h-[75dvh] flex-col border-t border-line
                   bg-raised shadow-pop rounded-t-xl
                   md:inset-y-0 md:left-auto md:right-0 md:max-h-none md:w-full md:max-w-md md:rounded-none
                   md:border-l md:border-t-0"
      >
        <header className="flex items-center gap-2 border-b border-line px-4 py-3">
          <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">{title}</span>
          <button
            type="button"
            onClick={onClose}
            title="收起"
            className="grid h-7 w-7 shrink-0 place-items-center rounded-lg text-ink-faint transition-colors hover:bg-sunken hover:text-ink"
          >
            <X size={16} />
          </button>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto p-4 pb-safe">{children}</div>
      </aside>
    </>
  )
}
