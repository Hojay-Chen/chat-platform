/**
 * 「名字 + 账号ID」—— 聊天列表行与通讯录行共用的那一小段。
 *
 * <h2>它解决的问题</h2>
 *
 * 2026-09-17 用户的聊天列表里有 7 行都叫「小满」。那 7 个是 7 个不同的、活着的 Agent ——
 * 名字是 LLM 从一句描述里生成的, 相近的描述反复收敛到同一个名字, 所以重名不是 bug,
 * 是名字这个概念的固有性质。真正缺的是"哪个是哪个"的答案。
 *
 * <p>账号ID 就是那个答案: 它唯一、由系统分配、可以改、可以报给别人 —— 现成的等价概念
 * 是微信号。两者正交: 名字回答"她叫什么", 账号ID 回答"是哪一个"。
 *
 * <h2>为什么挂在名字右边, 而不是副标题</h2>
 *
 * 因为**副标题在这两个列表里都已经有主了**:
 * <ul>
 *   <li>聊天列表的副标题是最后一条消息 —— 那是"最近发生了什么", 这一屏唯一的信息。</li>
 *   <li>通讯录的副标题被**刻意**留空(见 `Contacts.tsx` 那段"名字下面不写任何东西"):
 *       名字底下挂一行内容, 这一屏就变成第二个聊天列表了。</li>
 * </ul>
 *
 * 账号ID 两边都塞不进去, 而它本来也不属于"下面那一行" —— 它和名字是同一种东西(身份),
 * 不是名字的补充说明。所以它跟名字同一行。
 *
 * <h2>等宽 + 变暗</h2>
 *
 * `font-mono` 与 `tabular-nums` 是设计里给"技术标识"的固定待遇(见设计系统关于确定性的
 * 那一条): 账号ID 是要被念出来、照着敲的东西, 等宽让它逐字符可读, 也不会在列表刷新时
 * 因为字宽变化而左右抖。变暗是因为它是**标识**不是**称呼** —— 眼睛先看到名字, 需要区分
 * 时才落到这串字符上。
 */
export function NameWithHandle({ name, handle, className = '' }: {
  name: string
  /** 没有账号ID 时**整个不渲染** —— 不画一个空的灰块, 那看起来像加载失败 */
  handle?: string | null
  className?: string
}) {
  if (!handle) return <>{name}</>

  return (
    <span className={`flex items-baseline gap-1.5 ${className}`}>
      {/* min-w-0 是让 ellipsis 生效的那一半: flex 子项默认 min-width:auto,
          不加这行, 长名字会撑破父容器而不是被截断 */}
      <span className="min-w-0 truncate">{name}</span>
      <span className="shrink-0 font-mono text-[11px] font-normal text-ink-faint">{handle}</span>
    </span>
  )
}
