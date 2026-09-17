import { useEffect, useMemo, useState } from 'react'
import { Search, Send } from 'lucide-react'
import ActionSheet from '@/components/mini/ActionSheet'
import { Avatar } from '@/components/im/Avatar'
import { chatApplications } from '@/api/chatApplications'
import { useConversationStore } from '@/stores/conversations'
import {
  MAX_NOTE,
  coverOf,
  describeShareFailure,
  filterRecipients,
  normalizeNote,
  noteRemaining,
  type NormalizedShare,
} from './share'

/**
 * §10 的分享面板 —— 「分享到…」那张单子。
 *
 * <h2>为什么分享要经过一个面板, 而不是应用直接发出去</h2>
 *
 * 因为**发消息的是用户, 不是应用**。应用能表达的只有"我想让用户分享这一场", 而"发给谁、
 * 附一句什么话"是用户在说话。这条界线一旦放倒, 一个第三方应用就能往用户的任何一个好友
 * 那里发消息 —— 那是这个平台上最严重的一件事, 没有之一。
 *
 * 所以整条链的形状是: 应用要分享 → 宿主开这张单子 → **用户**选人、写附言、按下发送 →
 * 宿主用用户自己的身份调聊天接口。应用全程碰不到收件人是谁。
 *
 * <h2>为什么不需要一个新的后端端点</h2>
 *
 * 分享端点是 `POST /api/companions/{c}/conversations/{v}/applications/{sessionId}/share`,
 * 而它落的是一条消息到 `{v}` 里。`c` 与 `v` 由**收件人那一行**给出(`ConversationSummary`
 * 的 `peer.id` 与 `id`)—— 于是"把这一场分享给任何一个人"就是用现成的端点换一段路径。
 * 加一个"给任意人发邀请"的新端点会立刻带来一个没有答案的问题: 它凭什么信任调用方说的
 * 收件人。
 *
 * <h2>它自己不做任何判断</h2>
 *
 * 候选人怎么筛、附言怎么裁、失败怎么说 —— 全在 `share.ts` 里, 那边有测试。这个文件只负责
 * 摆出来。本仓前端测试跑在 node 上(没有 jsdom), 组件渲染不起来, 所以写在 JSX 里的判断
 * 一条都保护不了。
 */
export interface ShareSheetProps {
  /** 这次分享的上下文 —— 应用名与描述从这儿来。 */
  share: NormalizedShare
  /** 要分享出去的那一场。为空 = 用发起分享的那个应用当前的会话。 */
  sessionId: string
  /** 发送成功。`conversationId` 是消息落到的那段对话 —— 宿主据此提示用户"发出去了"。 */
  onSent: (conversationId: string) => void
  /** 用户关掉了面板(点遮罩、点取消、按 Esc)。**取消是正常结果, 不是错误。** */
  onCancel: () => void
}

export default function ShareSheet({ share, sessionId, onSent, onCancel }: ShareSheetProps) {
  const conversations = useConversationStore((s) => s.list)
  const loadConversations = useConversationStore((s) => s.load)
  const [query, setQuery] = useState('')
  const [note, setNote] = useState('')
  const [target, setTarget] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const candidates = useMemo(
    () => filterRecipients(conversations, query),
    [conversations, query],
  )
  const selected = candidates.find((c) => c.id === target) ?? null

  /**
   * 名单来自会话列表, 而这张单子**可能在一个从没加载过它的页面上被叫出来**。
   *
   * 分享是从应用里发起的, 而用户进应用的路径有很多条: 从聊天列表点进来(那时列表已经
   * 在了), 或者从一条分享链接直接落到 `/sessions/...`(那时它**不在**)。少了这一句,
   * 后一条路径上的单子会永远显示"还没有可以分享的人" —— 而那句话说的是假的, 用户有
   * 一整个通讯录。
   *
   * `load()` 自己是幂等的(见 store), 所以"已经加载过"不用在这里判断, 只要别重复拉:
   * 空的时候拉一次就够。
   */
  useEffect(() => {
    if (conversations.length === 0) void loadConversations()
  }, [conversations.length, loadConversations])

  // 附言在**输入时**就裁一遍, 好让计数器与真正发出去的东西一致。服务端还会再裁一次 ——
  // 那一次才是权威, 因为调这个端点的不止这一张单子。
  const cleanNote = normalizeNote(note)
  const remaining = noteRemaining(cleanNote)

  const title = share.title || '来玩吗?'
  const cover = coverOf(share.title)

  const submit = async () => {
    if (!selected || busy) return
    setBusy(true)
    setError('')
    try {
      // 收件人那一行同时给出两样东西: 消息落到哪段对话(`id`)、以及那段对话属于谁
      // (`peer.id`)。两者必须来自同一行 —— 分开取的话, 一次列表刷新就能让它们错位,
      // 而错位的表现是"消息发给了另一个人"。
      await chatApplications.share(selected.peer.id, selected.id, sessionId, {
        role: 'MEMBER',
        note: cleanNote,
      })
      onSent(selected.id)
    } catch (e) {
      setError(describeShareFailure(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    // ActionSheet 自己的 z 是 50(浮窗是 60) —— 分享单子是这三层里最上面的一层,
    // 而"点遮罩取消分享"必须能盖住聊天浮窗, 否则用户会点到浮窗上的东西。
    <div className="fixed inset-0 z-[70]">
      <ActionSheet title="分享到…" subtitle={title} onClose={onCancel}>
        <div className="flex items-center gap-3 border-b border-line px-4 py-3">
          {share.coverUrl ? (
            // 应用自己给的封面**只在这张单子上显示** —— 它是发起分享的人这一侧的预览。
            // 平台不会把它写进那条消息: 那条消息在**别人**的客户端上渲染, 见 share.ts。
            <img
              src={share.coverUrl}
              alt=""
              className="h-11 w-11 shrink-0 rounded-xl border border-line object-cover"
              data-testid="share-cover-image"
            />
          ) : (
            <span
              className="grid h-11 w-11 shrink-0 place-items-center rounded-xl text-lg text-white"
              style={{ backgroundColor: `hsl(${cover.hue} 55% 45%)` }}
              data-testid="share-cover-generated"
              aria-hidden
            >
              {cover.initial}
            </span>
          )}
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm text-ink">{title}</div>
            {share.description && (
              <div className="mt-0.5 line-clamp-2 text-xs text-ink-faint">{share.description}</div>
            )}
          </div>
        </div>

        {/* 搜索框。空查询时列出全部 —— 打开面板的第一眼必须是"我有哪些人"。 */}
        <div className="flex items-center gap-2 border-b border-line px-4 py-2">
          <Search size={14} className="shrink-0 text-ink-faint" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜名字、标题、聊天记录"
            className="min-w-0 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink-faint"
            data-testid="share-search"
          />
        </div>

        <div className="max-h-[38vh] overflow-y-auto" data-testid="share-recipients">
          {candidates.length === 0 ? (
            <p className="px-4 py-6 text-center text-xs text-ink-faint">
              {query ? '没有匹配的人。' : '还没有可以分享的人。'}
            </p>
          ) : (
            candidates.map((c) => {
              const active = c.id === target
              return (
                <button
                  key={c.id}
                  type="button"
                  onClick={() => setTarget(active ? null : c.id)}
                  aria-pressed={active}
                  className={`flex w-full items-center gap-3 px-4 py-2 text-left transition ${
                    active ? 'bg-accent-soft' : 'hover:bg-sunken/60'
                  }`}
                >
                  <Avatar name={c.peer.name || c.title} kind="agent" size={32} />
                  <span className="min-w-0 flex-1 truncate text-sm text-ink">
                    {c.peer.name || c.title}
                  </span>
                  {active && <span className="shrink-0 text-[11px] text-accent">已选</span>}
                </button>
              )
            })
          )}
        </div>

        <div className="border-t border-line px-4 py-3">
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={2}
            maxLength={MAX_NOTE}
            placeholder="说一句… (可不写)"
            className="w-full resize-none rounded-lg border border-line bg-sunken/40 px-3 py-2 text-sm text-ink outline-none placeholder:text-ink-faint focus:border-accent"
            data-testid="share-note"
          />
          <div className="mt-1 flex items-center justify-between text-[11px] text-ink-faint">
            <span>还能打 {remaining} 字</span>
            {note !== cleanNote && <span>发送时会去掉多余空白</span>}
          </div>

          {error && (
            <p className="mt-2 rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-[11px] text-danger">
              {error}
            </p>
          )}

          <div className="mt-2 flex items-center justify-end gap-2">
            <button
              type="button"
              onClick={onCancel}
              className="rounded-lg border border-line px-3 py-1.5 text-xs text-ink-soft transition hover:text-ink"
            >
              取消
            </button>
            <button
              type="button"
              onClick={() => void submit()}
              disabled={!selected || busy}
              className="flex items-center gap-1.5 rounded-lg bg-accent px-4 py-1.5 text-xs text-accent-ink transition disabled:opacity-40"
              data-testid="share-send"
            >
              <Send size={12} />
              {busy ? '发送中…' : selected ? `发给${selected.peer.name || selected.title}` : '选一个人'}
            </button>
          </div>
        </div>
      </ActionSheet>
    </div>
  )
}
