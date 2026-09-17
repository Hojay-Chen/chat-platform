import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Check, Copy, Boxes, Ticket } from 'lucide-react'
import { cardOf, chatApplications, invitationOf } from '@/api/chatApplications'
// 封面由平台按名字画 —— 与分享单子用的是同一个函数, 所以同一个人在两个地方看到的
// 颜色与首字一致。它不是"随便挑一个颜色": 名字一样结果就一样。
import { coverOf } from '@/application-host/share'
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

/**
 * 邀请消息 —— **一张卡片, 不是一段话**。
 *
 * <h2>它比以前多了什么, 以及为什么</h2>
 *
 * 以前它只显示 `content`(那句"点这里加入…" 加一条链接)。那句话是平台生成的, 它必须能
 * 独立成立 —— 认不出 `APPLICATION_INVITATION` 的旧客户端会把它当普通文本显示, 而那时
 * 它就是这条消息的全部。但**在那之外**, 一张给收件人看的卡片还该回答三件事:
 *
 * <pre>
 *   是什么应用     name + description + 一张封面(平台按应用名画, 见 coverOf)
 *   谁邀请我       分享的人写的那句附言(metadata.note)
 *   点了去哪       joinUrl
 * </pre>
 *
 * 三件都拿不到时(应用下架了、服务端那次额外查询失败、老消息)**整块退回从前的样子** ——
 * 一张只有链接的卡片仍然是能用的卡片, 而"编一个名字出来"是让它变成错的卡片。
 *
 * <h2>封面为什么是画出来的</h2>
 *
 * 收件人这一侧的封面由平台按应用名生成, 不是从应用拿一个图片地址。理由在
 * `application-host/share.ts` 的 `normalizeShare` 里: 一个外部图片地址进了收件人的消息流,
 * 收件人的 IP、打开时间、看没看这条就全部回报给了那个应用作者 —— 而他与收件人之间没有
 * 任何关系。首字 + 一个由名字定下来的颜色对任何应用都成立, 且不需要信任任何人。
 */
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

  // 拿不到应用名 → 从前的样子。这一条不是兜底, 而是**老消息的常态**: 服务端是后加的
  // 那三个字段, 此前铸出的邀请里一个都没有。
  if (!invite.name) {
    return (
      <Shell>
        <div className="flex items-start gap-3">
          <span className="mt-0.5 rounded-xl bg-accent-soft p-2 text-accent">
            <Ticket size={16} />
          </span>
          <div className="min-w-0 flex-1">
            <div className="font-medium text-ink">邀请链接</div>
            <p className="mt-0.5 text-xs leading-relaxed text-ink-faint">{message.content}</p>
            <InvitationActions copied={copied} link={link} onCopy={copy} />
          </div>
        </div>
      </Shell>
    )
  }

  const cover = coverOf(invite.name)

  return (
    <Shell>
      <div className="flex items-start gap-3">
        <span
          className="mt-0.5 grid h-11 w-11 shrink-0 place-items-center rounded-xl text-lg text-white"
          style={{ backgroundColor: `hsl(${cover.hue} 55% 45%)` }}
          aria-hidden
          data-testid="invitation-cover"
        >
          {cover.initial}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium text-ink">{invite.name}</span>
            <span className="shrink-0 rounded-full bg-accent-soft px-2 py-0.5 text-[10px] text-accent">
              邀请
            </span>
          </div>
          {invite.description && (
            <p className="mt-0.5 line-clamp-2 text-xs leading-relaxed text-ink-faint">
              {invite.description}
            </p>
          )}
          {/*
            附言是一个人写的话, 所以它有自己的样子(左边一条竖线), 而不是和应用的描述
            混成一段 —— 收件人要能一眼看出哪句是**人**说的。
          */}
          {invite.note && (
            <p className="mt-1.5 border-l-2 border-accent/40 pl-2 text-xs leading-relaxed text-ink-soft">
              {invite.note}
            </p>
          )}
          <InvitationActions copied={copied} link={link} onCopy={copy} />
        </div>
      </div>
    </Shell>
  )
}

function InvitationActions({
  copied,
  link,
  onCopy,
}: {
  copied: boolean
  link: string
  onCopy: () => void
}) {
  return (
    <div className="mt-2 flex items-center gap-2">
      <button
        onClick={onCopy}
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
  )
}
