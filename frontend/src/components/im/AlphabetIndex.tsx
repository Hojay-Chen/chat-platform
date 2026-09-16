/**
 * 通讯录右侧的字母索引。
 *
 * 一条容易做错的性质: **只出现有人的字母, 不是 A–Z 全集**。
 * 画满 26 个字母看着整齐, 但用户点「Q」却什么也没发生 —— 那是骗人的可点击区域。
 * 真机上微信也是只显示有联系人的那些字母。
 */

export interface AlphabetIndexProps {
  /** 已排序的、实际存在的分组字母 */
  letters: string[]
  active?: string | null
  onPick?: (letter: string) => void
  className?: string
}

export function AlphabetIndex({ letters, active = null, onPick, className = '' }: AlphabetIndexProps) {
  if (letters.length === 0) return null

  return (
    <div className={`flex flex-col items-center justify-center gap-0.5 px-1 ${className}`}>
      {letters.map((l) => (
        <button
          key={l}
          type="button"
          onClick={onPick ? () => onPick(l) : undefined}
          className={`grid h-4 w-4 place-items-center rounded text-[10px] font-medium transition-colors ${
            l === active ? 'bg-accent text-accent-ink' : 'text-ink-faint hover:text-ink'
          }`}
        >
          {l}
        </button>
      ))}
    </div>
  )
}
