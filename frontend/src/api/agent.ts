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

/**
 * 账号ID 的现状 + 改号配额。
 *
 * 字段名跟着后端 `HandleView` 走。`nextChangeAt` 只在额度用尽时非空 —— 界面上就是
 * "还能改 N 次" 与 "下次可改 YYYY-MM-DD" 两种状态, 互斥。
 */
export interface HandleView {
  handle: string | null
  /** 最近 365 天内已改次数 */
  used: number
  limit: number
  /** 还能改几次。界面显示这个, **不显示 used** —— 减法在每个调用点做, 总有人做反 */
  remaining: number
  nextChangeAt: string | null
}

/**
 * 读账号ID 与配额。设置页打开时调一次。
 *
 * 与 {@link getAgent} 分开是刻意的: 配额要查流水表, 而 `getAgent` 是通讯录每一行都会
 * 打的东西。把它塞进 `Companion` 等于每列一次通讯录就多算一遍配额。
 */
export function getHandle(companionId: string): Promise<HandleView> {
  return api.get<HandleView>(`${base(companionId)}/handle`)
}

/**
 * 改账号ID。
 *
 * 失败时抛的是 {@link ApiError}, 带 `status`:
 * `400` 形状不对 / `409` 被占用 / `429` 一年三次用完 —— 三种要给三句不同的话,
 * 而它们各自的 `hint` 里就写着该说的那句。所以调用方不要写自己的映射表,
 * 直接把 `hint` 显示出来。
 *
 * 大小写与前后空白由后端归一, 这里**不做**前端预校验: 一份前端副本就是一份会漂移的规则,
 * 而漂移的表现是"前端说可以, 后端说不行"。
 */
export function updateHandle(companionId: string, handle: string): Promise<HandleView> {
  return api.put<HandleView>(`${base(companionId)}/handle`, { handle })
}

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
