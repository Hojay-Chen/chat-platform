import type { ReactNode } from 'react'
import { HelpCircle } from 'lucide-react'

/**
 * 「?」—— 说明文字该待的地方。
 *
 * <h2>它解决的问题</h2>
 *
 * 应用那几屏里积了一批"作者写给读的人看"的段落: 为什么第三方应用必须不同源、为什么
 * 那张票的明文只出现一次、entry 模板里允许哪两个变量……这些话**没有一句是错的**,
 * 但它们是文档, 不是界面。把它们印在主流程里, 后果是每一屏的主干都被一段解释打断 ——
 * 用户的原话是这几屏「制作的太烂了」。
 *
 * 判定标准很简单: 一句话如果是"理解这个界面**为什么**这样"才需要的, 它就该在 `?` 里;
 * 如果是"知道**现在该按哪儿**"需要的, 它才留在屏幕上。
 *
 * <h2>为什么是原生 `<details>`</h2>
 *
 * 三件事一次拿到, 且都不需要 JS:
 * - 键盘能展开(`summary` 天然可聚焦)
 * - 读屏会念出"可展开", 并按 `aria-label` 报出这是什么
 * - 标记里带 `open` 属性 —— 上面那些用 `renderToStaticMarkup` 的断言能看见它,
 *   而一个靠 `onMouseEnter` 出现的浮层在静态渲染里等于不存在
 *
 * 样式在 `index.css` 的 `.hint` 一段里 —— 那里的选择器要够到 `[open]` 这个属性,
 * 写在类里比堆一串 arbitrary variant 好读。
 */
export function Hint({
  children,
  label = '说明',
  align = 'start',
}: {
  /** 展开后显示的那段话。它就是原来印在主流程里的那一段。 */
  children: ReactNode
  /** 读屏与 tooltip 上的名字 —— 「?」本身没有字, 所以这一句必须有。 */
  label?: string
  /** 靠右的元素上用它 —— 否则浮层会从容器右边溢出去。 */
  align?: 'start' | 'end'
}) {
  return (
    <details className={`hint ${align === 'end' ? 'hint-end' : ''}`}>
      <summary aria-label={label} title={label}>
        <HelpCircle size={11} />
      </summary>
      <div className="hint-body">{children}</div>
    </details>
  )
}

export default Hint
