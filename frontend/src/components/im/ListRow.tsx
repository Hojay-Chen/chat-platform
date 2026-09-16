import type { ReactNode } from 'react'

/**
 * 列表行的五段式骨架 —— 会话行、好友行、群聊行、群成员行**共用这一个组件**。
 *
 * 这是"一期做了二期白捡"最直接的一处: 二期加好友与群聊时不新增行组件,
 * 只是换 `leading` 里放什么头像、`trailing` 里放什么。
 *
 * 一个刻意的取舍: **没有 `onClick` 时渲染 `<div>` 而不是 `<button disabled>`**。
 * 带 onClick 的行在真机上是可点的, 但 disabled 的按钮既点不动又会变灰、
 * 还会吃掉键盘焦点 —— 对纯展示的行（比如"共 12 人"这种分隔）那是错的语言。
 */

export interface ListRowProps {
  leading?: ReactNode
  title: ReactNode
  subtitle?: ReactNode
  trailing?: ReactNode
  /** 右上角的时间文本, 用等宽数字 —— 否则会话列表轮询刷新时整列会左右抖 */
  trailingText?: string
  /** 未读角标, 放在右下角 */
  badge?: ReactNode
  active?: boolean
  muted?: boolean
  className?: string
  onClick?: () => void
}

export function ListRow({
  leading,
  title,
  subtitle,
  trailing,
  trailingText,
  badge,
  active = false,
  muted = false,
  className = '',
  onClick,
}: ListRowProps) {
  const cls = [
    'row-base',
    active ? 'bg-accent-soft' : onClick ? 'hover:bg-sunken' : '',
    className,
  ].filter(Boolean).join(' ')

  const body = (
    <>
      {leading}
      <span className="min-w-0 flex-1">
        <span
          className={`block truncate text-[15px] leading-6 ${muted ? 'text-ink-soft' : 'text-ink'}`}
        >
          {title}
        </span>
        {subtitle !== undefined && subtitle !== null && (
          <span className="block truncate text-[13px] leading-5 text-ink-soft">{subtitle}</span>
        )}
      </span>
      {(trailingText || trailing || badge) && (
        <span className="flex shrink-0 flex-col items-end gap-1">
          {trailingText && (
            <span className="text-[11px] leading-4 text-ink-faint tnum">{trailingText}</span>
          )}
          {trailing ?? badge}
        </span>
      )}
    </>
  )

  // 见文件头: 无 onClick 就是纯展示行, 不该长成按钮的样子
  if (!onClick) {
    return <div className={cls}>{body}</div>
  }

  return (
    <button type="button" onClick={onClick} className={cls}>
      {body}
    </button>
  )
}

/** 分组标题 —— 通讯录的「A」「B」、聊天列表的「置顶」、资料页的「记忆」都用它 */
export function SectionHeader({ label, count, className = '' }: {
  label: string
  count?: number
  className?: string
}) {
  return (
    <div className={`bg-sunken px-4 py-1.5 text-xs font-medium text-ink-soft ${className}`}>
      {label}
      {count !== undefined && <span className="ml-1.5 text-ink-faint tnum">{count}</span>}
    </div>
  )
}
