import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ChevronLeft, MessageSquare, Settings as SettingsIcon } from 'lucide-react'
import * as agentApi from '@/api/agent'
import { Avatar } from '@/components/im/Avatar'
import { EmptyState } from '@/components/im/EmptyState'
import { MemoriesPanel } from '@/components/agent/MemoriesPanel'
import { RecentPanel } from '@/components/agent/RecentPanel'
import { RelationshipPanel } from '@/components/agent/RelationshipPanel'
import { UserModelPanel } from '@/components/agent/UserModelPanel'
import { useAgentData } from '@/hooks/useAgentData'
import { stageZh } from '@/lib/agentLabels'
import { relationshipTypeZh } from '@/lib/relationships'
import { useConversationStore } from '@/stores/conversations'
import type { Companion } from '@/types'

/**
 * Agent 资料页 —— 老 `Chat.tsx` 那 7 个抽屉里 4 个的归宿。
 *
 * <h2>为什么是资料页, 不是聊天室里的抽屉</h2>
 *
 * 「记忆 / 它了解的 / 关系 / 最近」这四样都是**关于它的**, 不是**关于这段对话的**。
 * 它们不随会话变, 也不该在聊天时占掉半屏 —— 聊天室里那个位置属于小程序面板。
 * 微信把"这个人是谁"放在联系人资料页, 这里照做。
 *
 * <h2>为什么它现在是一条全屏路由</h2>
 *
 * 它是从通讯录**点进来**的, 是一条真正的浏览路径, 有进有退。而抽屉是"在当前页上盖一层",
 * 一个是空间隐喻、一个是路径隐喻 —— 混用会让返回键的行为变得没法预测。
 *
 * <h2>「发消息」为什么不是一句 `navigate`</h2>
 *
 * 因为**刚创建的 Agent 一段会话都没有**。聊天室按 conversationId 寻址, 而没有会话时
 * 那个 id 不存在。所以这里要先看会话列表里有没有它, 没有再开一段 —— 这正是老实现里
 * `conversations/first` 那条路径, 也是 `Chat.tsx` 一度不能删的唯一原因。它现在归位到了
 * 这里, 而这一段逻辑本来就该属于"点一个人, 跟他说话"这个动作。
 */
type TabKey = 'recent' | 'memories' | 'model' | 'relationship'

const TABS: { key: TabKey; label: string }[] = [
  { key: 'recent', label: '最近' },
  { key: 'memories', label: '记忆' },
  { key: 'model', label: '它了解的' },
  { key: 'relationship', label: '关系' },
]

/**
 * 页头只要一个请求 —— `Companion` 自己就带着 `relationshipStage` 与 `relationshipType`。
 *
 * 一度写成再拉一次 `/relationship` 拿同样的两个字段, 那是错的: 多一次往返、多一个
 * 失败点, 而失败时这一屏最显眼的那行字会变成空白。关系面板里那次请求是另一回事 ——
 * 它要的是**完整的关系读数**, 那两个字段只是其中的一小部分。
 */
function loadHeader(companionId: string): Promise<Companion> {
  return agentApi.getAgent(companionId)
}

export default function AgentProfile() {
  const { companionId } = useParams<{ companionId: string }>()
  const navigate = useNavigate()
  const [tab, setTab] = useState<TabKey>('recent')
  const [opening, setOpening] = useState(false)
  const [openError, setOpenError] = useState('')

  const list = useConversationStore((s) => s.list)
  const { data: agent, loading, error } = useAgentData(
    companionId,
    loadHeader,
    null as Companion | null,
  )

  if (!companionId) {
    return <EmptyState title="没有指定联系人" hint="请从通讯录里点一个人进来。" />
  }

  async function openChat() {
    if (!companionId) return
    // 已经聊过就直接进那一段。列表里的 `peer.id` 就是 companionId —— 一期的恒等式,
    // 二期的翻译点在 `api/conversations.ts`
    const existing = list.find((c) => c.peer.id === companionId)
    if (existing) {
      navigate(`/chat/${existing.id}`)
      return
    }
    setOpening(true)
    setOpenError('')
    try {
      navigate(`/chat/${await agentApi.openConversation(companionId)}`)
    } catch (e) {
      setOpenError(e instanceof Error ? e.message : '打不开对话')
    } finally {
      setOpening(false)
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="z-10 flex items-center gap-1 border-b border-line bg-surface/90 px-1.5 py-1.5 backdrop-blur">
        <button
          type="button"
          onClick={() => navigate('/contacts')}
          title="返回"
          className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
        >
          <ChevronLeft size={20} />
        </button>
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">
          {agent?.name ?? '联系人'}
        </span>
        <button
          type="button"
          onClick={() => navigate(`/contacts/agent/${companionId}/settings`)}
          title="设置"
          className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
        >
          <SettingsIcon size={18} />
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {loading && !agent && (
          <p className="py-16 text-center text-sm text-ink-faint">加载中…</p>
        )}

        {!loading && !agent && (
          <EmptyState
            title="找不到这个人"
            hint={error}
            action={
              <button type="button" className="btn-outline text-xs" onClick={() => navigate('/contacts')}>
                回通讯录
              </button>
            }
          />
        )}

        {agent && (
          <div className="mx-auto w-full max-w-2xl">
            <div className="flex items-start gap-4 px-5 py-5">
              <Avatar name={agent.name} kind="agent" size={64} />
              <div className="min-w-0 flex-1">
                <h1 className="truncate text-xl font-semibold tracking-tight text-ink">
                  {agent.name}
                </h1>
                {/*
                  账号ID 单独一行、带标签。这里是**唯一**该出现「账号ID」这四个字的地方 ——
                  列表行里它是名字旁边一串需要时才细看的字符, 而资料页是用户来查"她到底
                  是哪一个"的地方, 所以要写清楚这串东西是什么、并且能被选中复制。
                */}
                {agent.handle && (
                  <p className="mt-1 flex items-baseline gap-1.5 text-xs text-ink-faint">
                    <span className="shrink-0">账号ID</span>
                    <span className="select-all truncate font-mono text-ink-soft">{agent.handle}</span>
                  </p>
                )}
                <p className="mt-0.5 truncate text-xs text-ink-faint">
                  {[stageZh(agent.relationshipStage), agent.relationshipType ? relationshipTypeZh(agent.relationshipType) : '']
                    .filter(Boolean)
                    .join(' · ')}
                </p>
                {agent.greeting && (
                  <p className="mt-1.5 line-clamp-2 text-sm leading-relaxed text-ink-soft">
                    {agent.greeting}
                  </p>
                )}
              </div>
            </div>

            <div className="px-5">
              <button
                type="button"
                className="btn-primary w-full"
                onClick={() => void openChat()}
                disabled={opening}
              >
                <MessageSquare size={15} />
                {opening ? '正在打开…' : '发消息'}
              </button>
              {openError && (
                <p className="mt-2 text-center text-xs text-danger">{openError}</p>
              )}
            </div>

            {/*
              四个 tab 是**互斥的四个视图**, 不是四段折叠起来的内容 —— 所以这里用
              tab 而不是 accordion: 用户在这一屏问的是"它是谁", 一次看一面就够。
            */}
            <div className="mt-5 flex gap-1 border-b border-line px-3">
              {TABS.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setTab(t.key)}
                  className={`-mb-px border-b-2 px-3 py-2 text-sm transition-colors ${
                    tab === t.key
                      ? 'border-accent font-medium text-accent'
                      : 'border-transparent text-ink-soft hover:text-ink'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            {/*
              四个面板都要 `agentName`: 它们原来通篇用「她」—— 那是"数字伴侣"时代
              的遗留(那个产品里 agent 恒为女性)。现在 agent 按用户需求生成, gender
              可以是 male, 于是「她今天在干嘛」就成了界面在说假话。这一屏里 agent
              一定已经加载出来了(上面那个 `{agent && ...}` 就是闸门), 所以把名字
              传下去是零成本的。
            */}
            <div className="px-5 py-5">
              {tab === 'recent' && <RecentPanel companionId={companionId} agentName={agent.name} />}
              {tab === 'memories' && <MemoriesPanel companionId={companionId} agentName={agent.name} />}
              {tab === 'model' && <UserModelPanel companionId={companionId} agentName={agent.name} />}
              {tab === 'relationship' && (
                <RelationshipPanel companionId={companionId} agentName={agent.name} />
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
