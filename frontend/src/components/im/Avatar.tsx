import { avatarColor, groupCellCount, groupGridCols, initials } from '@/lib/avatar'

/**
 * 头像 —— 会话列表、通讯录、消息气泡、群成员共用同一个件。
 *
 * 形状是**圆角方块**而不是圆形: 微信/QQ 的头像是方的, 而圆形是"社交"语汇里
 * 偏西式的那一支。方角也让头像和列表行的对齐更干净。
 *
 * `kind='agent'` 加一个右下角标 —— 这是这个平台与微信唯一的分野,
 * 必须在列表里一眼看得出来, 而不是要点进资料页才知道。
 */

export type AvatarKind = 'user' | 'companion' | 'agent' | 'group' | 'system'

export interface AvatarProps {
  name: string
  src?: string | null
  /** 边长(px)。默认 40 —— 会话列表与通讯录都用这个尺寸 */
  size?: number
  kind?: AvatarKind
  online?: boolean
  className?: string
}

export function Avatar({
  name,
  src,
  size = 40,
  kind = 'user',
  online = false,
  className = '',
}: AvatarProps) {
  const dim = { width: `${size}px`, height: `${size}px` }
  const radius = size <= 28 ? '6px' : '10px'

  return (
    <span className={`relative inline-block shrink-0 ${className}`} style={dim}>
      {src ? (
        <img
          src={src}
          alt={name}
          className="h-full w-full object-cover"
          style={{ borderRadius: radius }}
        />
      ) : (
        <span
          className="grid h-full w-full place-items-center font-medium text-white select-none"
          style={{
            borderRadius: radius,
            background: avatarColor(name),
            fontSize: `${Math.round(size * 0.42)}px`,
          }}
        >
          {initials(name)}
        </span>
      )}

      {kind === 'agent' && (
        <span
          className="absolute -bottom-0.5 -right-0.5 grid place-items-center rounded-full bg-accent text-accent-ink ring-2 ring-surface"
          style={{ width: `${Math.max(12, size * 0.34)}px`, height: `${Math.max(12, size * 0.34)}px` }}
          title="仿真 Agent"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"
               style={{ width: '62%', height: '62%' }} aria-hidden="true">
            <rect x="4" y="8" width="16" height="12" rx="3" />
            <path d="M12 8V4M9 14h.01M15 14h.01" />
          </svg>
        </span>
      )}

      {online && (
        <span
          className="absolute -bottom-0.5 -right-0.5 rounded-full bg-ok ring-2 ring-surface"
          style={{ width: `${Math.max(8, size * 0.24)}px`, height: `${Math.max(8, size * 0.24)}px` }}
          title="在线"
        />
      )}
    </span>
  )
}

export interface GroupAvatarProps {
  members: { name: string; src?: string | null }[]
  size?: number
  className?: string
}

/**
 * 群头像九宫格。成员数决定格子数, 超出 9 个不画 —— 见 `lib/avatar.ts` 的理由。
 * 空群给一个单格兜底, 不是空白。
 */
export function GroupAvatar({ members, size = 40, className = '' }: GroupAvatarProps) {
  const count = groupCellCount(members.length)
  const cols = groupGridCols(Math.max(members.length, 1))
  const gap = count > 4 ? 1 : 1.5
  const cell = count <= 1 ? size : (size - gap * (cols - 1)) / cols

  if (count === 0) {
    return (
      <span className={`relative inline-block shrink-0 ${className}`}
            style={{ width: `${size}px`, height: `${size}px` }}>
        <span className="grid h-full w-full place-items-center text-white"
              style={{ borderRadius: '10px', background: '#94A3B8', fontSize: `${Math.round(size * 0.4)}px` }}>
          #
        </span>
      </span>
    )
  }

  return (
    <span
      className={`inline-grid shrink-0 overflow-hidden ${className}`}
      style={{
        width: `${size}px`,
        height: `${size}px`,
        borderRadius: '10px',
        gap: `${gap}px`,
        gridTemplateColumns: `repeat(${cols}, ${cell}px)`,
        gridAutoRows: `${cell}px`,
      }}
    >
      {members.slice(0, count).map((m, i) => (
        <span
          key={i}
          className="grid place-items-center font-medium text-white select-none"
          style={{
            background: avatarColor(m.name || String(i)),
            fontSize: `${Math.max(7, Math.round(cell * 0.5))}px`,
            borderRadius: count <= 1 ? '10px' : '2px',
          }}
        >
          {initials(m.name)}
        </span>
      ))}
    </span>
  )
}
