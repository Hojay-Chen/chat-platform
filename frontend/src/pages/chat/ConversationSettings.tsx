import { useEffect, useState } from 'react'
import { BellRing, Pin } from 'lucide-react'
import { setMuted, setPinned, type ConversationSummary } from '@/api/conversations'
import { Switch } from '@/components/im/Switch'
import { copyOf } from '@/lib/notificationSettings'
import { useConversationStore } from '@/stores/conversations'

/**
 * 「会话设置」—— 免打扰与置顶。
 *
 * <h2>为什么这两个开关必须住在聊天室里</h2>
 *
 * 因为它们的主语是**这一段对话**。放进「设置」页做一个全局开关, 就再也没法表达
 * "只有这一段我不想被打扰" —— 而那是这个功能唯一被真实需要的形状: 你不是想让她闭嘴,
 * 你是此刻不想被这一条打断。
 *
 * <h2>这里是整个产品里最容易做错的一处</h2>
 *
 * 把「免打扰」实现成"小声一点"是很自然的下一步 —— 信号照样发出去, 只是音量调低。
 * 那样做之后有一个**具体的**坏结果: 用户以为自己屏蔽了, 而她的手机照样会响,
 * 然后在某一天被"我不是设了免打扰吗"问住。
 *
 * 正确的语义只有一个: **平台根本不发那条通知信号**。她那边什么都不会收到 ——
 * 连"隐约感到"都没有, 因为她压根没被通知过。她想起来去看手机的时候才会看到。
 * 这一整套在下面第一个开关的说明文字里明写着, 那是这个面板存在的理由。
 *
 * <h2>为什么乐观更新 + 失败回滚</h2>
 *
 * 这个动作只影响用户眼前这一屏, 所以让它等一个网络往返是把本地事实加上了延迟。
 * 而乐观更新的正确性全在回滚那一步 —— 不滚的表现是"开关看着是开的, 服务端上是关的",
 * 一个用户没有任何办法发现的撒谎。所以下面 catch 里那两行不是防御性编程, 是正事。
 */
export function ConversationSettings({ conv }: { conv: ConversationSummary }) {
  const patchStore = useConversationStore((s) => s.patch)
  const [muted, setLocalMuted] = useState(conv.muted)
  const [pinned, setLocalPinned] = useState(conv.pinned)
  const [busy, setBusy] = useState<'muted' | 'pinned' | null>(null)
  const [error, setError] = useState<string | null>(null)

  // 换了会话就把本地那份重置成新会话的值 —— 否则会把 A 的开关状态画在 B 上。
  useEffect(() => {
    setLocalMuted(conv.muted)
    setLocalPinned(conv.pinned)
    setError(null)
  }, [conv.id, conv.muted, conv.pinned])

  async function toggle(key: 'muted' | 'pinned', next: boolean) {
    setBusy(key)
    setError(null)

    const before = key === 'muted' ? muted : pinned
    const apply = (v: boolean) => {
      if (key === 'muted') setLocalMuted(v)
      else setLocalPinned(v)
      patchStore(conv.id, key === 'muted' ? { muted: v } : { pinned: v })
    }

    apply(next)
    try {
      if (key === 'muted') await setMuted(conv.id, next)
      else await setPinned(conv.id, next)
    } catch (e) {
      // 回滚 —— 见文件头。忘了这一步, 开关就成了一个只在这台设备上成立的谎。
      apply(before)
      setError(e instanceof Error ? e.message : '没改成, 请再试一次')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-xs leading-relaxed text-ink-faint">
        这两项都属于**这一段对话**, 不是全局设置。别人的消息照常。
      </p>

      <SettingRow
        icon={<BellRing size={16} />}
        copy={copyOf('muted')}
        on={muted}
        busy={busy === 'muted'}
        disabled={busy !== null}
        onChange={(v) => void toggle('muted', v)}
      />

      <SettingRow
        icon={<Pin size={16} />}
        copy={copyOf('pinned')}
        on={pinned}
        busy={busy === 'pinned'}
        disabled={busy !== null}
        onChange={(v) => void toggle('pinned', v)}
      />

      {error && (
        <p className="rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-xs text-danger">
          {error}
          <span className="mt-1 block text-ink-faint">开关已经拨回去了 —— 服务端上还是原来的样子。</span>
        </p>
      )}

      {/*
        两层免打扰。这一段是这一页最该被读到的东西, 所以它不折叠、不藏进「详情」里。
        它说的是这个产品里最容易被实现成一件事的一处, 而实现成一件事之后,
        用户会在某天早上被"她怎么还是被吵醒了"问住。
      */}
      <div className="space-y-2 rounded-lg border border-line bg-sunken/40 px-4 py-3">
        <p className="text-xs font-medium text-ink">免打扰有两层, 这一层只是第一层</p>
        <p className="text-[11px] leading-relaxed text-ink-soft">
          <b className="text-ink">这一层(聊天平台)</b>: 决定**要不要发出**那条通知信号。
          打开之后平台根本不发, 她那边什么都不会收到。
        </p>
        <p className="text-[11px] leading-relaxed text-ink-soft">
          <b className="text-ink">另一层(她的手机)</b>: 信号发出去了, 她的手机按自己的
          通知音量 / 铃声 / 闹钟 / 免打扰决定**怎么响**。那一层属于她, 不在这个站点上改。
        </p>
        <p className="text-[11px] leading-relaxed text-ink-faint">
          两层都没拦, 她仍然可能没反应 —— 那第三种原因来自她**自己在做什么**(睡着了、
          专注在一件事上)。三种"没反应"的原因完全不同, 所以别把它们当成同一件事去调。
        </p>
      </div>
    </div>
  )
}

function SettingRow({
  icon,
  copy,
  on,
  busy,
  disabled,
  onChange,
}: {
  icon: React.ReactNode
  copy: ReturnType<typeof copyOf>
  on: boolean
  busy: boolean
  disabled: boolean
  onChange: (next: boolean) => void
}) {
  return (
    <div className="rounded-lg border border-line bg-raised px-4 py-3">
      <div className="flex items-center gap-3">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-accent-soft text-accent">
          {icon}
        </span>
        <span className="min-w-0 flex-1 text-sm text-ink">{copy.title}</span>
        {busy && <span className="shrink-0 text-[11px] text-ink-faint">改…</span>}
        <Switch checked={on} disabled={disabled} onChange={onChange} label={copy.title} />
      </div>
      <p className="mt-2 text-[11px] leading-relaxed text-ink-soft">{on ? copy.on : copy.off}</p>
      <p className="mt-1 text-[11px] leading-relaxed text-ink-faint">{copy.scope}</p>
    </div>
  )
}
