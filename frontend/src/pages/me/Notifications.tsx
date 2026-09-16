import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Bell, ChevronLeft } from 'lucide-react'
import * as agentApi from '@/api/agent'
import { PanelError, PanelLoading } from '@/components/agent/PanelState'
import { Avatar } from '@/components/im/Avatar'
import { EmptyState } from '@/components/im/EmptyState'
import { SectionHeader } from '@/components/im/ListRow'
import { useAgentData } from '@/hooks/useAgentData'
import { notificationTypeZh } from '@/lib/agentLabels'
import { mergeByAgent, sortNotifications, type PerAgent } from '@/lib/agentScoped'
import { timeAgo } from '@/lib/time'
import { useCompanionStore } from '@/stores/companion'
import type { Notification } from '@/types'

/**
 * 「我」→ 通知 —— Agent 主动找过你的那些时刻。
 *
 * <h2>进这一页就等于读完了</h2>
 *
 * 挂载时把所有未读标成已读。这不是偷懒, 而是这一页**唯一的语义**: 用户点进来看见了,
 * 那就读过了。反过来做(逐条点开才算读、或者给每条一个「标为已读」按钮)会把一个
 * 只读的流水页变成一个待办清单 —— 而它记的是"Agent 做过什么", 不是"你欠它什么"。
 *
 * <h2>一期的取数: N 个并发请求 + 客户端合并</h2>
 *
 * 与提醒那一页同构。二期的正解是把它做成聊天 tab 里的一个「服务通知」伪会话 ——
 * 但那要求后端把它做成会话, 一期做会是错的形状。所以现在它在这里。
 */
interface NotificationsBundle {
  groups: PerAgent<Notification>[]
  failures: string[]
  /** 这次读到的未读条数 —— 用来决定要不要提示"已标记为已读" */
  markedRead: number
}

async function load(companions: { id: string; name: string }[]): Promise<NotificationsBundle> {
  const results = await Promise.allSettled(
    companions.map(async (c) => ({ c, items: await agentApi.listNotifications(c.id) })),
  )

  const groups: PerAgent<Notification>[] = []
  const failures: string[] = []
  const unreadOwners: string[] = []

  for (const r of results) {
    if (r.status !== 'fulfilled') continue
    const { c, items } = r.value
    groups.push({ companionId: c.id, name: c.name, items })
    if (items.some((n) => !n.read)) unreadOwners.push(c.id)
  }
  results.forEach((r, i) => {
    if (r.status === 'rejected') failures.push(companions[i].name)
  })

  // 标已读是**副作用**, 但它必须发生在拿到列表之后、且不该让整页失败 ——
  // 一次 PUT 失败时用户至少还能看见那些通知, 而不是一个错误页
  await Promise.allSettled(unreadOwners.map((id) => agentApi.readAllNotifications(id)))

  return { groups, failures, markedRead: unreadOwners.length }
}

export default function Notifications() {
  const navigate = useNavigate()
  const companions = useCompanionStore((s) => s.companions)
  const storeLoad = useCompanionStore((s) => s.load)

  useEffect(() => {
    storeLoad()
  }, [storeLoad])

  const key = companions.map((c) => c.id).join(',')
  const { data, loading, error } = useAgentData(
    key || undefined,
    () => load(companions.map((c) => ({ id: c.id, name: c.name }))),
    { groups: [], failures: [], markedRead: 0 } as NotificationsBundle,
  )

  const rows = sortNotifications(mergeByAgent(data.groups))

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="z-10 flex items-center gap-1 border-b border-line bg-surface/90 px-1.5 py-1.5 backdrop-blur">
        <button
          type="button"
          onClick={() => navigate('/me')}
          title="返回"
          className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
        >
          <ChevronLeft size={20} />
        </button>
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">通知</span>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-2xl">
          {loading && (
            <div className="px-5 py-5">
              <PanelLoading label="正在看 Agent 找过你几次…" />
            </div>
          )}

          {error && (
            <div className="px-5 py-5">
              <PanelError message={error} />
            </div>
          )}

          {data.failures.length > 0 && (
            <div className="px-5 pt-5">
              <PanelError
                message={`${data.failures.join('、')} 的通知暂时拿不到, 下面显示的是其他人的。`}
              />
            </div>
          )}

          {!loading && companions.length === 0 && (
            <EmptyState
              icon={<Bell size={26} />}
              title="还没有人会找你"
              hint="添加一个 Agent 之后, 它会在想起你的时候主动出现。"
              action={
                <button
                  type="button"
                  className="btn-primary text-xs"
                  onClick={() => navigate('/contacts/new')}
                >
                  添加 Agent
                </button>
              }
            />
          )}

          {!loading && companions.length > 0 && rows.length === 0 && (
            <EmptyState
              icon={<Bell size={26} />}
              title="还没有通知"
              hint="Agent 主动找你的时候, 会在这里留下一条。"
            />
          )}

          {rows.length > 0 && (
            <>
              <SectionHeader label="全部通知" count={rows.length} />
              <ul>
                {rows.map(({ agentId, agentName, item: n }) => (
                  <li key={`${agentId}-${n.id}`} className="row-base items-start">
                    <Avatar name={agentName} kind="agent" size={36} className="mt-0.5" />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2">
                        <span className="truncate text-[15px] leading-6 text-ink">{n.title}</span>
                        {!n.read && (
                          <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-danger" title="未读" />
                        )}
                      </span>
                      {n.content && (
                        <span className="mt-0.5 block text-[13px] leading-5 text-ink-soft">
                          {n.content}
                        </span>
                      )}
                      <span className="mt-0.5 block text-[11px] leading-4 text-ink-faint">
                        {agentName} · {notificationTypeZh(n.type)} · {timeAgo(n.createdAt)}
                      </span>
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
