import type { ReactNode } from 'react'

/**
 * 消息气泡。
 *
 * **左右由 `sender` 决定, 但"这是不是我发的"由调用方判断** —— 一期只有单聊,
 * 所以 `sender='user'` 就是自己; 二期群聊里"别人发的"要和"我发的"分左右,
 * 而那时 `senderType` 已经不足以判断（一条 `senderType='user'` 的消息可能是群里
 * 另一个人发的）。届时调用方算好 `sender` 再传进来, **这个组件零改动**。
 *
 * 三种角色: `user` 右对齐实色气泡 / `peer` 左对齐淡底气泡 / `system` 居中胶囊。
 */

export type BubbleSender = 'user' | 'peer' | 'system'

export interface MessageBubbleProps {
  sender: BubbleSender
  content: string
  /** 群聊里显示发送者名字 —— 一期不传, 二期群聊传。Props 先留着, 免得到时候改签名 */
  senderName?: string
  avatar?: ReactNode
  time?: string
  /** 自己消息的状态文本（已读 / 发送中 / 发送失败…） */
  status?: string
  /** 对方正在输入 —— 画三点而不是空气泡 */
  streaming?: boolean
  failed?: boolean
  onRetry?: () => void
}

export function MessageBubble({
  sender,
  content,
  senderName,
  avatar,
  time,
  status,
  streaming = false,
  failed = false,
  onRetry,
}: MessageBubbleProps) {
  if (sender === 'system') {
    return (
      <div className="my-1 flex justify-center">
        <span className="max-w-[80%] rounded-full bg-sunken px-3 py-1 text-center text-[11px] leading-5 text-ink-soft">
          {content}
        </span>
      </div>
    )
  }

  const isUser = sender === 'user'

  return (
    <div className={`flex items-start gap-2 ${isUser ? 'flex-row-reverse' : 'flex-row'}`}>
      {avatar}
      <div className={`flex min-w-0 max-w-[min(78%,30rem)] flex-col ${isUser ? 'items-end' : 'items-start'}`}>
        {senderName && (
          <span className="mb-0.5 px-1 text-[11px] text-ink-faint">{senderName}</span>
        )}

        {streaming && !content ? (
          <span className="flex items-center gap-1 rounded-xl bg-bubble-in px-3.5 py-3">
            <span className="typing-dot" />
            <span className="typing-dot" style={{ animationDelay: '.15s' }} />
            <span className="typing-dot" style={{ animationDelay: '.3s' }} />
          </span>
        ) : (
          <span
            className={`whitespace-pre-wrap break-words rounded-xl px-3.5 py-2 text-[15px] leading-[1.5] ${
              isUser
                ? 'bg-bubble-out text-bubble-out-ink'
                : 'bg-bubble-in text-ink'
            }`}
          >
            {content}
          </span>
        )}

        {(time || status || failed) && (
          <span className="mt-0.5 flex items-center gap-1.5 px-1 text-[11px] leading-4 text-ink-faint tnum">
            {time}
            {status}
            {/* 没有 onRetry 就不画重试按钮 —— 点了没反应的按钮比没有按钮更糟 */}
            {failed && onRetry && (
              <button type="button" onClick={onRetry} className="text-danger hover:underline">
                重发
              </button>
            )}
          </span>
        )}
      </div>
    </div>
  )
}

/** 消息流里的日期分隔条 */
export function TimeSeparator({ label }: { label: string }) {
  return (
    <div className="my-1 flex items-center justify-center">
      <span className="text-[11px] text-ink-faint">{label}</span>
    </div>
  )
}
