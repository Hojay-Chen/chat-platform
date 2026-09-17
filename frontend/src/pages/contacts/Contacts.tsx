import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, UserPlus, Users, UsersRound } from 'lucide-react'
import { AlphabetIndex } from '@/components/im/AlphabetIndex'
import { Avatar } from '@/components/im/Avatar'
import { EmptyState, SearchBar } from '@/components/im/EmptyState'
import { ListRow, SectionHeader } from '@/components/im/ListRow'
import { NameWithHandle } from '@/components/im/NameWithHandle'
import { contactLetters, filterContacts, shouldShowIndex, sortContacts } from '@/lib/contacts'
import { useCompanionStore } from '@/stores/companion'

/**
 * 通讯录 —— 微信四 tab 里的第二格。
 *
 * <h2>它和「聊天」是同一份数据的两面</h2>
 *
 * 聊天 tab 按**时间**排(最近说过话的在上), 通讯录按**人**排(名字拼音序)。
 * 两个 tab 都能点进同一段对话, 只是问的问题不同: "最近发生了什么事" vs "都有谁"。
 * 这也是为什么通讯录里不显示未读数 —— 那是"最近"的属性, 归聊天 tab。
 *
 * <h2>名字下面不写任何东西</h2>
 *
 * 这里曾经是 `subtitle={c.greeting || '仿真 Agent'}` —— 一句她的问候语。用户看完说:
 * 「这个界面不需要展示最新聊天内容, 而是展示好友名称即可」。
 *
 * 那句话是对的, 而且理由比"简洁"更硬: 通讯录回答的是"**都有谁**"。一旦名字底下挂了
 * 一行内容, 这一屏就变成了第二个聊天列表 —— 而它的排序是拼音序, 于是同一行文字在
 * 这里和聊天 tab 里处于两个不同的位置, 两屏之间就没有互认的锚点了。微信的通讯录
 * 也只有名字, 是同一个道理。
 *
 * 那句 `|| '仿真 Agent'` 更是把两类信息混成了一行: 有问候语的 agent 显示问候语,
 * 没有的显示类型名 —— 于是"底下这行是什么"取决于这个人有没有写问候语。删掉它之后
 * 这个问题就不存在了。
 *
 * <h2>一期这里只有一类人</h2>
 *
 * 仿真 Agent。二期会有好友与群聊, 那时这一屏才需要真正的分组(「新的朋友 / 群聊 / 好友」)。
 * 今天硬造那三个分组是错的形状 —— 三个永远空着的分组比一个装满的分组糟糕得多。
 * 底下 `sections` 是个数组而不是写死的一段 JSX, 就是为了那一天不用重写渲染。
 */
export default function Contacts() {
  const navigate = useNavigate()
  const companions = useCompanionStore((s) => s.companions)
  const loading = useCompanionStore((s) => s.loading)
  const load = useCompanionStore((s) => s.load)

  const [keyword, setKeyword] = useState('')
  const [menuOpen, setMenuOpen] = useState(false)

  useEffect(() => {
    load()
  }, [load])

  const shown = useMemo(
    () => sortContacts(filterContacts(companions, keyword)),
    [companions, keyword],
  )
  const letters = useMemo(() => contactLetters(shown), [shown])

  return (
    <div className="relative mx-auto w-full max-w-[520px]">
      <header className="sticky top-0 z-10 border-b border-line bg-surface/85 backdrop-blur">
        <div className="flex items-center gap-2 px-4 pb-1 pt-3">
          <h1 className="min-w-0 flex-1 text-lg font-semibold tracking-tight text-ink">通讯录</h1>
          <button
            type="button"
            onClick={() => setMenuOpen(true)}
            title="添加"
            className="grid h-8 w-8 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
          >
            <Plus size={20} />
          </button>
        </div>
        <SearchBar value={keyword} onChange={setKeyword} onClear={() => setKeyword('')} />
      </header>

      {loading && companions.length === 0 && (
        <p className="py-16 text-center text-sm text-ink-faint">加载中…</p>
      )}

      {!loading && companions.length === 0 && (
        <EmptyState
          icon={<Users size={28} />}
          title="通讯录还是空的"
          hint="点右上角的加号添加一个仿真 Agent。他会像真人一样有自己的生活, 也会在你说话时回你。"
          action={
            <button
              type="button"
              className="btn-primary text-xs"
              onClick={() => navigate('/contacts/new')}
            >
              添加 Agent
            </button>
          }
        />
      )}

      {companions.length > 0 && shown.length === 0 && (
        <EmptyState title={`没有匹配「${keyword}」的联系人`} />
      )}

      {shown.length > 0 && (
        <div className="relative flex">
          <div className="min-w-0 flex-1">
            <SectionHeader label="Agents" count={shown.length} />
            <ul>
              {shown.map((c) => (
                <li key={c.id}>
                  <ListRow
                    leading={<Avatar name={c.name} kind="agent" size={40} />}
                    // 账号ID 跟名字同一行, **不是**副标题 —— 上面那段"名字下面不写任何
                    // 东西"依然成立: 它挡的是"内容"(问候语、最后一条消息), 而账号ID 与名字
                    // 是同一种东西(身份)。而且这里正是最需要它的地方: 通讯录是"都有谁",
                    // 而两个一模一样的「小满」并没有回答"都有谁"。
                    title={<NameWithHandle name={c.name} handle={c.handle} />}
                    onClick={() => navigate(`/contacts/agent/${c.id}`)}
                  />
                </li>
              ))}
            </ul>
          </div>

          {/*
            索引只在真有跨字母的名字时才出现。本平台的 Agent 名字几乎全是中文, 而
            `indexLetter()` 把所有非 ASCII 首字符归到 '#', 于是它会渲染成一个孤零零的
            '#' —— 点了不滚动任何地方, 却占掉右边一条竖带。见 `lib/contacts.ts`。
          */}
          {shouldShowIndex(letters) && (
            <AlphabetIndex letters={letters} className="sticky top-24 self-start" />
          )}
        </div>
      )}

      {menuOpen && <AddMenu onClose={() => setMenuOpen(false)} onPick={(to) => navigate(to)} />}
    </div>
  )
}

/**
 * 右上角「+」的三项 —— 与微信完全一致。
 *
 * **一期只有「添加 Agent」是通的。** 另外两项要等二期的好友与群聊, 而这里把它们
 * **画出来但点不动**, 并写清楚为什么:
 *
 * 一个点进去是白屏的入口比没有这个入口更糟; 而一个完全不存在的入口, 用户会以为
 * 这个应用根本没有这项能力 —— 而它只是还没做。所以最优解是第三种: 看得见、点不动、
 * 说得出什么时候能用。
 */
function AddMenu({ onClose, onPick }: { onClose: () => void; onPick: (to: string) => void }) {
  return (
    <>
      {/* 点空白处收起。它是 `<button>` 而不是 div, 这样键盘和读屏都能触发 */}
      <button
        type="button"
        aria-label="收起菜单"
        onClick={onClose}
        className="fixed inset-0 z-40 cursor-default bg-scrim/20"
      />
      <div className="animate-fadeUp absolute right-3 top-14 z-50 w-56 overflow-hidden rounded-xl border border-line bg-raised shadow-pop">
        <ListRow
          leading={
            <span className="grid h-8 w-8 place-items-center rounded-lg bg-accent-soft text-accent">
              <Plus size={16} />
            </span>
          }
          title="添加 Agent"
          subtitle="描述你想要的人, 他会在另一个世界里生活"
          onClick={() => {
            onClose()
            onPick('/contacts/new')
          }}
        />
        <ComingSoon icon={<UserPlus size={16} />} title="添加好友" hint="开通注册之后才有别的真人" />
        <ComingSoon icon={<UsersRound size={16} />} title="发起群聊" hint="群聊与好友一起做" />
      </div>
    </>
  )
}

/** 二期功能的位置占位 —— 见 `AddMenu` 上面那段 */
function ComingSoon({
  icon,
  title,
  hint,
}: {
  icon: React.ReactNode
  title: string
  hint: string
}) {
  return (
    <div className="row-base cursor-not-allowed opacity-55">
      <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-sunken text-ink-faint">
        {icon}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-1.5">
          <span className="truncate text-[15px] leading-6 text-ink-soft">{title}</span>
          <span className="chip shrink-0 bg-sunken text-[10px] text-ink-faint">二期</span>
        </span>
        <span className="block truncate text-[13px] leading-5 text-ink-faint">{hint}</span>
      </span>
    </div>
  )
}
