import type { KeyboardEvent, ReactNode } from 'react'
import { Plus, Send } from 'lucide-react'

/**
 * 输入区。
 *
 * 一处刻意的行为修正: **请求在飞时不再锁住输入框**。
 *
 * 旧实现里 `inputLocked = streaming && !gathering` —— 对方还在回复时输入框是灰的。
 * 那是"AI 伴侣"的语汇（等她说完你再说）。IM 里这是错的: 真人聊天时对方打字
 * 完全不妨碍你继续发。`streaming` 这个状态仍然保留（面板要用它显示"她正在输入"）,
 * 只是不再驱动 `disabled`。
 */

export interface ComposerAction {
  key: string
  icon: ReactNode
  label: string
  onClick: () => void
}

export interface ComposerProps {
  value: string
  onChange: (v: string) => void
  onSend: () => void
  placeholder?: string
  disabled?: boolean
  /** 提供时才画左边的「+」 —— 没有可选动作的输入框不该有一个点了没反应的加号 */
  onOpenPanel?: () => void
  actions?: ComposerAction[]
}

export function Composer({
  value,
  onChange,
  onSend,
  placeholder = '发消息…',
  disabled = false,
  onOpenPanel,
  actions = [],
}: ComposerProps) {
  const canSend = !disabled && value.trim().length > 0

  // Enter 发送, Shift+Enter 换行 —— IM 的通用约定。
  // 这里用 input 而不是 textarea 是为了配合静态渲染测试; 多行输入二期换 textarea 时这条逻辑不变。
  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (canSend) onSend()
    }
  }

  return (
    <div className="border-t border-line bg-surface px-3 py-2 pb-safe">
      <div className="mx-auto flex max-w-3xl items-center gap-2">
        {onOpenPanel && (
          <button
            type="button"
            onClick={onOpenPanel}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
            title="更多"
          >
            <Plus size={20} />
          </button>
        )}

        {actions.map((a) => (
          <button
            key={a.key}
            type="button"
            onClick={a.onClick}
            title={a.label}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
          >
            {a.icon}
          </button>
        ))}

        <input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder={placeholder}
          disabled={disabled}
          className="input flex-1 rounded-xl py-2"
        />

        <button
          type="button"
          onClick={onSend}
          disabled={!canSend}
          className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-accent text-accent-ink transition-colors hover:bg-accent/90 disabled:opacity-30"
          title="发送"
        >
          <Send size={17} />
        </button>
      </div>
    </div>
  )
}
