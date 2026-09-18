import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { BellOff, ChevronLeft } from 'lucide-react'
import { setMuted, setPinned, type ConversationSummary } from '@/api/conversations'
import { Avatar } from '@/components/im/Avatar'
import { EmptyState } from '@/components/im/EmptyState'
import { ListRow } from '@/components/im/ListRow'
import { Switch } from '@/components/im/Switch'
import { sortConversations, previewText } from '@/lib/conversations'
import { mutedCount } from '@/lib/notificationSettings'
import { useConversationStore } from '@/stores/conversations'

/**
 * 「我」→ 会话免打扰 —— 一次看全"我把谁静音了"。
 *
 * <h2>为什么在聊天室之外还要有这一页</h2>
 *
 * 因为**忘记了**才是这件事真正的故障形态。聊天室里那个铃铛要打开那一段对话才看得见,
 * 而人不会没事去点开一段他没在等的对话 —— 于是"我上周把它设成免打扰了"这件事,
 * 在他奇怪"她怎么一直不回我"的时候, 一点线索都留不下。
 *
 * 这一页把全部会话的两项设置摊在一屏里, 回答的就是那一个问题: **我把谁静音了。**
 *
 * <h2>为什么每一行都没有 `onClick`</h2>
 *
 * 因为行里有两个开关。`ListRow` 在有 `onClick` 时渲染成 `<button>`, 而 `<button>`
 * 里面不能再放 `<button>` —— 那不只是 HTML 不合法: 真机上它会让里层的开关点不动,
 * 或者更糟, 点开关的同时把整行也点了一遍。`ListRow` 的文件头里写着这条取舍,
 * 这里正是它要照顾的那一种用法。
 *
 * <h2>为什么这一页不做批量操作</h2>
 *
 * 因为它回答的是"我静音了谁", 不是"帮我静音一批"。批量开关看起来很顺手, 但一次误触
 * 就能把全部会话静音 —— 而那个错误的**症状是静默的**: 她不再响, 而你不会知道
 * 是哪一次点击造成的。
 */
export default function Conversations() {
  const navigate = useNavigate()
  const list = useConversationStore((s) => s.list)
  const loading = useConversationStore((s) => s.loading)
  const error = useConversationStore((s) => s.error)
  const load = useConversationStore((s) => s.load)
  const patch = useConversationStore((s) => s.patch)
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    void load()
  }, [load])

  const rows = sortConversations(list)
  const muted = mutedCount(list)

  async function toggle(c: ConversationSummary, key: 'muted' | 'pinned', next: boolean) {
    setBusy(`${c.id}:${key}`)
    const before = c[key]
    patch(c.id, key === 'muted' ? { muted: next } : { pinned: next })
    try {
      if (key === 'muted') await setMuted(c.id, next)
      else await setPinned(c.id, next)
    } catch {
      // 回滚。与聊天室里那个开关同一条规矩: 不回滚的开关是一个用户发现不了的谎。
      patch(c.id, key === 'muted' ? { muted: before } : { pinned: before })
    } finally {
      setBusy(null)
    }
  }

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
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">会话免打扰</span>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-2xl px-3 py-4">
          <p className="mb-4 rounded-lg border border-line bg-sunken/40 px-4 py-3 text-xs leading-relaxed text-ink-soft">
            免打扰打开之后, 平台**根本不会发出**那条通知信号 —— 她那边不会响, 也不会
            "隐约感到"。她只有在自己主动去看手机的时候, 才会知道你说过话。
            <span className="mt-1 block text-ink-faint">
              置顶只影响**你自己**这张列表的顺序, 她那边一个字都不变。
              {muted > 0 && <> 现在有 <b className="text-ink-soft">{muted}</b> 段对话被静音了。</>}
            </span>
          </p>

          {error && (
            <p className="mb-4 rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
              {error}
            </p>
          )}

          {loading && rows.length === 0 && (
            <p className="py-8 text-center text-sm text-ink-faint">读取中…</p>
          )}

          {!loading && rows.length === 0 && (
            <EmptyState
              icon={<BellOff size={26} />}
              title="还没有会话"
              hint="跟谁说上第一句话之后, 这里会列出每一段对话的免打扰与置顶。"
            />
          )}

          {rows.length > 0 && (
            <div className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-raised">
              {rows.map((c) => (
                <ListRow
                  key={c.id}
                  leading={<Avatar name={c.peer.name || c.title} kind="agent" size={40} />}
                  title={c.peer.name || c.title}
                  subtitle={previewText(c.lastMessage) || '还没说过话'}
                  muted={c.muted}
                  trailing={
                    <span className="flex items-center gap-3">
                      <Switch
                        checked={c.muted}
                        disabled={busy !== null}
                        onChange={(v) => void toggle(c, 'muted', v)}
                        label={`${c.peer.name || c.title} 的消息免打扰`}
                      />
                      <Switch
                        checked={c.pinned}
                        disabled={busy !== null}
                        onChange={(v) => void toggle(c, 'pinned', v)}
                        label={`置顶 ${c.peer.name || c.title}`}
                      />
                    </span>
                  }
                />
              ))}
            </div>
          )}

          {rows.length > 0 && (
            <p className="mt-3 px-1 text-[11px] leading-relaxed text-ink-faint">
              左边那个开关是**免打扰**, 右边那个是**置顶**。免打扰会改变她那边的行为,
              置顶不会 —— 所以它们不是同一类设置, 只是都住在这张列表上。
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
