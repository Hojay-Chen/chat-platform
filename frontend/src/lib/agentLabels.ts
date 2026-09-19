/**
 * 后端说英文、界面说中文 —— 全站的对照表都在这一个文件里。
 *
 * <h2>为什么集中在一个文件</h2>
 *
 * 这些映射散落在老 `Chat.tsx` 里时, 它们藏在组件函数体内 —— 于是既测不到, 又没人
 * 知道到底有几套。集中起来之后有一个副作用比可测性更重要: **哪个字段还没有中文名
 * 一眼可见**。下面每一个 `?? 原值` 的兜底都是刻意的: 后端加一个新枚举值时, 界面显示
 * `weekly_digest` 而不是空白, 而空白会让人以为那条数据是坏的。
 *
 * 未知值**不抛错、不吞掉**, 原样显示 —— 这是这份界面里所有 `Record<string,string>`
 * 对照表的统一约定。
 *
 * <h2>后半段是应用域(LAP)的</h2>
 *
 * 它原先只覆盖伴侣域, 于是应用那几屏(会话面板、应用详情、容器预览)直接把
 * `OWNER` / `ACTIVE` / `EXECUTE` / `FULL_PAGE` 印在了中文句子中间 —— 一句话里两种语言
 * 混着, 是那几屏"看着没做完"的一半原因。搬到这里来而不是在页面里就地写一张表:
 * 同样的 `status` 在会话、参与者、邀请三处各有一套取值, 就地写三张表迟早会不一致。
 */

// ── 记忆 ────────────────────────────────────────────────────────────

export const MEMORY_TYPE_ZH: Record<string, string> = {
  episodic: '经历',
  semantic: '认知',
  shared: '共同',
}

export function memoryTypeZh(type: string): string {
  return MEMORY_TYPE_ZH[type] ?? type
}

// ── 关系阶段 ────────────────────────────────────────────────────────

export const RELATIONSHIP_STAGE_ZH: Record<string, string> = {
  new: '初识',
  familiar: '熟络',
  close: '亲密',
  deeply_connected: '深深相连',
}

/**
 * 老代码写的是 `STAGE_ZH[rel.relationshipStage]`, 取不到就是 `undefined` ——
 * 资料页最显眼的那一行会变成空白, 看起来像关系数据丢了。这里兜底成原值。
 */
export function stageZh(stage: string | null | undefined): string {
  if (!stage) return '初识'
  return RELATIONSHIP_STAGE_ZH[stage] ?? stage
}

// ── 用户模型的事实谓词 ───────────────────────────────────────────────

const PREDICATE_ZH: Record<string, string> = {
  likes: '喜欢',
  prefers: '更喜欢',
  dislikes: '不喜欢',
  works_as: '工作是',
  lives_in: '住在',
  has_pet: '养了',
  is_learning: '在学',
}

/**
 * `用户 + 谓词 + 宾语`。谓词认不出时**返回原词**而不是空串 ——
 * "用户 用户 咖啡" 至少还读得懂主谓宾, "用户  咖啡" 只是坏掉了。
 * 所以拼句子的调用方要在谓词两侧各留一个空格的位置, 见 `UserModelPanel`。
 */
export function zhPredicate(predicate: string): string {
  return PREDICATE_ZH[predicate] ?? predicate
}

export function factLabel(predicate: string, object: string | null | undefined): string {
  return `用户${zhPredicate(predicate)}${object ?? ''}`
}

// ── 提醒 / 通知 ─────────────────────────────────────────────────────

export const REMINDER_TYPE_ZH: Record<string, string> = {
  birthday: '生日',
  user_set: '自定义',
  anniversary: '纪念日',
  follow_up: '跟进',
}

export function reminderTypeZh(type: string): string {
  return REMINDER_TYPE_ZH[type] ?? type
}

export const NOTIFICATION_TYPE_ZH: Record<string, string> = {
  proactive: '主动找你',
  birthday: '生日',
  reminder: '提醒',
  relationship: '关系',
  system: '系统',
}

export function notificationTypeZh(type: string): string {
  return NOTIFICATION_TYPE_ZH[type] ?? type
}

// ── 人格特质 ────────────────────────────────────────────────────────

export const TRAIT_ZH: Record<string, string> = {
  warmth: '温柔',
  maturity: '成熟',
  independence: '独立',
  playfulness: '活泼',
  curiosity: '好奇',
  confidence: '自信',
  patience: '耐心',
  sociability: '外向',
  emotionalSensitivity: '敏感',
  rationality: '理性',
}

export function traitZh(key: string): string {
  return TRAIT_ZH[key] ?? key
}

// ── 人格版本来源 ────────────────────────────────────────────────────

const CHANGE_SOURCE_ZH: Record<string, string> = {
  evolution: '自动演化',
  user: '用户设定',
  initial: '初次生成',
}

export function changeSourceZh(source: string | null | undefined): string {
  if (!source) return ''
  return CHANGE_SOURCE_ZH[source] ?? source
}

// ── 应用域: 参与者、邀请、会话 ───────────────────────────────────────
//
// 取值来自后端契约(`contracts/application/PrincipalType.java`,
// `contracts/chat/ParticipantView.java` 等), 不是照界面猜的。

/** 这一位是谁。`EXTERNAL_AGENT` 与 `AGENT` 的区别是"平台托管的还是在平台外的"。 */
const PRINCIPAL_TYPE_ZH: Record<string, string> = {
  HUMAN: '真人',
  AGENT: '数字人',
  EXTERNAL_AGENT: '外来程序',
  SYSTEM: '平台',
  APPLICATION: '应用',
}

export function principalTypeZh(type: string | null | undefined): string {
  if (!type) return ''
  return PRINCIPAL_TYPE_ZH[type] ?? type
}

/** 在这一场里的身份。决定默认能做什么, 所以它该被读成人话。 */
const ROLE_ZH: Record<string, string> = {
  OWNER: '主人',
  MEMBER: '成员',
  PARTICIPANT: '成员',
  OBSERVER: '旁观',
}

export function roleZh(role: string | null | undefined): string {
  if (!role) return ''
  return ROLE_ZH[role] ?? role
}

/** 参与者的状态。`LEFT` 是人自己走的, `REMOVED` 是被请出去的 —— 两件事不一样。 */
const PARTICIPANT_STATUS_ZH: Record<string, string> = {
  ACTIVE: '在场',
  LEFT: '已离开',
  REMOVED: '已被请出',
}

export function participantStatusZh(status: string | null | undefined): string {
  if (!status) return ''
  return PARTICIPANT_STATUS_ZH[status] ?? status
}

/** 邀请的状态。`CONSUMED` 是"被用掉了", `REVOKED` 是"被主人收回了"。 */
const INVITATION_STATUS_ZH: Record<string, string> = {
  CREATED: '还没人用',
  CONSUMED: '已用掉',
  EXPIRED: '已过期',
  REVOKED: '已收回',
}

export function invitationStatusZh(status: string | null | undefined): string {
  if (!status) return ''
  return INVITATION_STATUS_ZH[status] ?? status
}

/** 会话的状态。 */
const SESSION_STATUS_ZH: Record<string, string> = {
  ACTIVE: '进行中',
  ENDED: '已结束',
  EXPIRED: '已过期',
  PENDING: '等待中',
}

export function sessionStatusZh(status: string | null | undefined): string {
  if (!status) return ''
  return SESSION_STATUS_ZH[status] ?? status
}

// ── 应用域: 应用自己的清单 ──────────────────────────────────────────

/** §18 的三种界面模式 —— 应用这一侧的界面是平台画的、远端画的、还是原生客户端画的。 */
const UI_MODE_ZH: Record<string, string> = {
  EMBEDDED: '平台内置',
  REMOTE: '应用自己提供',
  NATIVE: '原生客户端',
}

export function uiModeZh(mode: string | null | undefined): string {
  if (!mode) return ''
  return UI_MODE_ZH[mode] ?? mode
}

/** §67 的五种呈现方式。用户在界面上读到的是这五个词, 不是五个枚举值。 */
const SURFACE_TYPE_ZH: Record<string, string> = {
  FULL_PAGE: '整页',
  EMBEDDED: '嵌在页面里',
  MODAL: '弹出窗口',
  PANEL: '侧边栏',
  INLINE: '一行',
}

export function surfaceTypeZh(type: string | null | undefined): string {
  if (!type) return ''
  return SURFACE_TYPE_ZH[type] ?? type
}

/** 动作要求的权限档。`EXECUTE` 是"它真的会去改东西", 这一档值得被看见。 */
const PERMISSION_LEVEL_ZH: Record<string, string> = {
  READ: '只读',
  WRITE: '可写入',
  EXECUTE: '可执行',
}

export function permissionLevelZh(level: string | null | undefined): string {
  if (!level) return ''
  return PERMISSION_LEVEL_ZH[level] ?? level
}

/** 动作的风险档。名字本身就是"要不要拦一下"的答案。 */
const RISK_LEVEL_ZH: Record<string, string> = {
  NONE: '无风险',
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  CRITICAL: '极高',
}

export function riskLevelZh(level: string | null | undefined): string {
  if (!level) return ''
  return RISK_LEVEL_ZH[level] ?? level
}
