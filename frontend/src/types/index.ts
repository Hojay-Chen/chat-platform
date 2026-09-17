/**
 * 会话对面的那个人「是什么」。
 *
 * 一期的 `PeerKind` 恒为 `'companion'` —— 今天每一个会话的对面都是一个数字人。
 * 之所以现在就把它写出来, 是因为二期会同时出现三种对面: 真人(`'user'`)、群(`'group'`),
 * 以及数字人(`'companion'`)。而 `companionId: string` 这个名字一旦长进组件签名里,
 * 二期就是一次全仓签名手术 —— 它出现在 URL 参数、`chatApplications.open()`、
 * `openEventStream()`、`ApplicationCardBubble` 的 props 和 7 个面板的 props 上。
 *
 * 所以新组件一律收 `PeerRef`, 只有 `api/` 那一层知道怎么把它翻译回具体路径段。
 */
export type PeerKind = 'companion' | 'user' | 'group' | 'system'

export interface PeerRef {
  kind: PeerKind
  id: string
  /** 显示名。列表行本来就有名字, 带上可以省一次查询; 缺了也不影响寻址 */
  name?: string
}

export interface User {
  id: string
  username: string
  email?: string | null
  nickname?: string | null
  timezone?: string | null
  birthDate?: string | null
  gender?: string | null
  createdAt?: string | null
}

export interface Place {
  country?: string
  province?: string
  city?: string
}

export interface PersonaTraits {
  warmth?: number
  maturity?: number
  independence?: number
  playfulness?: number
  curiosity?: number
  confidence?: number
  patience?: number
  sociability?: number
  emotionalSensitivity?: number
  rationality?: number
}

export interface PersonaBehavior {
  trigger?: string
  tendencies?: string[]
}

export interface PersonaLifeEvent {
  type?: string
  subtype?: string
  title?: string
  description?: string
  startTime?: string | null
  endTime?: string | null
  importance?: number
  emotionalSignificance?: number
}

export interface Persona {
  identity?: {
    name?: string
    gender?: string
    birthDate?: string
    nationality?: string
    timezone?: string
    birthPlace?: Place
  }
  relationship?: { type?: string }
  personality?: { traits?: PersonaTraits; summary?: string }
  communication?: {
    formality?: number
    verbosity?: number
    emojiUsage?: number
    teasing?: number
    initiative?: number
    directness?: number
    humor?: number
    style?: string
  }
  behaviors?: PersonaBehavior[]
  values?: string[]
  boundaries?: string[]
  life?: {
    background?: string
    events?: PersonaLifeEvent[]
    residences?: { city?: string; startDate?: string; endDate?: string | null }[]
  }
}

export interface Companion {
  id: string
  name: string
  /**
   * 账号ID —— 与 `name` 正交, 且这是**唯一**能区分两个同名 Agent 的东西。
   *
   * 名字是 LLM 从一句描述里生成的, 相似的描述会收敛到同一个名字: 用户的 9 个 Agent 里
   * 有 7 个都叫「小满」, 而这 7 个是 7 个不同的、活着的 Agent。没有账号ID 时, 聊天列表和
   * 通讯录里那 7 行**在界面上完全无法区分**。
   *
   * 可空是刻意的: 老数据是加列之前建的, 要等 `PersonHandleBackfill` 在启动时补号。
   * 界面必须接受 null(显示为空), 而不是崩或者显示 "undefined"。
   */
  handle?: string | null
  gender?: string | null
  age?: number | null
  birthDate?: string | null
  nextBirthday?: string | null
  birthPlace?: Place | null
  nationality?: string | null
  timezone?: string | null
  greeting?: string | null
  persona?: Persona | null
  relationshipType?: string | null
  relationshipStage?: string | null
  createdAt?: string | null
}

export interface LifeEvent {
  id: string
  type: string
  subtype?: string | null
  title: string
  description?: string | null
  startTime?: string | null
  endTime?: string | null
  importance: number
  emotionalSignificance: number
}

export interface Conversation {
  id: string
  userId: string
  companionId: string
  title: string
  startedAt: string
  lastMessageAt?: string | null
  messageCount: number
  summary?: string | null
  status?: string | null
}

export interface Message {
  id: string
  conversationId: string
  senderType: 'user' | 'companion' | 'system'
  /**
   * 这条消息**是谁**发的 —— `senderType` 只说"以什么身份", 这个说"哪一个"。
   *
   * 一期一对一里它和 `senderType` 是冗余的, 但群聊里不是: 一条 `senderType='user'`
   * 的消息, 作者可能是群里任何一个人。消息气泡靠它决定显不显示头像和昵称。
   *
   * **可以为 null**: 加这一列之前的历史消息没有这个值, 而 `ddl-auto: update`
   * 既不能给非空表加 NOT NULL 列, 也补不出老数据的值。
   */
  senderId?: string | null
  content: string
  /** 客户端幂等键(乐观消息 → canonical 消息的对应键) */
  clientMessageId?: string | null
  intent?: string | null
  emotion?: string | null
  topic?: string | null
  proactive?: boolean
  /** 会话模型归属 */
  sessionId?: string | null
  exchangeId?: string | null
  /** NORMAL/SHORT_ACK/PROACTIVE/FOLLOW_UP/SYSTEM/TOOL_RESULT/APPLICATION_CARD/APPLICATION_INVITATION */
  messageKind?: string | null
  /** DELIVERED/READ/RESPONDED/DEFERRED/IGNORED */
  deliveryStatus?: string | null
  /**
   * 结构化的附带信息 —— 有些消息的全部内容都在这里。
   *
   * `APPLICATION_CARD` 靠它带 applicationId/sessionId/name/role/status, 客户端据此把一条
   * 消息渲染成一张能点开的卡片而不是一段文字。不认识的 `messageKind` 会退回显示 `content`
   * —— 所以带 metadata 的消息, `content` 也必须是一句人能读的话, 不能是空串。
   */
  metadata?: Record<string, unknown> | null
  createdAt: string
}

export interface Memory {
  id: string
  type: 'episodic' | 'semantic' | 'shared'
  content: string
  summary?: string | null
  importance: number
  confidence: number
  emotionalWeight: number
  relationshipWeight: number
  retrievalCount: number
  lastRetrievedAt?: string | null
  occurredAt?: string | null
  status?: string
  sourceType?: string | null
  sourceId?: string | null
  createdAt: string
}

export interface UserFact {
  id: string
  predicate: string
  object?: string | null
  confidence: number
  sourceType?: string
  firstObservedAt?: string
  lastObservedAt?: string
}

export interface UserPreference {
  id: string
  category: string
  preference: string
  confidence: number
  sourceType?: string
}

export interface UserPattern {
  id: string
  pattern: string
  description?: string | null
  confidence: number
  evidenceCount: number
  evidence?: unknown[]
}

export interface UserHypothesis {
  id: string
  hypothesis: string
  description?: string | null
  confidence: number
  evidence?: unknown[]
}

export interface AgentState {
  mood?: string
  energy: number
  stress: number
  socialEnergy: number
  curiosity: number
  emotionalCloseness: number
  updatedAt?: string
}

export interface Relationship {
  id: string
  relationshipType?: string
  relationshipStage: string
  familiarity: number
  trust: number
  intimacy: number
  affection: number
  /** 多维关系 */
  tension?: number
  reciprocity?: number
  respect?: number
  connectionPressure?: number
  sharedExperienceCount: number
  messageCount: number
  lastInteractionAt?: string | null
  startedAt: string
}

export interface RelationshipEvent {
  id: string
  type: string
  title: string
  description?: string
  significance: number
  occurredAt: string
}

export interface SharedExperience {
  id: string
  type: string
  title: string
  description?: string
  importance: number
  occurredAt: string
}

export interface Reminder {
  id: string
  type: string
  title: string
  content?: string | null
  remindAt: string
  status: string
}

export interface Notification {
  id: string
  type: string
  title: string
  content?: string | null
  read: boolean
  createdAt: string
}

export interface ReflectionRecord {
  id: string
  type: string
  period: string
  summary?: string
  insights?: unknown[]
  createdAt: string
}

export interface MemorySourceMessage {
  sender: string
  content: string
  createdAt: string}

export interface MemoryLink {
  id: string
  fromMemoryId: string
  toMemoryId: string
  relation: string
  strength: number
}

/** P2: 用户常提的实体(长期指代) */
export interface MemoryEntity {
  id: string
  name: string
  type: string
  description?: string | null
  mentionCount: number
  salience: number
  lastContext?: string | null
  lastSeenAt: string
}

export interface PersonaVersion {
  id: string
  version: number
  active: boolean
  changeSource?: string
  changeReason?: string
  createdAt: string
}

export interface CompanionLife {
  currentActivity?: string
  currentLocation?: string
  dayPhase?: string
  todaySummary?: string
  todayActivities: LifeActivity[]
}

export interface LifeActivity {
  id: string
  type: string
  title: string
  description?: string
  plannedStart?: string
  plannedEnd?: string
  actualStart?: string
  actualEnd?: string
  status: string
  source?: string
}

export interface SelfModel {
  narrative?: string
  facts?: string[]
  preferences?: string[]
  concerns?: string[]
  plans?: string[]
  version?: number
}

export interface RelationshipNarrative {
  currentSummary?: string
  importantChapters?: unknown[]
  emotionalArc?: string[]
  sharedIdentity?: string
  version?: number
}

export interface RelationshipThread {
  id: string
  topic: string
  summary?: string
  status: string
  importance: number
  lastActivityAt: string
}

export interface OpenLoop {
  id: string
  ownerType?: string
  title: string
  description?: string
  status: string
  importance: number
  expectedResolutionAt?: string
  lastReferencedAt?: string
}
