import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Boxes, ExternalLink } from 'lucide-react'
import type { PeerRef } from '@/types'
import { chatApplications } from '@/api/chatApplications'
import type { ConversationApplications, OpenableApplication, OpenSessionView } from '@/api/chatApplications'
import { SkeletonRows } from '@/components/agent/PanelState'
import { Panel } from '@/components/im/Panel'
import { sessionStatusZh } from '@/lib/agentLabels'

/**
 * 聊天室「+」里的应用面板 —— 从 `Chat.tsx:615-700` 的 `ApplicationsPanel` 搬过来的。
 *
 * <h2>为什么它必须留在聊天现场</h2>
 *
 * 这是平台与微信的分野那一处: 一段对话里可以「一起玩点什么」。把它挪进「发现」tab
 * 也能到, 但那是**逛**的路径; 而真实的动作发生在对话中间 —— 聊着聊着开一局。
 * 所以「发现」是入口, 这里是主路径。
 *
 * <h2>搬动时改了什么</h2>
 *
 * 只改了措辞与外观, 判定一个字没动:
 * - `companionId` → `peer: PeerRef`, 只在调 `chatApplications` 时翻译回路径段(见 §1.3 ③)
 * - 暖棕色号 → 语义 token
 * - 「打开」的 `disabled` 仍然由服务端给的 `allowsNewSession` 决定, 前端不复制这份判断
 *
 * 抽掉了 `Chat.tsx` 里那个本地 `Empty` 组件, 改用共用的 `EmptyState` —— 它多一个
 * `hint`, 而"这段对话里还没有开着的应用"恰好需要一句解释。
 */
export function ChatRoomPanel({
  open,
  onClose,
  peer,
  conversationId,
  onOpened,
}: {
  open: boolean
  onClose: () => void
  peer: PeerRef
  conversationId: string
  /** 开完应用后重拉消息 —— 平台已经在对话里落下了一条卡片消息 */
  onOpened: () => void
}) {
  const navigate = useNavigate()
  const [data, setData] = useState<ConversationApplications | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setData(await chatApplications.context(peer.id, conversationId))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    }
  }, [peer.id, conversationId])

  useEffect(() => {
    if (open) void load()
  }, [open, load])

  const openApp = async (applicationId: string) => {
    setBusy(applicationId)
    setError(null)
    try {
      await chatApplications.open(peer.id, conversationId, applicationId)
      onOpened()
      await load()
    } catch (e) {
      // 被拒时两边都不留痕迹(平台先开应用再落消息), 所以这里只需要说清原因
      setError(e instanceof Error ? e.message : '打开失败')
    } finally {
      setBusy(null)
    }
  }

  return (
    <Panel open={open} title={`和${peer.name ?? 'TA'}一起玩`} onClose={onClose}>
      {error && (
        <div className="mb-4 flex items-center gap-3 rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
          <span className="min-w-0 flex-1">{error}</span>
          {!data && (
            <button
              type="button"
              onClick={() => void load()}
              className="shrink-0 rounded-lg border border-danger/40 px-2.5 py-0.5 text-xs transition-colors hover:bg-danger/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
            >
              重试
            </button>
          )}
        </div>
      )}

      {/*
        骨架而不是「正在看看有什么…」—— 下面出来的是**两段行的列表**, 那句字给不出
        任何形状, 于是数据到位时整块换掉。骨架的形状与 `OpenSessionRow`/`OpenableRow`
        一致(见 `SkeletonRows` 的类注释): 头像块 + 两行字 + 右侧一个按钮。
      */}
      {!data && !error && (
        <div aria-busy aria-label="正在看这段对话里有什么应用">
          <SkeletonRows rows={3} avatar />
        </div>
      )}

      {data && (
        <div className="space-y-6">
          <section>
            <SectionHead title="正在进行的" count={data.open.length} />
            {data.open.length === 0 ? (
              <p className="text-sm text-ink-faint">这段对话里还没有开着的应用。</p>
            ) : (
              <div className="space-y-2">
                {data.open.map((s) => (
                  <OpenSessionRow
                    key={s.sessionId}
                    session={s}
                    onEnter={() => navigate(`/sessions/${s.sessionId}`)}
                  />
                ))}
              </div>
            )}
          </section>

          <section>
            <SectionHead title="还能开的" count={data.openable.length} />
            {data.openable.length === 0 ? (
              <p className="text-sm text-ink-faint">现在没有可以开的新应用。</p>
            ) : (
              <div className="space-y-2">
                {data.openable.map((app) => (
                  <OpenableRow
                    key={app.applicationId}
                    application={app}
                    busy={busy === app.applicationId}
                    onOpen={() => openApp(app.applicationId)}
                  />
                ))}
              </div>
            )}
          </section>

          <button
            type="button"
            onClick={() => navigate('/discover')}
            className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-line py-2 text-xs text-ink-soft transition-colors hover:bg-sunken hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
          >
            <ExternalLink size={13} />
            去发现看看还有什么
          </button>
        </div>
      )}
    </Panel>
  )
}

function SectionHead({ title, count }: { title: string; count: number }) {
  return (
    <div className="mb-2 flex items-baseline gap-2">
      <h3 className="text-xs font-medium tracking-wide text-ink-soft">{title}</h3>
      <span className="tnum text-[11px] text-ink-faint">{count}</span>
    </div>
  )
}

function OpenableRow({
  application,
  busy,
  onOpen,
}: {
  application: OpenableApplication
  busy: boolean
  onOpen: () => void
}) {
  return (
    <div className="flex items-start gap-3 rounded-lg border border-line bg-raised px-3 py-2.5">
      <span className="mt-0.5 rounded-md bg-accent-soft p-1.5 text-accent">
        <Boxes size={14} />
      </span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm text-ink">{application.name}</div>
        {application.description && (
          <p className="mt-0.5 line-clamp-2 text-xs leading-relaxed text-ink-faint">
            {application.description}
          </p>
        )}
      </div>
      <button
        type="button"
        onClick={onOpen}
        // 服务端说不能开就是不能开 —— 下架的应用仍然出现在市场里(它认得出自己是谁),
        // 但它不在这张"可以开一个"的列表里; 这里再挡一次是为了"恰好在这一刻被下架"。
        disabled={busy || !application.allowsNewSession}
        // 灰掉的按钮必须说清为什么灰 —— 一个没有理由的 disabled 按钮看起来像坏了。
        title={application.allowsNewSession ? `打开 ${application.name}` : '这个应用现在开不了新的一局'}
        className="btn-primary shrink-0 !px-3 !py-1 text-xs disabled:opacity-40"
      >
        {busy ? '正在打开…' : '打开'}
      </button>
    </div>
  )
}

function OpenSessionRow({ session, onEnter }: { session: OpenSessionView; onEnter: () => void }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-line bg-raised px-3 py-2.5">
      <div className="min-w-0 flex-1">
        {/* applicationId 是技术标识 —— 等宽的 tnum 让它看起来就是个 id, 而不是标题 */}
        <div className="truncate font-mono text-xs text-ink">
          {session.applicationId}
          {session.version ? ` v${session.version}` : ''}
        </div>
        {/* `status` 是后端枚举 —— 原来它裸着印在中文句子中间("3 人 · ACTIVE") */}
        <div className="mt-0.5 text-[11px] text-ink-faint">
          <span className="tnum">{session.participantCount} 人</span> ·{' '}
          {sessionStatusZh(session.status)}
        </div>
      </div>
      <button
        type="button"
        onClick={onEnter}
        title="回到这一场"
        className="shrink-0 rounded-lg border border-line px-3 py-1 text-xs text-ink-soft transition-colors hover:bg-sunken hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
      >
        进入
      </button>
    </div>
  )
}
