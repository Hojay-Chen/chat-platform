/**
 * 未读角标。
 *
 * 三种形态, 对应三种不同的"要不要打扰我":
 * - 有未读 → 数字, 超过 99 收敛成 `99+`（三位数会把列表挤歪）
 * - 免打扰 → **只有小红点, 没有数字**。这正是"免打扰"的含义: 知道有事, 但不要数字催我
 * - 没未读 → 什么都不画（返回 null, 不是画一个空 span —— 空 span 仍占位, 会让行高抖动）
 */

export interface UnreadBadgeProps {
  count: number
  /** 免打扰 —— 只出点, 不出数字 */
  muted?: boolean
  className?: string
}

export function UnreadBadge({ count, muted = false, className = '' }: UnreadBadgeProps) {
  if (count <= 0) return null

  if (muted) {
    return (
      <span
        className={`inline-block h-2 w-2 rounded-full bg-danger ${className}`}
        title="有新消息(免打扰)"
      />
    )
  }

  return (
    <span
      className={`inline-grid min-w-[18px] place-items-center rounded-full bg-danger px-1.5 text-[11px] font-medium leading-[18px] text-white tnum ${className}`}
    >
      {count > 99 ? '99+' : count}
    </span>
  )
}
