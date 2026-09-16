import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ChevronLeft, MoreHorizontal, RotateCw } from 'lucide-react'
import type { Message } from '@/types'
import ApplicationCardBubble from '@/components/ApplicationCardBubble'
import { Avatar } from '@/components/im/Avatar'
import { Composer } from '@/components/im/Composer'
import { EmptyState } from '@/components/im/EmptyState'
import { MessageBubble, TimeSeparator, type BubbleSender } from '@/components/im/MessageBubble'
import { useChatRoom } from '@/hooks/useChatRoom'
import { groupMessages } from '@/lib/messageGroups'
import { userStatus } from '@/lib/messageState'
import { formatTime } from '@/lib/time'
import { ChatRoomPanel } from './ChatRoomPanel'

/**
 * 聊天室 —— 全屏, 没有 tab bar。
 *
 * <h2>这一屏与老 `Chat.tsx` 的关系</h2>
 *
 * 消息流的形状(日期分隔、左右气泡、应用卡片混排、连发聚合)全部照搬, 一条判定都没改。
 * 改掉的是三件事:
 *
 * 1. **会话由 URL 上的 `conversationId` 寻址**。老实现里它是组件 state, 于是刷新页面会
 *    跳到"第一个会话"而不是你在看的那个 —— 因为 URL 上只有伴侣 id, 而一个伴侣可以有
 *    多段对话。点进来、刷新、分享链接、按后退, 现在都落在同一段对话上。
 * 2. **不锁输入框**。老实现的 `inputLocked = streaming && !gathering` 意思是"等她说完
 *    你再说", 那是 AI 伴侣的语汇。真人聊天时对方打字完全不妨碍你继续发。
 *    连发聚合本身保留 —— 它是"一次请求至多一次回复"的前端一半, 删掉是行为回归。
 * 3. **左侧抽屉没有了**。7 个抽屉在 IA 上分头归位: 应用进了下面那个「+」面板,
 *    记忆/用户模型/关系/最近进了联系人资料页(第 6 步), 提醒与通知进了「我」。
 *
 * <h2>左侧怎么没有会话列表</h2>
 *
 * 微信的聊天室也是全屏的 —— 会话列表在返回之后。这里与它一致: 左上角返回回到
 * `/chat`。**返回用 `navigate('/chat')` 而不是 `navigate(-1)`**: 从通知或分享链接
 * 直接点进来时, 历史里上一条可能在一个完全无关的地方, 而"后退"在用户心里永远等于
 * "回到消息列表"。
 */
export default function ChatRoom() {
  const { conversationId } = useParams<{ conversationId: string }>()
  const navigate = useNavigate()
  const {
    conv, messages, loading, error, setError,
    typing, readMap, statusMap,
    send, markRead,
  } = useChatRoom(conversationId)

  const [draft, setDraft] = useState('')
  const [panelOpen, setPanelOpen] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)

  const rows = useMemo(() => groupMessages(messages), [messages])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' })
  }, [rows.length, typing])

  if (!conversationId) {
    // 路由保证它存在; 真走到这里说明有人把 `/chat/:conversationId` 之外的路径挂到了
    // 这个组件上 —— 直说, 别画一个空壳聊天室让用户以为对方不理他
    return <EmptyState title="没有指定会话" hint="请从消息列表里点一个会话进来。" />
  }

  if (loading && !conv) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-ink-faint">加载中…</div>
    )
  }

  if (!conv) {
    return (
      <div className="flex flex-1 flex-col">
        <RoomHeader title="会话" subtitle="" onBack={() => navigate('/chat')} />
        <EmptyState
          icon={<RotateCw size={26} />}
          title="没能打开这段对话"
          hint={error}
          action={
            <button type="button" className="btn-outline text-xs" onClick={() => navigate('/chat')}>
              回消息列表
            </button>
          }
        />
      </div>
    )
  }

  function submit() {
    const text = draft.trim()
    if (!text) return
    send(text)
    setDraft('')
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <RoomHeader
        title={conv.peer.name || conv.title}
        // 「正在输入」压过一切 —— 这是此刻唯一在变的事实
        subtitle={typing ? '正在输入…' : '仿真 Agent'}
        onBack={() => navigate('/chat')}
        onMore={() => navigate(`/contacts/agent/${conv.peer.id}`)}
      />

      <div className="min-h-0 flex-1 overflow-y-auto px-3 py-4 sm:px-6">
        {rows.length === 0 ? (
          <div className="grid h-full place-items-center">
            <p className="text-xs text-ink-faint">还没有消息。说点什么吧。</p>
          </div>
        ) : (
        /**
         * 消息贴着输入框往上长, 而不是从顶部往下堆 —— 这是 IM 的默认姿态:
         * 新的消息出现在**离拇指最近**的地方, 而一段刚开始的对话不该顶在屏幕最上方,
         * 下面留一大片空白。
         *
         * 用 `min-h-full` + `justify-end` 而不是把 `justify-end` 加在滚动容器上:
         * 后者在内容超过一屏时会把**最上面几条裁掉**, 而且滚不回去(flex 的经典坑)。
         */
        <div className="mx-auto flex min-h-full max-w-2xl flex-col justify-end gap-3">
          {rows.map((row) => {
            if (row.kind === 'separator') return <TimeSeparator key={row.key} label={row.label} />
            const m = row.message
            // §66: 应用卡片就是一条消息 —— 它和别的消息排在同一条时间线上, 只是换一种画法。
            // 认不出的 messageKind 落回 MessageBubble, 而平台保证那种消息的 content 是一句人能读的话。
            const isApplicationCard =
              m.messageKind === 'APPLICATION_CARD' || m.messageKind === 'APPLICATION_INVITATION'
            if (isApplicationCard) {
              return (
                <ApplicationCardBubble
                  key={m.id}
                  message={m}
                  // 这里是 `PeerRef → companionId` 的翻译点之一(§1.3 ③)。
                  // `ApplicationCardBubble` 一期不动(F11 钉着它的物理路径), 所以由调用方翻译。
                  companionId={conv.peer.id}
                  conversationId={conv.id}
                  onShared={() => void markRead()}
                />
              )
            }
            return (
              <MessageBubble
                key={m.id}
                sender={senderOf(m)}
                content={m.content}
                time={formatTime(m.createdAt)}
                avatar={
                  senderOf(m) === 'peer' ? (
                    <Avatar name={conv.peer.name || conv.title} kind="agent" size={28} />
                  ) : undefined
                }
                status={senderOf(m) === 'user' ? userStatus(m, readMap, statusMap) : undefined}
                // 失败的标红, 但**不给「重发」** —— 重发要拿到原批次重投, 而 `useBurstSend`
                // 这一期不暴露这个动作。`MessageBubble` 的约定是"没有 onRetry 就不画按钮",
                // 所以这里不传, 而不是传一个点了没反应的函数。
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
        <div className="mx-auto mb-2 w-full max-w-2xl px-3">
          <div className="flex items-center gap-2 rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
            <span className="min-w-0 flex-1">{error}</span>
            <button
              type="button"
              onClick={() => setError('')}
              className="shrink-0 text-xs underline"
            >
              知道了
            </button>
          </div>
        </div>
      )}

      {/* 「+」只在会话真的存在时才给 —— 一个点了没反应的加号比没有加号更糟 */}
      <Composer
        value={draft}
        onChange={setDraft}
        onSend={submit}
        placeholder={`发消息给${conv.peer.name || conv.title}…`}
        onOpenPanel={() => setPanelOpen(true)}
      />

      <ChatRoomPanel
        open={panelOpen}
        onClose={() => setPanelOpen(false)}
        peer={conv.peer}
        conversationId={conv.id}
        onOpened={() => void markRead()}
      />
    </div>
  )
}

/**
 * 一条消息画在左边还是右边。
 *
 * **这一句是这一期最该被注释的一行。** 一期里它是对的, 因为每段会话的对面都是一个
 * 数字人, 于是 `senderType === 'user'` 等价于"我发的"。二期群里不是了 —— 一条
 * `senderType='user'` 的消息可能是**群里另一个人**发的, 那时判断要换成
 * `m.senderId === myUserId`。改的只有这一个函数, `MessageBubble` 一个字都不用动
 * —— 它拿到的永远是已经算好的 `sender`, 这正是它 props 里不放 `senderType` 的原因。
 */
function senderOf(m: Message): BubbleSender {
  if (m.senderType === 'system') return 'system'
  return m.senderType === 'user' ? 'user' : 'peer'
}

function RoomHeader({
  title,
  subtitle,
  onBack,
  onMore,
}: {
  title: string
  subtitle: string
  onBack: () => void
  onMore?: () => void
}) {
  return (
    <header className="z-10 flex items-center gap-1 border-b border-line bg-surface/90 px-1.5 py-1.5 backdrop-blur">
      <button
        type="button"
        onClick={onBack}
        title="返回"
        className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
      >
        <ChevronLeft size={20} />
      </button>

      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium text-ink">{title}</div>
        {subtitle && <div className="truncate text-[11px] text-ink-faint">{subtitle}</div>}
      </div>

      {onMore && (
        <button
          type="button"
          onClick={onMore}
          title="资料页"
          className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
        >
          <MoreHorizontal size={20} />
        </button>
      )}
    </header>
  )
}
