import { useState } from 'react'
import * as agentApi from '@/api/agent'
import { useAgentData } from '@/hooks/useAgentData'
import { memoryTypeZh } from '@/lib/agentLabels'
import { truncate } from '@/lib/text'
import { timeAgo } from '@/lib/time'
import type { Memory, MemoryEntity, MemoryLink, MemorySourceMessage } from '@/types'
import { PanelError, PanelLoading } from './PanelState'

/**
 * 资料页的「记忆」。
 *
 * <h2>「为什么」与「忘记」并排放</h2>
 *
 * 每一条记忆右侧有两个动作: 看它从哪来, 和删掉它。**这两个必须挨着** ——
 * 一个能被删除的记忆系统, 用户点删除之前有权知道自己在删什么。把「为什么」藏进
 * 二级菜单里, 得到的是一个只能盲删的界面。
 *
 * <h2>清空全部要二次确认, 「忘记」一条不用</h2>
 *
 * 单条删除是可逆的(再聊一次她就想起来了), 而且用户看得见自己删的是哪一条;
 * 清空全部是不可逆的、看不见边界的。所以只有后者弹 confirm。
 *
 * <h2>搜索结果是一层覆盖, 不是一份新数据</h2>
 *
 * `hits === null` 表示"在看全部", 非 null 表示"在看搜索结果"。两者分开存, 而不是
 * 把结果写回主列表 —— 后者会让「清除搜索」无从下手(要重新拉一次才知道原来有哪些),
 * 而且删掉一条搜索结果之后该回到哪一份也就说不清了。
 */

interface MemoriesBundle {
  memories: Memory[]
  entities: MemoryEntity[]
}

const EMPTY: MemoriesBundle = { memories: [], entities: [] }

async function load(companionId: string): Promise<MemoriesBundle> {
  const [memories, entities] = await Promise.all([
    agentApi.listMemories(companionId),
    // 实体列表是锦上添花的一栏 —— 拉不到不该让整个记忆面板变成错误页
    agentApi.listMemoryEntities(companionId).catch(() => [] as MemoryEntity[]),
  ])
  return { memories, entities }
}

export function MemoriesPanel({ companionId }: { companionId: string }) {
  const { data, loading, error, reload } = useAgentData(companionId, load, EMPTY)

  const [hits, setHits] = useState<Memory[] | null>(null)
  const [q, setQ] = useState('')
  const [searching, setSearching] = useState(false)
  const [sourceOf, setSourceOf] = useState<Record<string, MemorySourceMessage[]>>({})
  const [graph, setGraph] = useState<{ nodes: Memory[]; links: MemoryLink[] } | null>(null)
  const [linkCount, setLinkCount] = useState<number | null>(null)

  if (loading) return <PanelLoading label="正在翻她的记忆…" />
  if (error) return <PanelError message={error} />

  const memories = hits ?? data.memories
  const nodeById = new Map((graph?.nodes ?? []).map((n) => [n.id, n]))

  /** 有写操作之后调它: 清掉搜索覆盖, 再重拉主列表 —— 见文件头 */
  function refresh() {
    setHits(null)
    setQ('')
    setSourceOf({})
    reload()
  }

  async function search() {
    const query = q.trim()
    if (!query) {
      setHits(null)
      return
    }
    setSearching(true)
    try {
      setHits(await agentApi.searchMemories(companionId, query))
    } finally {
      setSearching(false)
    }
  }

  async function forget(id: string) {
    await agentApi.forgetMemory(companionId, id)
    refresh()
  }

  async function clearAll() {
    // 见文件头: 这一条不可逆、看不见边界, 所以只有它确认
    if (!confirm('确定让她忘记所有这些记忆吗?这是不可逆的。')) return
    await agentApi.clearMemories(companionId)
    refresh()
  }

  async function toggleSource(id: string) {
    if (sourceOf[id]) {
      setSourceOf((s) => {
        const next = { ...s }
        delete next[id]
        return next
      })
      return
    }
    const resp = await agentApi.memorySource(companionId, id)
    setSourceOf((s) => ({ ...s, [id]: resp.source }))
  }

  /**
   * 收起图谱时 `graph` 变成 null, 于是按钮上的条数不能从它算 —— 否则一收起来就变成
   * 「记忆图谱 (… 条关联)」, 那个省略号会一直挂在那里。条数单独记一份。
   */
  async function toggleGraph() {
    if (graph) {
      setGraph(null)
      return
    }
    const g = await agentApi.memoryGraph(companionId)
    setGraph(g)
    setLinkCount(g.links.length)
  }

  return (
    <div className="space-y-3">
      <div className="flex gap-2">
        <input
          className="input"
          placeholder="搜索记忆…(试试「咖啡」或「加班」)"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void search()
          }}
        />
        <button
          type="button"
          className="btn-ghost shrink-0 !px-3"
          onClick={() => void search()}
          disabled={searching}
        >
          搜
        </button>
      </div>
      <p className="text-xs text-ink-faint">
        她记得这些, 并在聊天时自然地使用它们。点「为什么」可看来源对话。
      </p>

      {data.entities.length > 0 && (
        <div className="rounded-xl border border-line bg-raised p-3">
          <p className="text-xs text-ink-faint">她认识的(你常提的人 / 地方 / 事):</p>
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            {data.entities.map((e) => (
              <span
                key={e.id}
                title={e.description || `${e.type} · 提过 ${e.mentionCount} 次`}
                className="chip bg-accent-soft text-accent"
              >
                {e.name}
                <span className="ml-1 text-[10px] text-ink-faint tnum">{e.mentionCount}</span>
              </span>
            ))}
          </div>
        </div>
      )}

      <div className="flex items-center justify-between">
        <button
          type="button"
          className="btn-outline !px-3 !py-1 text-xs"
          onClick={() => void toggleGraph()}
        >
          {graph
            ? '收起记忆图谱'
            : `记忆图谱${linkCount === null ? '' : ` (${linkCount} 条关联)`}`}
        </button>
        <button
          type="button"
          className="btn-danger !px-3 !py-1 text-xs"
          onClick={() => void clearAll()}
        >
          清空全部
        </button>
      </div>

      {graph && graph.nodes.length > 0 && (
        <div className="animate-fadeUp space-y-1.5 rounded-xl border border-line bg-raised p-3">
          <p className="text-xs text-ink-faint">相关记忆彼此相连:</p>
          {graph.links.map((l) => {
            const a = nodeById.get(l.fromMemoryId)
            const b = nodeById.get(l.toMemoryId)
            // 关联的两头有一头不在节点列表里 —— 后端给了一张不完整的图。
            // 跳过这一条, 而不是画一个半截的 "↔"
            if (!a || !b) return null
            return (
              <div key={l.id} className="text-xs text-ink-soft">
                <span className="text-ink">{truncate(a.content, 14)}</span>
                <span className="mx-1 text-accent">↔</span>
                <span className="text-ink">{truncate(b.content, 14)}</span>
              </div>
            )
          })}
        </div>
      )}

      {memories.length === 0 && (
        <p className="py-8 text-center text-sm text-ink-faint">
          {hits ? '没有找到相关的记忆。' : '还没有记忆, 去和她聊聊吧。'}
        </p>
      )}

      {memories.map((m) => (
        <div key={m.id} className="rounded-xl border border-line bg-raised p-3">
          <div className="flex items-center gap-2 text-xs text-ink-faint">
            <span className="chip bg-accent-soft text-accent">{memoryTypeZh(m.type)}</span>
            <span className="tnum">
              {m.occurredAt ? timeAgo(m.occurredAt) : ''}
              {m.sourceType === 'conversation' ? ' · 来自对话' : ''}
            </span>
            <div className="ml-auto flex items-center gap-2">
              <button
                type="button"
                onClick={() => void toggleSource(m.id)}
                className="text-accent hover:underline"
              >
                为什么
              </button>
              <button
                type="button"
                onClick={() => void forget(m.id)}
                className="text-ink-faint transition-colors hover:text-danger"
              >
                忘记
              </button>
            </div>
          </div>
          <p className="mt-1.5 text-sm text-ink">{m.content}</p>
          {sourceOf[m.id] && (
            <div className="animate-fadeIn mt-2 space-y-1 rounded-lg bg-sunken p-2.5">
              <p className="text-[11px] text-ink-faint">来源对话:</p>
              {sourceOf[m.id].length === 0 && (
                <p className="text-xs text-ink-faint">这条记忆没有留下原始对话。</p>
              )}
              {sourceOf[m.id].map((s, i) => (
                <p key={i} className="text-xs text-ink-soft">
                  <span className={s.sender === 'user' ? 'text-accent' : 'text-ink-faint'}>
                    {s.sender === 'user' ? '你' : '她'}:
                  </span>{' '}
                  {s.content}
                </p>
              ))}
            </div>
          )}
        </div>
      ))}

      {hits && (
        <button
          type="button"
          className="btn-ghost w-full !py-1.5 text-xs"
          onClick={() => {
            setHits(null)
            setQ('')
          }}
        >
          清除搜索, 看全部记忆
        </button>
      )}
    </div>
  )
}
