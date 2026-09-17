import type {
  AgentState,
  Companion,
  CompanionLife,
  LifeEvent,
  Memory,
  MemoryEntity,
  MemoryLink,
  MemorySourceMessage,
  Notification,
  OpenLoop,
  Persona,
  PersonaVersion,
  ReflectionRecord,
  Relationship,
  RelationshipEvent,
  RelationshipNarrative,
  RelationshipThread,
  Reminder,
  SelfModel,
  SharedExperience,
  UserFact,
  UserHypothesis,
  UserPattern,
  UserPreference,
} from '@/types'
import { api } from './client'

/**
 * Agent 域的全部端点 —— 资料页、设置页、提醒页、通知页都从这里取。
 *
 * <h2>为什么要有这一层</h2>
 *
 * 这些路径在老 `Chat.tsx` 里是散在 7 个组件里的模板字符串: 同一个
 * `/api/companions/${companionId}/relationship` 在面板里出现一次, 在设置页里又拼一次。
 * 于是"改一个路径"意味着全仓 grep, 而漏掉一处**不会编译报错** —— 它只会在运行时
 * 404, 且只在用户点进那一个面板时才 404。
 *
 * 集中到这一个文件后, 路径只写一遍。三期「Agent 即账号」时这里会换成
 * `/api/agents/{accountId}/...`, 而所有调用方一个字都不用改 —— 这是这一层存在的
 * 真正理由, 不是为了少写几行。
 *
 * <h2>路径前缀的硬约束</h2>
 *
 * 全部挂在 `/api/companions/{id}/**` 下。8081 有一个兜底代理
 * (`CompanionDomainProxyController`) 把这一整段的**未实现**路径转给 8091, 所以这些
 * 端点实际由谁实现取决于 8081 自己实现了什么 —— 判据是 Spring 的 HandlerMapping
 * 优先级, 不是路径段。**新端点绝不能加在这一段下面**(会被代理吃掉), 要走
 * `/api/conversations/**` 那样的顶层新面。这条约束在 `/api/conversations` 那边也写了
 * 一遍, 因为它是这两个仓之间最容易踩的一脚。
 */

const base = (companionId: string) => `/api/companions/${companionId}`

// ── 基本资料 ────────────────────────────────────────────────────────

export function getAgent(companionId: string): Promise<Companion> {
  return api.get<Companion>(base(companionId))
}

export function removeAgent(companionId: string): Promise<void> {
  return api.del<void>(base(companionId))
}

/**
 * 开一段会话(或复用已有的那一段)。
 *
 * 这是 8091 的端点, 由 8081 在服务端转发。**它的存在决定了资料页上那个「发消息」
 * 按钮能不能工作**: 刚创建的 Agent 一段会话都没有, 而聊天室是按 conversationId
 * 寻址的, 所以必须先拿到一段会话才能进去。
 *
 * 返回值只取 `id` —— 8091 回的是完整的 `ConversationView`, 但这里只需要那一段的
 * 地址, 多声明字段就等于多了一份会过期的契约。
 */
export async function openConversation(companionId: string): Promise<string> {
  const conv = await api.post<{ id: string }>(`${base(companionId)}/conversations/first`, {})
  return conv.id
}

// ── 「最近」那一栏 ──────────────────────────────────────────────────

export function getLife(companionId: string): Promise<CompanionLife> {
  return api.get<CompanionLife>(`${base(companionId)}/life`)
}

export function getSelfModel(companionId: string): Promise<SelfModel> {
  return api.get<SelfModel>(`${base(companionId)}/self`)
}

export function getNarrative(companionId: string): Promise<RelationshipNarrative> {
  return api.get<RelationshipNarrative>(`${base(companionId)}/relationship/narrative`)
}

export function getThreads(companionId: string): Promise<RelationshipThread[]> {
  return api.get<RelationshipThread[]>(`${base(companionId)}/relationship/threads`)
}

export function getOpenLoops(companionId: string): Promise<OpenLoop[]> {
  return api.get<OpenLoop[]>(`${base(companionId)}/open-loops`)
}

// ── 记忆 ────────────────────────────────────────────────────────────

export function listMemories(companionId: string): Promise<Memory[]> {
  return api.get<Memory[]>(`${base(companionId)}/memories`)
}

export function searchMemories(companionId: string, q: string): Promise<Memory[]> {
  return api.get<Memory[]>(`${base(companionId)}/memories/search?q=${encodeURIComponent(q)}`)
}

export function listMemoryEntities(companionId: string): Promise<MemoryEntity[]> {
  return api.get<MemoryEntity[]>(`${base(companionId)}/memories/entities`)
}

export function forgetMemory(companionId: string, memoryId: string): Promise<void> {
  return api.del<void>(`${base(companionId)}/memories/${memoryId}`)
}

export function clearMemories(companionId: string): Promise<void> {
  return api.del<void>(`${base(companionId)}/memories`)
}

/** 「为什么」—— 这条记忆是从哪几句对话里来的 */
export function memorySource(
  companionId: string,
  memoryId: string,
): Promise<{ memory: Memory; source: MemorySourceMessage[] }> {
  return api.get(`${base(companionId)}/memories/${memoryId}/source`)
}

export function memoryGraph(
  companionId: string,
): Promise<{ nodes: Memory[]; links: MemoryLink[] }> {
  return api.get(`${base(companionId)}/memories/graph`)
}

export function exportMemories(companionId: string): Promise<unknown> {
  return api.get(`${base(companionId)}/memories/export`)
}

// ── 用户模型(「它了解的」) ───────────────────────────────────────────

export function listUserFacts(companionId: string): Promise<UserFact[]> {
  return api.get<UserFact[]>(`${base(companionId)}/user-model/facts`)
}

export function listUserPreferences(companionId: string): Promise<UserPreference[]> {
  return api.get<UserPreference[]>(`${base(companionId)}/user-model/preferences`)
}

export function listUserPatterns(companionId: string): Promise<UserPattern[]> {
  return api.get<UserPattern[]>(`${base(companionId)}/user-model/patterns`)
}

export function listUserHypotheses(companionId: string): Promise<UserHypothesis[]> {
  return api.get<UserHypothesis[]>(`${base(companionId)}/user-model/hypotheses`)
}

export function clearUserModel(companionId: string): Promise<void> {
  return api.del<void>(`${base(companionId)}/user-model/clear`)
}

// ── 关系 ────────────────────────────────────────────────────────────

export interface RelationshipBundle {
  /** 后端可能还没有为这个 Agent 建立关系记录 —— 老面板把它当必然存在, 于是那一刻整页崩掉 */
  relationship: Relationship | null
  events: RelationshipEvent[]
  sharedExperiences: SharedExperience[]
  state: AgentState | null
}

export function getRelationship(companionId: string): Promise<RelationshipBundle> {
  return api.get<RelationshipBundle>(`${base(companionId)}/relationship`)
}

// ── 提醒 ────────────────────────────────────────────────────────────

export function listReminders(companionId: string): Promise<Reminder[]> {
  return api.get<Reminder[]>(`${base(companionId)}/reminders`)
}

export function createReminder(
  companionId: string,
  input: { title: string; content?: string; remindAt: string },
): Promise<Reminder> {
  return api.post<Reminder>(`${base(companionId)}/reminders`, {
    type: 'user_set',
    title: input.title,
    content: input.content,
    remindAt: input.remindAt,
  })
}

export function completeReminder(companionId: string, reminderId: string): Promise<void> {
  return api.put<void>(`${base(companionId)}/reminders/${reminderId}/done`)
}

// ── 通知 ────────────────────────────────────────────────────────────

export function listNotifications(companionId: string): Promise<Notification[]> {
  return api.get<Notification[]>(`${base(companionId)}/notifications`)
}

export function readAllNotifications(companionId: string): Promise<void> {
  return api.put<void>(`${base(companionId)}/notifications/read-all`)
}

// ── 设置页 ──────────────────────────────────────────────────────────

export function listLifeEvents(companionId: string): Promise<LifeEvent[]> {
  return api.get<LifeEvent[]>(`${base(companionId)}/life-events`)
}

export function listReflections(companionId: string): Promise<ReflectionRecord[]> {
  return api.get<ReflectionRecord[]>(`${base(companionId)}/reflections`)
}

export function listPersonaVersions(companionId: string): Promise<PersonaVersion[]> {
  return api.get<PersonaVersion[]>(`${base(companionId)}/persona/versions`)
}

export function updatePersona(
  companionId: string,
  description: string,
  reason = '用户在设置里重新描述',
): Promise<void> {
  return api.put<void>(`${base(companionId)}/persona`, { description, reason })
}

// ── 账号ID ──────────────────────────────────────────────────────────

/*
 * 这里**没有** `getHandle` / `updateHandle`, 是删掉的, 不是漏了。
 *
 * 读: Agent 的账号ID 就在 `Companion.handle` 里 —— 它是通讯录每一行都在传的字段。
 * 原先设置页为它多打的那一次 `GET /api/companions/{id}/handle` 换来的只是同一个值。
 *
 * 写: **这个操作不存在**。Agent 的聊天账号ID 由系统分配、永久不变; 服务端的
 * `PersonService.changeHandle` 用类型闸门把它做成了 403, 8091 那两个端点也一并删了
 * (见 CompanionController 里那段注释)。所以这里连类型都不声明 —— 让界面**没有机会**
 * 画出一个必然失败的按钮, 或者一句"今年还剩 3 次修改机会"(配额对 Agent 是死值)。
 *
 * 真人自己的账号ID 可以改(每年三次, 滑动窗口), 那套读写与配额在 `api/person.ts`,
 * 页面在 `pages/me/Handle.tsx`。
 */

// ── 创建流程 ────────────────────────────────────────────────────────

export function compilePersona(description: string): Promise<{ persona: Persona; preview: string }> {
  return api.post<{ persona: Persona; preview: string }>('/api/companions/compile', { description })
}

export function previewPersona(
  persona: Persona,
  scenario: string,
): Promise<{ response: string }> {
  return api.post<{ response: string }>('/api/companions/preview', { persona, scenario })
}

export function createAgent(input: {
  persona: Persona
  relationshipType?: string
}): Promise<Companion> {
  return api.post<Companion>('/api/companions', input)
}
