import type { ConversationSummary } from '@/api/conversations'
import { ChatApplicationError } from '@/api/chatApplications'

/**
 * 分享这条链上**能被断言的那一半** —— §8/§9/§10 里所有不含渲染的规则。
 *
 * <h2>为什么这些规则值得单独一个文件、而不是写在 ShareSheet 里</h2>
 *
 * 它们全都是"错了不会报错"的那一类:
 *
 * <ul>
 *   <li>附言判宽了, 用户能往别人的消息流里灌一万字;</li>
 *   <li>候选人筛错了, 搜"Maya"搜不到 Maya, 而用户只会以为"这个平台搜不了";</li>
 *   <li>封面判宽了, 收件人的客户端会去拉一个第三方地址 —— 那是把**收到邀请的人**的 IP
 *       与阅读时间送给应用作者, 而这件事在他那一侧完全不可见。</li>
 * </ul>
 *
 * 本仓前端测试跑在 `environment: 'node'` 上(没有 jsdom), 组件渲染不起来 —— 所以这些判断
 * 必须住在纯函数里, 否则它们一条都保护不了。
 */

/** 附言的长度上限。 */
export const MAX_NOTE = 200

/**
 * 附言的形状 —— 裁剪、去控制字符、截断。
 *
 * <h2>为什么这件事必须在客户端做一次, 服务端再做一次</h2>
 *
 * 客户端这一次是为了**让人看得见**: 输入框下面那个计数器要与实际发出去的东西一致, 否则
 * 用户会打满 200 字、发出去发现被截了。服务端那一次才是权威(见
 * `ConversationApplicationService.sanitizeNote`) —— 因为调这个接口的不止这一个界面。
 * 两处都做不是重复: 一处是体验, 一处是边界。
 *
 * <p>控制字符被去掉而不是替换: 一个零宽控制字符在气泡里什么都看不见, 但它会让那段文本在
 * 日志与终端里表现异常 —— 一个只用来搞坏别人排查过程的字符没有保留的理由。
 * 换行是例外, 它是**正常输入**(用户在附言里分行), 保留。
 */
export function normalizeNote(raw: string | null | undefined): string {
  if (!raw) return ''
  return raw
    // 控制字符: C0 里除 \t(9) \n(10) \r(13) 之外的全部, 再加上 DEL(127)。
    // 写成 \u 转义而不是字面量 —— 字面量在这个文件里是看不见的, 而看不见的字符
    // 恰恰就是这段代码在删的东西。
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '')
    // 一段横向空白压成一个普通空格。用 \p{Zs}(需要 u 标志)而不是 " " —— 那一位字符集里
    // 还有不换行空格 U+00A0(手机键盘、网页复制粘贴里极常见)与全角空格 U+3000(中文输入法
    // 直接打得出来), 它们与普通空格在气泡里长得一模一样。\t 也要一起收: 它在 C0 里,
    // 不属于 Zs。**这一条必须与服务端 ConversationApplicationService.sanitizeNote 逐字一致**,
    // 否则输入框下面那个计数器就在对用户说谎。
    .replace(/[\t\p{Zs}]+/gu, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
    .slice(0, MAX_NOTE)
}

/** 还能再打几个字 —— 计数器用。已经超了就是 0, 不给负数。 */
export function noteRemaining(note: string): number {
  return Math.max(0, MAX_NOTE - note.length)
}

/**
 * 从应用来的分享意图里取出宿主真正要用的那几个字段。
 *
 * <h2>`coverUrl` 被收下, 但**不会**进消息</h2>
 *
 * 宿主接受它, 因为它对**发起分享的那个人**没有新增的隐私暴露 —— 那个应用此刻就跑在他的
 * 浏览器里, 它本来就看得见他的一切。但平台**不把它写进邀请消息的 metadata**: 那条消息会
 * 被发到**别人**的客户端上渲染, 而一个第三方图片地址一旦进了收件人的消息流, 收件人的 IP、
 * 打开时间、有没有看这条消息就全都回报给了应用作者 —— 而他与收件人之间没有任何关系。
 *
 * 所以收件人看到的那张封面是**平台自己画的**(见 `coverOf`)。这不是"暂时没做", 是一条
 * 要一直守着的规矩: 跨用户传播的内容, 它的每一个外部引用都必须由平台自己产生。
 *
 * <p>真正被带进消息的是 `title` 与 `description` —— 它们是**文字**, 而且会被裁剪与转义,
 * 一个应用无法用它们做超出"写一句话"的事。
 */
export interface NormalizedShare {
  title: string
  description: string
  /** 应用想让用户分享的那一场。为空 = 由宿主决定(通常是当前这一场)。 */
  sessionId: string | null
  /** 只服务于发起者这一侧的预览, **不进消息**。见上面那段。 */
  coverUrl: string | null
}

export function normalizeShare(input: Record<string, unknown>): NormalizedShare {
  const str = (v: unknown, max: number): string =>
    typeof v === 'string' ? v.replace(/\s+/g, ' ').trim().slice(0, max) : ''

  const coverUrl = typeof input.coverUrl === 'string' ? input.coverUrl.trim() : ''
  return {
    title: str(input.title, 60),
    description: str(input.description, 160),
    sessionId: typeof input.sessionId === 'string' && input.sessionId ? input.sessionId : null,
    coverUrl: isDisplayableCover(coverUrl) ? coverUrl : null,
  }
}

/**
 * 这张封面图**能不能在发起者自己这一侧显示**。
 *
 * <p>只放行 https 的绝对地址。`data:` 与 `blob:` 被拒的理由不是安全, 而是**它们没有意义**:
 * 一个 data URL 里嵌着的图片是那个应用自己画出来的, 而它就在页面里, 没必要多绕一圈;
 * 放行它们只会让"封面"这个字段变成一条可以塞任意体积内容的通路。
 *
 * <p>`http:` 被拒是因为这一页本身在 https 上 —— 浏览器会把它当混合内容拦掉, 于是表现为
 * 一张永远裂着的图。宁可退回平台自己画的那张。
 */
export function isDisplayableCover(url: string): boolean {
  if (!url || url.length > 2048) return false
  return url.startsWith('https://')
}

/**
 * 候选收件人 —— 搜索。
 *
 * <h2>匹配的是三个字段, 不是"名字"</h2>
 *
 * 会话行上能用来认人的东西有三个: 对方的名字、会话标题、以及最后一条消息的内容。前两个
 * 是显然的; 第三个是**微信的搜索行为**, 而它有用的场景很具体 —— 用户记得"上次聊到棋的那
 * 个人", 但不记得她叫什么。
 *
 * <p>空查询返回**全部**, 而不是空列表: 打开 ShareSheet 的第一眼必须是"我有哪些人",
 * 否则那一屏长得像"搜不到任何人"。
 *
 * <p>大小写不敏感, 且对方名字参与匹配是**首要**的 —— 所以结果按"名字命中优先"排, 而不是
 * 简单过滤。用户搜"maya"时, 一个名字里没有 maya 但最后一条消息里有 maya 的会话排在第一行,
 * 会让人觉得搜索是坏的。
 */
export function filterRecipients(
  conversations: readonly ConversationSummary[],
  query: string,
): ConversationSummary[] {
  const q = query.trim().toLowerCase()
  if (!q) return [...conversations]

  const scored: { row: ConversationSummary; rank: number }[] = []
  for (const row of conversations) {
    const name = row.peer.name?.toLowerCase() ?? ''
    const title = row.title?.toLowerCase() ?? ''
    const last = row.lastMessage?.content?.toLowerCase() ?? ''
    // 名次越小越靠前。名字命中永远优于标题命中, 标题优于内容。
    const rank = name.includes(q) ? 0 : title.includes(q) ? 1 : last.includes(q) ? 2 : -1
    if (rank >= 0) scored.push({ row, rank })
  }
  // 同档内保持调用方给的顺序(通常是最近说话的排前面) —— 排序稳定, 不做二次排序。
  return scored.sort((a, b) => a.rank - b.rank).map((s) => s.row)
}

/**
 * 把**服务端**的拒绝翻译成一句人话, 并说明下一步。
 *
 * <h2>为什么不能直接把后端的 message 显示出来</h2>
 *
 * 后端的措辞是给**调接口的人**看的("session 已结束" / "调用方不能铸造邀请")。同一句话在
 * 界面上会变成一句让人不知道该怎么办的提示 —— 而分享失败恰恰是用户最需要知道"接下来做什么"
 * 的时刻: 是重试一次, 还是去找房主再开一局?
 *
 * <p>认不出的码**原样显示**, 不吞掉。吞掉的话, 一次没预料到的失败会表现为"什么都没发生",
 * 而那是最难被报上来的 bug 形状。
 */
export function describeShareFailure(e: unknown): string {
  if (e instanceof ChatApplicationError) {
    switch (e.code) {
      case 'SESSION_NOT_FOUND':
      case 'APPLICATION_SESSION_NOT_FOUND':
        return '这一局已经不在了 —— 可能已经被结束。回应用里重开一局再分享。'
      case 'NOT_SESSION_OWNER':
      case 'NOT_PERMITTED':
        return '只有开这一局的人能邀请别人。'
      case 'INVITATION_EXHAUSTED':
        return '这一场的邀请名额用完了, 让开这一局的人再铸一张。'
      case 'SESSION_ENDED':
        return '这一局已经结束了, 不能再邀请人进来。'
      case 'APPLICATION_NOT_AVAILABLE':
        return '这个应用此刻不能开新会话, 所以也分享不出去。'
      default:
        return `${e.code} — ${e.message}`
    }
  }
  if (e instanceof Error) return e.message
  return String(e)
}

/**
 * 平台自己画的那张封面 —— §10 里那个「[ 游戏封面 ]」。
 *
 * <h2>为什么是画出来的, 而不是从应用拿一个地址</h2>
 *
 * 见 {@link normalizeShare} 那段。这里只说它**为什么够用**: 五种 Surface 里的应用可以没有
 * 图标、没有图床、甚至没有网络 —— 而一张封面存在的意义是"让收件人一眼看出这是哪个应用",
 * 首字 + 一个由名字定下来的颜色就能做到这件事, 且**对任何应用都成立**。真去要求一张图,
 * 结果是没图的应用在邀请里显示一个灰色方块。
 *
 * <p>颜色是从名字派生的哈希, 所以同一个应用在任何设备、任何时候都是同一个颜色 ——
 * 一个每次刷新都换颜色的封面等于没有封面。
 */
export function coverOf(name: string): { initial: string; hue: number } {
  const trimmed = (name ?? '').trim()
  const initial = trimmed.length > 0 ? [...trimmed][0] : '应'
  let hash = 0
  for (const ch of trimmed) {
    // 只要它对同一个输入稳定即可 —— 这里不需要抗碰撞, 只需要"同名字同颜色"。
    hash = (hash * 31 + ch.codePointAt(0)!) % 360
  }
  return { initial, hue: hash }
}
