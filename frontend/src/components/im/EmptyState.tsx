import type { ReactNode } from 'react'
import { Search, X } from 'lucide-react'

/**
 * 空态。
 *
 * 空列表是 IM 里出现频率最高的一屏（新账号、搜索无结果、群还没人说话）,
 * 值得一个统一的件而不是每处写一句 `<p className="text-center text-ink-faint">`。
 * `hint` 与 `action` 都是可选的 —— 缺失就不渲染, 不占位。
 */
export function EmptyState({ icon, title, hint, action }: {
  icon?: ReactNode
  title: string
  hint?: string
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-16 text-center">
      {icon && <div className="mb-1 text-ink-faint">{icon}</div>}
      <p className="text-sm text-ink-soft">{title}</p>
      {hint && <p className="max-w-xs text-xs leading-relaxed text-ink-faint">{hint}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
}

/** 搜索框 —— 会话列表与通讯录共用 */
export function SearchBar({ value, onChange, placeholder = '搜索', onClear }: {
  value: string
  onChange: (v: string) => void
  placeholder?: string
  onClear?: () => void
}) {
  return (
    <div className="px-3 py-2">
      <div className="flex items-center gap-2 rounded-lg bg-sunken px-3 py-1.5">
        <Search size={15} className="shrink-0 text-ink-faint" />
        <input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className="min-w-0 flex-1 bg-transparent text-sm text-ink placeholder:text-ink-faint outline-none"
        />
        {value && onClear && (
          <button
            type="button"
            onClick={onClear}
            className="grid h-4 w-4 shrink-0 place-items-center rounded-full bg-ink-faint/30 text-ink-soft"
            title="清除"
          >
            <X size={11} />
          </button>
        )}
      </div>
    </div>
  )
}
