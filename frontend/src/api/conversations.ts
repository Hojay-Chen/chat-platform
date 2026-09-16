import type { Message, PeerRef } from '@/types'
import { api } from './client'

/**
 * 「我的会话」—— 聊天 tab 那一屏的数据来源。
 *
 * <h2>这个文件是 `PeerRef` 与 `companionId` 之间**唯一**的翻译层</h2>
 *
 * 后端此刻返回的每一行都带着 `peerId`, 而在一期它恒等于 companionId。但这个等式是
 * 二期的**意外**而不是一期的**约定**: 二期一旦有了真人会话与群聊, 对面可能是
 * `{kind:'user'}` 或 `{kind:'group'}`。
 *
 * 所以翻译只发生在 {@link toSummary} 这一个函数里。到时候只需要在这里加一个
 * `kind` 分支, 别的文件一个字都不用动 —— 这正是 `PeerRef` 存在的全部理由。
 *
 * <h2>没有 `POST /api/conversations/{id}/messages`</h2>
 *
 * 后端刻意没提供。那个端点的语义是"真人发消息, 不经过 LLM", 而一期里每一个会话的对面
 * 都是一个数字人 —— 从那条路发出去的消息不会触发任何事件, agent 永远不会回。
 * 所以发消息仍然走 `api/chatApplications.ts` 隔壁那套伴侣域端点, 那里会触发 agent。
 * 这里不给一个"看起来能发消息"的函数: 一个用起来静默失效的 API 比没有这个 API 更糟。
 */

/**
 * 服务端返回的一行(`ConversationSummaryView`)。
 *
 * 内部类型, 不导出 —— 它的字段名是后端的措辞(`peerId`), 而界面只该看见
 * `peer: PeerRef`。导出它等于给了别人一条绕过翻译层的捷径。
 */
interface ConversationSummaryDto {
  id: string
  peerId: string
  peerName: string
  title: string
  status?: string | null
  messageCount: number
  lastMessageAt?: string | null
  lastMessage?: {
    id: string
    senderType: string
    senderId?: string | null
    content: string
    messageKind?: string | null
    createdAt: string
  } | null
  unreadCount: number
  pinned: boolean
  muted: boolean
}

/** 界面看见的一行。二期加真人/群聊时, 这个类型不变 */
export interface ConversationSummary {
  id: string
  peer: PeerRef
  title: string
  lastMessage?: {
    id: string
    senderType: string
    senderId?: string | null
    content: string
    messageKind?: string | null
    createdAt: string
  } | null
  lastMessageAt?: string | null
  unreadCount: number
  pinned: boolean
  /** 免打扰。会话行仍显示小红点, 但 TabBar 的总数不算它 */
  muted: boolean
}

/**
 * 一行 DTO → 一行界面数据。**纯函数, 单独导出就是为了能直接测它。**
 *
 * 一期的 `kind` 恒为 `'companion'`: 今天每一个会话的对面都是一个数字人。
 * 这个"恒为"是写死的而不是猜出来的 —— 后端的 `peerId` 就是 companionId。
 */
export function toSummary(dto: ConversationSummaryDto): ConversationSummary {
  return {
    id: dto.id,
    peer: { kind: 'companion', id: dto.peerId, name: dto.peerName },
    title: dto.title,
    lastMessage: dto.lastMessage ?? null,
    lastMessageAt: dto.lastMessageAt ?? null,
    unreadCount: dto.unreadCount ?? 0,
    pinned: Boolean(dto.pinned),
    muted: Boolean(dto.muted),
  }
}

/**
 * 我的全部会话, 最近的排前面。
 *
 * 服务端**不**按置顶排, 置顶排序在 `lib/conversations.ts` —— 服务端再排一次的话
 * 前端还得再排一次, 两处规则迟早会不一致。数据从哪来是一件事, 怎么显示是另一件事。
 */
export async function listConversations(): Promise<ConversationSummary[]> {
  const dtos = await api.get<ConversationSummaryDto[]>('/api/conversations')
  return dtos.map(toSummary)
}

/** 单独取一行 —— 从通知点进来时只需要这一行, 不该逼客户端拉整个列表 */
export async function getConversation(conversationId: string): Promise<ConversationSummary> {
  return toSummary(await api.get<ConversationSummaryDto>(`/api/conversations/${conversationId}`))
}

/** 某段会话的全部消息 */
export async function listMessages(conversationId: string): Promise<Message[]> {
  return api.get<Message[]>(`/api/conversations/${conversationId}/messages`)
}

/**
 * 读到某条消息为止。`lastMessageId` 可以不传 —— 不传就是"全读了"。
 *
 * 进入聊天室、以及窗口重新获得焦点时调它。刻意**不**做成"拉一次消息就算读了":
 * 拉消息是渲染路径, 读到哪是用户行为, 把两者混在一起会让"我只是切了个窗口"
 * 也算读完了 —— 而那个窗口可能只是被别的程序挡了一下。
 */
export async function markRead(conversationId: string, lastMessageId?: string): Promise<void> {
  await api.post<void>(`/api/conversations/${conversationId}/read`,
    lastMessageId ? { lastMessageId } : {})
}

export async function setPinned(conversationId: string, pinned: boolean): Promise<void> {
  await api.post<void>(`/api/conversations/${conversationId}/pin`, { pinned })
}

export async function setMuted(conversationId: string, muted: boolean): Promise<void> {
  await api.post<void>(`/api/conversations/${conversationId}/mute`, { muted })
}

// ── 发消息 ──────────────────────────────────────────────────────────

/** 一条待发出的消息。`clientMessageId` 既是幂等键, 也是 temp→canonical 的对应键 */
export interface OutgoingMessage {
  content: string
  clientMessageId: string
}

/** 服务端回传的已落库消息。字段是 `Message` 的子集, 只带 temp→canonical 替换要用的那几个 */
export interface CanonicalMessage {
  id: string
  clientMessageId?: string | null
  content: string
  conversationId: string
  senderType: string
  senderId?: string | null
  deliveryStatus?: string | null
  createdAt: string
}

export interface SendResult {
  status: string
  messageId: string
  messages: CanonicalMessage[]
}

/**
 * 发一批消息。**一次请求至多触发一次回复** —— 所以连发的多句必须一起送,
 * 分开送对方就会回多次(见 `lib/burstWindow.ts` 的长注释)。
 *
 * <h2>为什么路径里是 companionId, 不是 conversationId</h2>
 *
 * 这个端点住在聊天平台**自己**的面上(`/api/companions/{c}/conversations/{v}/messages`,
 * 8081 实现), 它是"往这段对话里说一句话, 并让对面的数字人有机会回应"—— 触发认知链正是
 * 它存在的全部意义, 而 `companionId` 是触发所需的那半个键。
 *
 * 与它相邻的 `/api/conversations/{id}/...` 那一套是**只读 + 读状态**, 后端刻意没在那边
 * 提供发消息: 一个不触发任何事件的发送端点在一期里唯一的作用, 是把"agent 不回我了"
 * 这个 bug 引进来。二期真有真人会话时, 那条路径会在那边落地, 并且**不**触发 LLM。
 */
export async function sendMessages(
  companionId: string,
  conversationId: string,
  batch: readonly OutgoingMessage[],
): Promise<SendResult> {
  return api.post<SendResult>(
    `/api/companions/${companionId}/conversations/${conversationId}/messages`,
    { messages: batch.map((b) => ({ content: b.content, clientMessageId: b.clientMessageId })) },
  )
}
