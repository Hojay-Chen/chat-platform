import { indexLetter } from './avatar'

/**
 * 通讯录的分组与排序。
 *
 * <h2>为什么按 kind 分组, 而不是按首字母</h2>
 *
 * 微信的通讯录有两层: 顶层是「新的朋友 / 群聊 / 标签」这些**分类**, 分类里面才是
 * A-Z 的首字母索引。而一期的通讯录里只有一类人(仿真 Agent), 所以顶层分类就是全部
 * —— 再往下套一层首字母, 得到的是一组只有一项的分组。
 *
 * 更要紧的是**中文名没有首字母可用**: `lib/avatar.ts` 的 `indexLetter()` 把所有非 ASCII
 * 首字符都归到 `'#'`。这个平台的 Agent 名字几乎全是中文(晚晚、林夏), 于是首字母索引
 * 会渲染成一个孤零零的 `#` 按钮 —— 点了什么也不发生, 而它看起来像坏了。
 *
 * 所以一期的决定是: **排序用拼音序(`Intl.Collator`), 索引等真有跨字母的名字时再出现**。
 * `shouldShowIndex()` 就是那道闸: 字母种类少于两个就不画, 而不是画一个假的。
 * 二期有了好友列表、一屏装不下的时候, 这里才会需要真正的拼音首字母表。
 */

/** 中文按拼音排 —— `localeCompare` 的默认序是按码点, 「张」会排在「李」前面 */
const collator = new Intl.Collator('zh-Hans-CN', { sensitivity: 'base', numeric: true })

export function sortContacts<T extends { name: string }>(items: readonly T[]): T[] {
  return [...items].sort((a, b) => collator.compare(a.name ?? '', b.name ?? ''))
}

/**
 * 按名字过滤。空查询返回全部。
 *
 * 只按名字匹配, 不按关系类型/备注 —— 一期通讯录里能搜的东西只有名字, 加一个搜不到的
 * 字段等于给用户一个"明明有这个人却搜不出来"的困惑。
 */
export function filterContacts<T extends { name: string }>(items: readonly T[], query: string): T[] {
  const q = query.trim().toLowerCase()
  if (!q) return [...items]
  return items.filter((c) => (c.name ?? '').toLowerCase().includes(q))
}

/** 这批联系人一共占几个首字母 —— `AlphabetIndex` 的输入 */
export function contactLetters(items: readonly { name: string }[]): string[] {
  return [...new Set(items.map((c) => indexLetter(c.name ?? '')))].sort()
}

/**
 * 值不值得画那条索引。
 *
 * 一个字母的索引是**纯装饰**: 它只有一格, 点它不滚动(本来就在顶上), 却占掉屏幕
 * 右边一条竖带。少于两个字母时返回 false, 让调用方整个不渲染。
 */
export function shouldShowIndex(letters: readonly string[]): boolean {
  return letters.length >= 2
}
