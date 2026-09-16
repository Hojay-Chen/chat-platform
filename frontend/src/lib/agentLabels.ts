/**
 * Agent 域里那些「后端说英文、界面说中文」的对照表。
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
  proactive: '她主动',
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
