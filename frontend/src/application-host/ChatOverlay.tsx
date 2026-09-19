import { useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, MessageSquare, Minus, X } from 'lucide-react'
import ApplicationCardBubble from '@/components/ApplicationCardBubble'
import { Skeleton } from '@/components/agent/PanelState'
import { Avatar } from '@/components/im/Avatar'
import { Composer } from '@/components/im/Composer'
import { MessageBubble, TimeSeparator, type BubbleSender } from '@/components/im/MessageBubble'
import { useChatRoom } from '@/hooks/useChatRoom'
import { groupMessages } from '@/lib/messageGroups'
import { userStatus } from '@/lib/messageState'
import { formatTime } from '@/lib/time'
import { useConversationStore } from '@/stores/conversations'
import type { Message } from '@/types'

/**
 * §7 的聊天浮窗 —— <b>应用在被使用的同时, 聊天不消失</b>。
 *
 * <h2>它为什么是一个浮窗, 而不是"打开聊天页"</h2>
 *
 * 用户在下一局棋, 中途想跟对手说句话。此时把他送到 `/chat/:id` 意味着**离开那一局** ——
 * 而棋局是在第三方应用里的, 它的状态不归平台管, 回来时它可能已经不记得自己下到哪儿了。
 * 浮窗让两件事同时成立: 应用还在原地, 聊天浮在上面。
 *
 * <h2>两档, 不是三档</h2>
 *
 * {@code MINIMIZED} 是一个贴着右下角的小条({@code EXPANDED} 是一块 360×520 的窗)。
 * 没有第三档"半屏": 浮窗的意义是**不占地方**, 而一个能占半屏的浮窗退化成了一块需要
 * 用户去摆放的面板 —— 那时候它不如直接进聊天页。
 *
 * <h2>默认哪一档</h2>
 *
 * 带 {@code conversationId} 打开就是 {@code EXPANDED}: 用户(或应用)点名要看某一段对话,
 * 那他就是要看。不带 conversationId 时是 {@code MINIMIZED} —— 应用只是想把"聊天"这个
 * 入口摆出来, 而一块盖住半个棋盘的会话列表不是他要的东西。
 *
 * <h2>它复用的是聊天室那一套, 不是另一套</h2>
 *
 * `useChatRoom` / `MessageBubble` / `Composer` / `groupMessages` —— 与全屏聊天室<b>同一个</b>
 * hook 与同一批组件。写第二套"简化的"消息流是那种一开始省事、后来每加一个消息类型都要
 * 改两遍的做法, 而两边不一致的表现是"浮窗里看不到某类消息"。
 */
export type OverlayMode = 'MINIMIZED' | 'EXPANDED'

export interface ChatOverlayProps {
  /** 要打开的会话。为空 = 还没选, 先给一份会话列表让用户挑。 */
  conversationId: string | null
  mode: OverlayMode
  onModeChange: (mode: OverlayMode) => void
  /** 关掉浮窗(不是最小化 —— 最小化走 onModeChange)。 */
  onClose: () => void
  /** 用户在列表里挑了一段对话。 */
  onPick: (conversationId: string) => void
}

export default function ChatOverlay({
  conversationId,
  mode,
  onModeChange,
  onClose,
  onPick,
}: ChatOverlayProps) {
  if (mode === 'MINIMIZED') {
    return <MinimizedBar conversationId={conversationId} onExpand={() => onModeChange('EXPANDED')} onClose={onClose} />
  }
  return (
    <ExpandedWindow
      conversationId={conversationId}
      onMinimize={() => onModeChange('MINIMIZED')}
      onClose={onClose}
      onPick={onPick}
    />
  )
}

/**
 * 最小态: 一条贴着右下角的小条。
 *
 * 它显示的是**会话名与未读数**, 而不是一个光秃秃的图标: 用户把它缩起来是为了不挡着棋局,
 * 不是为了忘掉对面刚发来的那条消息。
 */
function MinimizedBar({
  conversationId,
  onExpand,
  onClose,
}: {
  conversationId: string | null
  onExpand: () => void
  onClose: () => void
}) {
  const conv = useConversationStore((s) => s.list.find((c) => c.id === conversationId) ?? null)
  const unread = conv?.unreadCount ?? 0

  return (
    <div
      className="fixed bottom-4 right-4 z-[60] flex items-center gap-2 rounded-full border border-line bg-raised py-1.5 pl-3 pr-1.5 shadow-pop"
      data-testid="chat-overlay-minimized"
    >
      <button
        type="button"
        onClick={onExpand}
        title="展开聊天"
        className="flex items-center gap-2 rounded-full text-sm text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
      >
        <MessageSquare size={15} className="text-accent" />
        <span className="max-w-[10rem] truncate">
          {conv ? conv.peer.name || conv.title : '聊天'}
        </span>
        {unread > 0 && (
          <span className="rounded-full bg-danger px-1.5 text-[10px] leading-4 text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>
      <button
        type="button"
        onClick={onClose}
        title="关掉聊天浮窗"
        aria-label="关掉聊天浮窗"
        className="rounded-full p-1 text-ink-faint transition hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
        data-testid="chat-overlay-close"
      >
        <X size={13} />
      </button>
    </div>
  )
}

/**
 * 展开态: 一块 360×520 的窗。
 *
 * 高度用 `min(520px, 100vh - 4rem)` 而不是一个固定值 —— 移动端浏览器地址栏会吃掉一部分
 * 视口, 而一个 520px 的窗在那些设备上会把输入框顶到屏幕外面去。
 */
function ExpandedWindow({
  conversationId,
  onMinimize,
  onClose,
  onPick,
}: {
  conversationId: string | null
  onMinimize: () => void
  onClose: () => void
  onPick: (conversationId: string) => void
}) {
  return (
    <div
      className="fixed bottom-4 right-4 z-[60] flex h-[min(520px,calc(100vh-2rem))] w-[min(360px,calc(100vw-2rem))] flex-col overflow-hidden rounded-2xl border border-line bg-surface shadow-pop"
      data-testid="chat-overlay"
      data-mode="EXPANDED"
    >
      {conversationId ? (
        <Room conversationId={conversationId} onMinimize={onMinimize} onClose={onClose} />
      ) : (
        <Picker onMinimize={onMinimize} onClose={onClose} onPick={onPick} />
      )}
    </div>
  )
}

function Picker({
  onMinimize,
  onClose,
  onPick,
}: {
  onMinimize: () => void
  onClose: () => void
  onPick: (conversationId: string) => void
}) {
  const list = useConversationStore((s) => s.list)
  const load = useConversationStore((s) => s.load)

  useEffect(() => {
    // 浮窗可能在任何一屏被叫出来, 而那一屏不一定加载过会话列表。
    if (list.length === 0) void load()
    // 只在挂载时看一眼 —— 之后由 store 自己保证新鲜(它本来就在轮询)。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <>
      <OverlayHeader title="和谁聊?" onMinimize={onMinimize} onClose={onClose} />
      <div className="min-h-0 flex-1 overflow-y-auto">
        {list.length === 0 ? (
          <p className="p-6 text-center text-xs text-ink-faint">还没有可以聊的人。</p>
        ) : (
          list.slice(0, 30).map((c) => (
            <button
              key={c.id}
              type="button"
              onClick={() => onPick(c.id)}
              className="flex w-full items-center gap-3 px-3 py-2 text-left transition hover:bg-sunken/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-accent/40"
            >
              <Avatar name={c.peer.name || c.title} kind="agent" size={30} />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm text-ink">{c.peer.name || c.title}</span>
                <span className="block truncate text-[11px] text-ink-faint">
                  {c.lastMessage?.content ?? ''}
                </span>
              </span>
              {c.unreadCount > 0 && (
                <span className="shrink-0 rounded-full bg-danger px-1.5 text-[10px] leading-4 text-white">
                  {c.unreadCount > 99 ? '99+' : c.unreadCount}
                </span>
              )}
            </button>
          ))
        )}
      </div>
    </>
  )
}

/**
 * 一段对话的浮窗形态。
 *
 * 它和全屏聊天室共用 `useChatRoom` —— 包括已读回执。这一点是刻意的: 用户**看着**浮窗里
 * 的那些消息, 角标就该灭, 与他在哪一屏看无关。
 */
function Room({
  conversationId,
  onMinimize,
  onClose,
}: {
  conversationId: string
  onMinimize: () => void
  onClose: () => void
}) {
  const { conv, messages, loading, error, setError, typing, readMap, statusMap, send } =
    useChatRoom(conversationId)
  const [draft, setDraft] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  const rows = useMemo(() => groupMessages(messages), [messages])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' })
  }, [rows.length, typing])

  const title = conv ? conv.peer.name || conv.title : '聊天'

  return (
    <>
      <OverlayHeader title={title} subtitle={typing ? '正在输入…' : undefined} onMinimize={onMinimize} onClose={onClose} />

      <div className="min-h-0 flex-1 overflow-y-auto px-2.5 py-3">
        {loading && !conv ? (
          /*
            骨架而不是「加载中…」—— 这里出来的是**一列气泡**, 所以骨架就摆成气泡:
            左一条、右一条、左一条。一句话给不出任何形状, 数据到位时整块换掉。
          */
          <div className="space-y-3" aria-busy aria-label="正在读这一段对话">
            <Skeleton className="h-9 w-2/3 rounded-2xl" />
            <Skeleton className="ml-auto h-9 w-1/2 rounded-2xl" />
            <Skeleton className="h-9 w-3/5 rounded-2xl" />
          </div>
        ) : rows.length === 0 ? (
          <p className="p-4 text-center text-xs text-ink-faint">还没有消息。说点什么吧。</p>
        ) : (
          <div className="flex min-h-full flex-col justify-end gap-2.5">
            {rows.map((row) => {
              if (row.kind === 'separator') return <TimeSeparator key={row.key} label={row.label} />
              const m = row.message
              const isCard =
                m.messageKind === 'APPLICATION_CARD' || m.messageKind === 'APPLICATION_INVITATION'
              // 应用卡片在浮窗里也照画 —— 用户可能正是在浮窗里点进某一局的,
              // 而"浮窗里看不到卡片"会让那条邀请变成一句莫名其妙的话。
              if (isCard && conv) {
                return (
                  <ApplicationCardBubble
                    key={m.id}
                    message={m}
                    companionId={conv.peer.id}
                    conversationId={conv.id}
                  />
                )
              }
              const sender = senderOf(m)
              return (
                <MessageBubble
                  key={m.id}
                  sender={sender}
                  content={m.content}
                  time={formatTime(m.createdAt)}
                  avatar={
                    sender === 'peer' ? (
                      <Avatar name={conv?.peer.name || conv?.title || ''} kind="agent" size={24} />
                    ) : undefined
                  }
                  status={sender === 'user' ? userStatus(m, readMap, statusMap) : undefined}
                  failed={m.deliveryStatus === 'FAILED'}
                />
              )
            })}
            {typing && <MessageBubble sender="peer" content="" streaming />}
            <div ref={bottomRef} />
          </div>
        )}
      </div>

      {error && (
        <div className="mx-2.5 mb-1.5 flex items-center gap-2 rounded-lg border border-danger/30 bg-danger/10 px-2.5 py-1.5 text-[11px] text-danger">
          <span className="min-w-0 flex-1">{error}</span>
          <button
            type="button"
            onClick={() => setError('')}
            className="shrink-0 rounded underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
          >
            知道了
          </button>
        </div>
      )}

      {/* 浮窗里没有「+」—— 那块面板是全屏聊天室的东西(开应用、看邀请),
          而一个 360px 宽的窗装不下它。浮窗要回答的只有"说句话"。 */}
      <Composer
        value={draft}
        onChange={setDraft}
        onSend={() => {
          const text = draft.trim()
          if (!text) return
          setDraft('')
          void send(text)
        }}
        placeholder={`发消息给${title}…`}
      />
    </>
  )
}

function OverlayHeader({
  title,
  subtitle,
  onMinimize,
  onClose,
}: {
  title: string
  subtitle?: string
  onMinimize: () => void
  onClose: () => void
}) {
  return (
    <header className="flex shrink-0 items-center gap-1 border-b border-line bg-surface/95 px-3 py-2 backdrop-blur">
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm text-ink">{title}</div>
        {subtitle && <div className="truncate text-[10px] text-accent">{subtitle}</div>}
      </div>
      <button
        type="button"
        onClick={onMinimize}
        title="缩起来"
        aria-label="缩起来"
        className="rounded p-1 text-ink-faint transition hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
        data-testid="chat-overlay-minimize"
      >
        <Minus size={15} />
      </button>
      <button
        type="button"
        onClick={onClose}
        title="关掉聊天浮窗"
        aria-label="关掉聊天浮窗"
        className="rounded p-1 text-ink-faint transition hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
        data-testid="chat-overlay-close"
      >
        <ChevronDown size={16} />
      </button>
    </header>
  )
}

/**
 * 与全屏聊天室里的 `senderOf` **同一句话**。
 *
 * 一期每段会话的对面都是一个数字人, 所以 `senderType === 'user'` 等价于"我发的"。
 * 二期群里不是了 —— 那时判据要换成 `m.senderId === myUserId`, 而改的仍然只有这一个函数。
 * 两处各写一份的话, 那一改就会漏掉浮窗, 而漏掉的表现是"群里别人的消息画在右边"。
 */
function senderOf(m: Message): BubbleSender {
  if (m.senderType === 'system') return 'system'
  return m.senderType === 'user' ? 'user' : 'peer'
}
