import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Check, Copy, Boxes, Ticket } from 'lucide-react'
import { cardOf, chatApplications, invitationOf } from '@/api/chatApplications'
import type { Message } from '@/types'

/**
 * LAP v2 §66 —— 应用卡片<b>就是一条消息</b>, 这是它该长成的样子。
 *
 * 它没有自己的表、自己的分页、自己的已读状态: 它就是 `messages` 里的一行, 靠 `messageKind`
 * 在这里被认出来, 然后换一种画法。认不出来的客户端(旧版本、第三方客户端)会退回把 `content`
 * 当普通文本显示 —— 这也是为什么平台在落卡片消息时, 必须同时写一句人能读的话。
 *
 * <h2>两张卡片, 两种动作</h2>
 * <pre>
 *   APPLICATION_CARD          "井字棋 已在这段对话里开启"  → 进去 / 分享
 *   APPLICATION_INVITATION    "邀请你加入 井字棋"          → 复制链接
 * </pre>
 *
 * 卡片按钮的判据来自服务端(§4.1 的 `allowsNewSession`), 这里不预判任何东西 ——
 * 前端复制一份可用性判断, 迟早会有一次两边不一致, 而不一致的那一次一定发生在
 * 用户按下按钮的时候。
 */
export default function ApplicationCardBubble({
  message,
  companionId,
  conversationId,
  onShared,
}: {
  message: Message
  /** 分享要落到某段对话里 —— 这两个 id 只有聊天页面有, 所以从上面传下来。 */
  companionId: string
  conversationId: string
  /** 分享成功后重拉消息列表: 平台已经替我们把那条邀请消息落库了。 */
  onShared?: () => void
}) {
  if (message.messageKind === 'APPLICATION_CARD') {
    return (
      <CardBubble
        message={message}
        companionId={companionId}
        conversationId={conversationId}
        onShared={onShared}
      />
    )
  }
  if (message.messageKind === 'APPLICATION_INVITATION') return <InvitationBubble message={message} />
  return null
}

/** 平台通告都是从系统这一侧发出来的, 所以两张卡片都不靠左右对齐区分, 而是居中。 */
function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex animate-fadeUp justify-center py-1">
      <div className="w-full max-w-[86%] rounded-xl border border-line bg-raised px-4 py-3 text-sm">
        {children}
      </div>
    </div>
  )
}

function CardBubble({
  message,
  companionId,
  conversationId,
  onShared,
}: {
  message: Message
  companionId: string
  conversationId: string
  onShared?: () => void
}) {
  const navigate = useNavigate()
  const card = cardOf(message.metadata)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // metadata 缺失(旧消息 / 手工插的行)时退回纯文本 —— 卡片不是一个必须成立的前提。
  if (!card.sessionId) {
    return <Shell>{message.content}</Shell>
  }
  const sessionId = card.sessionId

  /**
   * 分享 = <b>把邀请链接发进这段对话</b>, 而不是把链接塞进剪贴板。
   *
   * 差别不是口味问题: 铸出来的票只出现一次(库里只有哈希), 而落成一条消息之后它就再也
   * 不会丢了 —— 换台设备、刷新页面, 那条链接还在对话里, 还能再点一次。走剪贴板的话,
   * 用户没粘贴就是真的没了。
   */
  const share = async () => {
    setBusy(true)
    setError(null)
    try {
      await chatApplications.share(companionId, conversationId, sessionId, { role: 'MEMBER' })
      onShared?.()
    } catch (e) {
      setError(e instanceof Error ? e.message : '分享失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Shell>
      <div className="flex items-start gap-3">
        <span className="mt-0.5 rounded-xl bg-accent-soft p-2 text-accent">
          <Boxes size={16} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium text-ink">{card.name ?? card.applicationId}</span>
            {card.role === 'OWNER' && (
              <span className="shrink-0 rounded-full bg-accent-soft px-2 py-0.5 text-[10px] text-accent">
                我开的
              </span>
            )}
          </div>
          <p className="mt-0.5 line-clamp-2 text-xs leading-relaxed text-ink-faint">
            {card.description ?? message.content}
          </p>
          <div className="mt-2 flex items-center gap-2">
            <button
              onClick={() => navigate(`/sessions/${sessionId}`)}
              className="btn-primary !px-3 !py-1 text-xs"
            >
              进入
            </button>
            <button
              onClick={share}
              disabled={busy}
              className="flex items-center gap-1 rounded-lg border border-line px-3 py-1 text-xs text-ink-soft transition hover:text-accent disabled:opacity-50"
            >
              <Ticket size={12} />
              {busy ? '分享中…' : '分享到对话'}
            </button>
          </div>
          {error && <p className="mt-1.5 text-[11px] text-danger">{error}</p>}
        </div>
      </div>
    </Shell>
  )
}

function InvitationBubble({ message }: { message: Message }) {
  const invite = invitationOf(message.metadata)
  const [copied, setCopied] = useState(false)

  if (!invite.joinUrl) return <Shell>{message.content}</Shell>
  // 平台给的是相对路径(`/join/{token}`), 而 <a href> 用相对路径是对的: 浏览器自己会按当前
  // origin 解析, 右键"复制链接地址"拿到的也是完整 URL。渲染阶段因此完全不碰 `window` ——
  // 这不是洁癖: 一个在渲染时读 `window` 的组件没法在服务端渲染, 而它本来就不需要读。
  const link = invite.joinUrl

  const copy = async () => {
    // 只有"放进剪贴板"这一步需要绝对地址 —— 收链接的人不在这个页面上。
    const absolute = `${window.location.origin}${link}`
    try {
      await navigator.clipboard.writeText(absolute)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      window.prompt('复制这个链接', absolute)
    }
  }

  return (
    <Shell>
      <div className="flex items-start gap-3">
        <span className="mt-0.5 rounded-xl bg-accent-soft p-2 text-accent">
          <Ticket size={16} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="font-medium text-ink">邀请链接</div>
          <p className="mt-0.5 text-xs leading-relaxed text-ink-faint">{message.content}</p>
          <div className="mt-2 flex items-center gap-2">
            <button
              onClick={copy}
              className="flex items-center gap-1 rounded-lg bg-accent px-3 py-1 text-xs text-accent-ink transition"
            >
              {copied ? <Check size={12} /> : <Copy size={12} />}
              {copied ? '已复制' : '复制链接'}
            </button>
            <a
              href={link}
              className="rounded-lg border border-line px-3 py-1 text-xs text-ink-soft transition hover:text-accent"
            >
              打开看看
            </a>
          </div>
        </div>
      </div>
    </Shell>
  )
}
