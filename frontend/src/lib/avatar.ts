/**
 * 头像的确定性派生 —— 没有上传图片时, 界面上仍然要有一个稳定的、认得出来的方块。
 *
 * "确定性"是这里唯一的硬要求: 同一个人在会话列表、通讯录、消息气泡里必须永远是
 * 同一个颜色和同一个字。用随机色或递增计数器都会让头像在每次渲染时变脸。
 *
 * 选色板而不是全色相环: 8 个固定色比哈希出来的任意 HSL 更容易凑成一套看起来
 * 像"设计过"的东西 —— 后者总会出现几个脏色。
 */

/** 8 色板。都是中饱和度的实色 + 白字, 因此在亮色和暗色底上都成立 —— 不需要按主题分两套 */
const PALETTE = [
  '#3B82F6', // 蓝
  '#8B5CF6', // 紫
  '#EC4899', // 粉
  '#F97316', // 橙
  '#10B981', // 绿
  '#06B6D4', // 青
  '#F59E0B', // 琥珀
  '#6366F1', // 靛
] as const

/**
 * 稳定哈希。FNV-1a 的 32 位版本 —— 用它而不是 `seed.length` 之类,
 * 是因为会话 id 常常是 UUID, 只取长度会让绝大多数 id 撞进同一个颜色。
 */
function hash(seed: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/** 无头像时那个方块的颜色 */
export function avatarColor(seed: string): string {
  if (!seed) return PALETTE[7] // 空 seed 给靛蓝 —— 一个"中性"的兜底, 不是随机
  return PALETTE[hash(seed) % PALETTE.length]
}

/**
 * 无头像时那个方块里的字。
 *
 * 中文取第一个字, 拉丁字母取首字母并大写 —— 混排时按首字符判定, 不做语言检测。
 * 空名字给 `?` 而不是空串: 一个纯色空方块看起来像加载失败。
 */
export function initials(name: string): string {
  const t = (name ?? '').trim()
  if (!t) return '?'
  const first = Array.from(t)[0]
  return /[a-z]/.test(first) ? first.toUpperCase() : first
}

/**
 * 群头像画几个格子。微信那套: 最多九宫格, 多出来的成员不画。
 * 取前 9 个而不是随机 9 个 —— 随机会让群头像每次刷新都不一样, 失去辨识作用。
 */
export function groupCellCount(memberCount: number): number {
  if (memberCount <= 0) return 0
  return Math.min(memberCount, 9)
}

/**
 * 群头像的列数。
 * 1 人整格、2 人左右并排、3~4 人 2x2、5 人以上 3x3 —— 与微信一致。
 */
export function groupGridCols(memberCount: number): 1 | 2 | 3 {
  if (memberCount <= 1) return 1
  if (memberCount <= 4) return 2
  return 3
}

/** 通讯录字母索引用的分组键 —— 非 ASCII 一律归到 `#` */
export function indexLetter(name: string): string {
  const t = (name ?? '').trim()
  if (!t) return '#'
  const first = Array.from(t)[0].toUpperCase()
  return /[A-Z]/.test(first) ? first : '#'
}
