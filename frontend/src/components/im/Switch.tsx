/**
 * 一个开关。
 *
 * <h2>为什么是 `role="switch"` 而不是 `role="checkbox"`</h2>
 *
 * 这两者在屏幕阅读器里报的是不同的话: checkbox 报"已勾选/未勾选", switch 报
 * "开/关"。而这两个开关切换的是**一件一直在生效的事**(她会不会被通知到), 不是一个
 * 被选中的条目 —— "开"才是这件事真正的语汇。
 *
 * <h2>为什么不用 `<input type="checkbox">` 再美化</h2>
 *
 * 因为那份美化的 CSS 会长到需要自己处理 focus ring、处理 `:checked` 的相邻选择器、
 * 处理不同浏览器对 appearance 的默认值 —— 而这三样里任何一样漏了, 症状都是"键盘用户
 * 看不出焦点在哪"。用一个 `<button role="switch">` 拿到的是一份浏览器原生就对的语义。
 *
 * <h2>轨道是两边不同色的</h2>
 *
 * 关着的时候轨道画成 sunken(凹), 开着画成 accent。只用滑块位置区分开关状态在
 * 小尺寸上是看不出来的 —— 而这一屏上"我到底开没开"就是全部信息。
 *
 * <h2>为什么滑块与它的行程都是任意值</h2>
 *
 * `h-[1.125rem]` / `left-[1.625rem]` 都是尺寸表里没有的数: 轨道 44×24, 滑块 18×18,
 * 行程 `44 - 18 - 2 = 24px` 的两端各留 2px。写成 `h-4`(16)或 `h-5`(20)会让滑块要么
 * 四周漏光要么顶到轨道边 —— 而漏光那种错在小尺寸上看起来只是"有点歪", 没人会去查。
 * 行程与槽宽必须是**算出来的**, 不是挑一个近似的档位。
 */
export function Switch({
  checked,
  onChange,
  disabled = false,
  label,
}: {
  checked: boolean
  onChange: (next: boolean) => void
  disabled?: boolean
  /** 无障碍名。开关旁边那行字是视觉标签, 这个才是读屏听到的。 */
  label: string
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`relative h-6 w-11 shrink-0 rounded-full border transition-colors
                  ${checked ? 'border-accent bg-accent' : 'border-line-strong bg-sunken'}
                  disabled:opacity-40 disabled:cursor-not-allowed`}
    >
      <span
        aria-hidden="true"
        className={`absolute top-0.5 h-[1.125rem] w-[1.125rem] rounded-full bg-raised shadow-sm transition-[left]
                    ${checked ? 'left-[1.625rem]' : 'left-0.5'}`}
      />
    </button>
  )
}
