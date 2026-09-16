import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlarmClock, ChevronLeft, Plus } from 'lucide-react'
import * as agentApi from '@/api/agent'
import { PanelError, PanelLoading } from '@/components/agent/PanelState'
import { Avatar } from '@/components/im/Avatar'
import { EmptyState } from '@/components/im/EmptyState'
import { SectionHeader } from '@/components/im/ListRow'
import { useAgentData } from '@/hooks/useAgentData'
import { mergeByAgent, splitReminders, type AgentScoped, type PerAgent } from '@/lib/agentScoped'
import { reminderTypeZh } from '@/lib/agentLabels'
import { useCompanionStore } from '@/stores/companion'
import type { Reminder } from '@/types'
import { format } from 'date-fns'

/**
 * 「我」→ 提醒。
 *
 * <h2>为什么提醒不在聊天室里</h2>
 *
 * 老实现把它做成 `Chat.tsx` 的一个抽屉, 于是它**只在你正和某个人聊天时才看得见** ——
 * 而提醒的全部意义是"过一会儿再提醒我", 那时你早就不在那个聊天室里了。
 *
 * 它的主语是**你**, 不是某一段对话; 归属是「我」。至于"是谁提醒我的", 那是这一行上的
 * 一个标签, 不是它的地址。
 *
 * <h2>一期的取数: N 个并发请求 + 客户端合并</h2>
 *
 * 后端此刻只有 `GET /api/companions/{id}/reminders`。二期的 `GET /api/reminders` 落地时,
 * 要改的只有 `load()` 那一个函数 —— 排序、分组、渲染收的都是合并后的形状。
 */
interface RemindersBundle {
  groups: PerAgent<Reminder>[]
  /** 一个提醒都建不了的 Agent(比如接口挂了) —— 不该让整页变成错误页, 但要说出来 */
  failures: string[]
}

async function load(companions: { id: string; name: string }[]): Promise<RemindersBundle> {
  const results = await Promise.allSettled(
    companions.map(async (c) => ({
      companionId: c.id,
      name: c.name,
      items: await agentApi.listReminders(c.id),
    })),
  )
  const groups: PerAgent<Reminder>[] = []
  const failures: string[] = []
  results.forEach((r, i) => {
    if (r.status === 'fulfilled') groups.push(r.value)
    // 一个 Agent 拉不到不代表别的也拉不到 —— 用 allSettled 而不是 all,
    // 否则最坏的情况下"她有 3 条提醒"会因为你另一个 Agent 的接口 500 而全部看不见
    else failures.push(companions[i].name)
  })
  return { groups, failures }
}

export default function Reminders() {
  const navigate = useNavigate()
  const companions = useCompanionStore((s) => s.companions)
  const storeLoad = useCompanionStore((s) => s.load)
  const [adding, setAdding] = useState(false)

  /**
   * 直接刷新 `/me/reminders` 时 store 是空的 —— 不拉一次的话这一页会显示成
   * 「还没有可以提醒你的人」, 而实际上有一整个通讯录的人。这一页必须自己保证
   * 依赖的数据到位, 不能指望用户先去过「通讯录」。
   */
  useEffect(() => {
    storeLoad()
  }, [storeLoad])

  // 提醒是按 Agent 存的, 所以要先知道有哪些 Agent。空列表时不该发 N=0 个请求就结束 ——
  // 那会让"还没有 Agent"和"还没有提醒"看起来一样
  const key = companions.map((c) => c.id).join(',')
  const { data, loading, error, reload } = useAgentData(
    key || undefined,
    () => load(companions.map((c) => ({ id: c.id, name: c.name }))),
    { groups: [], failures: [] } as RemindersBundle,
  )

  const rows = mergeByAgent(data.groups)
  const { pending, done } = splitReminders(rows)

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
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">提醒</span>
        <button
          type="button"
          onClick={() => setAdding((v) => !v)}
          title="新建提醒"
          className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
        >
          <Plus size={20} />
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-2xl px-5 py-5">
          {loading && <PanelLoading label="正在看有什么事…" />}
          {error && <PanelError message={error} />}
          {data.failures.length > 0 && (
            <PanelError
              message={`${data.failures.join('、')} 的提醒暂时拿不到, 下面显示的是其他人的。`}
            />
          )}

          {adding && (
            <CreateReminder
              onCreated={() => {
                setAdding(false)
                reload()
              }}
              onCancel={() => setAdding(false)}
            />
          )}

          {!loading && companions.length === 0 && (
            <EmptyState
              icon={<AlarmClock size={26} />}
              title="还没有可以提醒你的人"
              hint="先去通讯录添加一个 Agent, 之后就能让他提醒你。"
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

          {!loading && companions.length > 0 && rows.length === 0 && !adding && (
            <EmptyState
              icon={<AlarmClock size={26} />}
              title="还没有提醒"
              hint="点右上角的加号, 让某个 Agent 到点提醒你。"
            />
          )}

          {pending.length > 0 && (
            <>
              <SectionHeader label="待办" count={pending.length} />
              <ul>
                {pending.map((r) => (
                  <ReminderRow
                    key={`${r.agentId}-${r.item.id}`}
                    row={r}
                    onDone={async () => {
                      await agentApi.completeReminder(r.agentId, r.item.id)
                      reload()
                    }}
                  />
                ))}
              </ul>
            </>
          )}

          {done.length > 0 && (
            <>
              <SectionHeader label="已完成" count={done.length} />
              <ul className="opacity-60">
                {done.map((r) => (
                  <ReminderRow key={`${r.agentId}-${r.item.id}`} row={r} />
                ))}
              </ul>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * 一行提醒。
 *
 * 归属 Agent 的头像与名字放在**这一行里**, 而不是做成按 Agent 分组的小节 ——
 * 用户问的是"我接下来要做什么", 按 Agent 分组会把这个列表变成"晚晚跟我说过什么 /
 * 林夏跟我说过什么", 那是另一个问题。
 */
function ReminderRow({ row, onDone }: { row: AgentScoped<Reminder>; onDone?: () => void }) {
  const r = row.item
  const done = r.status === 'done'
  return (
    <li className="row-base">
      <Avatar name={row.agentName} kind="agent" size={36} />
      <span className="min-w-0 flex-1">
        <span className={`block truncate text-[15px] leading-6 ${done ? 'text-ink-faint line-through' : 'text-ink'}`}>
          {r.title}
        </span>
        <span className="block truncate text-[13px] leading-5 text-ink-faint">
          {row.agentName} · {format(new Date(r.remindAt), 'M月d日 HH:mm')} ·{' '}
          {reminderTypeZh(r.type)}
        </span>
        {r.content && <span className="block truncate text-xs text-ink-soft">{r.content}</span>}
      </span>
      {onDone && (
        <button type="button" onClick={onDone} className="btn-ghost shrink-0 !px-3 !py-1 text-xs">
          完成
        </button>
      )}
    </li>
  )
}

/**
 * 新建提醒要选「让谁提醒我」—— 因为提醒是按 Agent 存的, 没有"系统提醒"这种东西。
 *
 * 这一期只有列表里的第一个 Agent 会被默认选中, 用户可改。二期提醒改成用户维度之后,
 * 这个选择器会变成"要不要告诉她", 而不是"存到谁名下"—— 但在那之前, 藏起这个选择
 * 会让"谁提醒我"变成一个用户看不见的暗决定。
 */
function CreateReminder({
  onCreated,
  onCancel,
}: {
  onCreated: () => void
  onCancel: () => void
}) {
  const companions = useCompanionStore((s) => s.companions)
  const [agentId, setAgentId] = useState(companions[0]?.id ?? '')
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [time, setTime] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  async function submit() {
    if (!agentId || !title.trim() || !time) return
    setBusy(true)
    setErr('')
    try {
      await agentApi.createReminder(agentId, {
        title: title.trim(),
        content: content.trim() || undefined,
        remindAt: time,
      })
      onCreated()
    } catch (e) {
      setErr(e instanceof Error ? e.message : '创建失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mb-5 space-y-2 rounded-xl border border-line bg-raised p-3">
      {companions.length > 1 && (
        <select className="input" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
          {companions.map((c) => (
            <option key={c.id} value={c.id}>
              让 {c.name} 提醒我
            </option>
          ))}
        </select>
      )}
      <input
        className="input"
        placeholder="提醒我…(例如:记得喝水)"
        value={title}
        onChange={(e) => setTitle(e.target.value)}
      />
      <input
        className="input"
        type="datetime-local"
        value={time}
        onChange={(e) => setTime(e.target.value)}
      />
      <input
        className="input"
        placeholder="备注(可选)"
        value={content}
        onChange={(e) => setContent(e.target.value)}
      />
      {err && <p className="text-xs text-danger">{err}</p>}
      <div className="flex gap-2">
        <button
          type="button"
          className="btn-primary flex-1"
          onClick={() => void submit()}
          disabled={busy || !agentId || !title.trim() || !time}
        >
          {busy ? '正在记下…' : '让她记着'}
        </button>
        <button type="button" className="btn-ghost" onClick={onCancel}>
          取消
        </button>
      </div>
      {companions.length === 1 && (
        <p className="text-xs text-ink-faint">到点后 {companions[0].name} 会主动提醒你。</p>
      )}
    </div>
  )
}
