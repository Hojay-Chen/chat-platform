import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MessageSquare } from 'lucide-react'
import { ConversationRow } from '@/components/im/ConversationRow'
import { EmptyState, SearchBar } from '@/components/im/EmptyState'
import { sortConversations } from '@/lib/conversations'
import { useConversationStore } from '@/stores/conversations'

/**
 * 聊天 tab —— 微信的「微信」那一屏。
 *
 * 一行 = 一段会话, 按 (置顶, 最后一条消息时间) 排。排序规则住在
 * `lib/conversations.ts` 里, 因为它是纯的、可断言的, 而这里只负责取数与过滤。
 *
 * **这个页面自己不做轮询。** 轮询由 `TabLayout` 持有 —— 它在四个 tab 上都挂着,
 * 寿命恰好是"用户在这个应用里"的全部时间; 而 `ChatList` 在切到「通讯录」时就卸载了,
 * 让它持有轮询会导致"切走之后未读角标就不再更新"。这里只负责在挂载时先拉一次,
 * 好让从别的 tab 切回来时看到的是新的。
 */
export default function ChatList() {
  const navigate = useNavigate()
  const list = useConversationStore((s) => s.list)
  const loading = useConversationStore((s) => s.loading)
  const error = useConversationStore((s) => s.error)
  const load = useConversationStore((s) => s.load)

  const [keyword, setKeyword] = useState('')

  useEffect(() => {
    load()
  }, [load])

  const rows = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    const matched = kw
      ? list.filter((c) =>
          (c.peer.name || c.title).toLowerCase().includes(kw) ||
          (c.lastMessage?.content ?? '').toLowerCase().includes(kw))
      : list
    // 排序放在过滤**之后** —— 先排再滤的结果一样, 但这样能少排一批
    return sortConversations(matched)
  }, [list, keyword])

  return (
    <div className="mx-auto w-full max-w-[520px]">
      <header className="sticky top-0 z-10 border-b border-line bg-surface/85 backdrop-blur">
        <h1 className="px-4 pb-1 pt-3 text-lg font-semibold tracking-tight text-ink">聊天</h1>
        <SearchBar value={keyword} onChange={setKeyword} onClear={() => setKeyword('')} />
      </header>

      {loading && list.length === 0 && (
        <p className="py-16 text-center text-sm text-ink-faint">加载中…</p>
      )}

      {!loading && error && list.length === 0 && (
        // 轮询失败时 store 不会清空列表, 所以能走到这里说明一条都没拉到
        <EmptyState
          icon={<MessageSquare size={28} />}
          title="没能加载会话"
          hint={error}
          action={
            <button type="button" className="btn-outline text-xs" onClick={() => load()}>
              重试
            </button>
          }
        />
      )}

      {!loading && !error && list.length === 0 && (
        <EmptyState
          icon={<MessageSquare size={28} />}
          title="还没有聊天"
          hint="去通讯录看看, 那里有你添加的 Agent。"
          action={
            <button
              type="button"
              className="btn-primary text-xs"
              onClick={() => navigate('/contacts')}
            >
              打开通讯录
            </button>
          }
        />
      )}

      {list.length > 0 && rows.length === 0 && (
        <EmptyState title={`没有匹配「${keyword}」的聊天`} />
      )}

      <ul>
        {rows.map((c) => (
          <li key={c.id}>
            <ConversationRow summary={c} onOpen={() => navigate(chatRoomHref(c))} />
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * 一行会话点开之后去哪。
 *
 * 路径里是**会话 id**, 不是伴侣 id —— 这是这一期最要紧的一处修正。一个伴侣可以有多段
 * 对话, 而"我在看哪一段"是 URL 该记住的事: 老实现把它放在 `Chat.tsx` 的局部 state 里,
 * 于是刷新页面会跳到第一个会话, 而不是你正在看的那个。刷新、分享、后退现在都成立。
 */
function chatRoomHref(summary: { id: string }): string {
  return `/chat/${summary.id}`
}
