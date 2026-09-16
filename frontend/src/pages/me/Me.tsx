import { useEffect } from 'react'
import { AlarmClock, Bell, ChevronRight, LogOut, Moon, Sun } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Avatar } from '@/components/im/Avatar'
import { ListRow, SectionHeader } from '@/components/im/ListRow'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import type { ReactNode } from 'react'

/**
 * 「我」。
 *
 * <h2>为什么提醒与通知在这里, 而不是聊天室里</h2>
 *
 * 它们的主语是**用户自己**: "我接下来要做什么"、"谁找过我"。老实现把两者做成
 * `Chat.tsx` 里的两个抽屉, 于是它们只在你正和某个人聊天时才看得见 —— 而这两件事
 * 恰恰是你不在聊天室里的时候才需要知道的。
 *
 * <h2>为什么没有「设置」与「隐私」</h2>
 *
 * 一期**故意不挂**。后端没有任何一条支撑它们的接口(`/api/users/me` 只读、没有改密码、
 * 没有会话管理、没有隐私开关), 所以那两个页面只能是一屏写死的、点了没反应的控件。
 *
 * 在"一个点进去是白屏的入口"和"一个点进去全是假开关的入口"之间, 这里两个都不选 ——
 * 两个都会让人以为这个应用坏了一半。它们会在有接口的那一期一起出现。
 * (这条与 `Contacts.tsx` 里「添加好友 / 发起群聊」的处理不同: 那两处是**明确标注
 * 「二期」并禁止点击**, 因为用户在通讯录里主动找的就是它们, 缺了会让人以为没有这个能力;
 * 而没人会因为找不到「设置」而认为这个应用不会聊天。)
 */
export default function Me() {
  const navigate = useNavigate()
  const { user, fetchMe, logout } = useAuthStore()
  const { theme, toggle } = useThemeStore()

  useEffect(() => {
    if (!user) fetchMe()
  }, [user, fetchMe])

  return (
    <div className="mx-auto w-full max-w-[520px]">
      <div className="flex items-center gap-4 px-5 py-6">
        <Avatar name={user?.nickname || user?.username || '我'} size={56} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-lg font-semibold text-ink">
            {user?.nickname || user?.username || '未登录'}
          </p>
          {user?.username && <p className="truncate text-sm text-ink-faint">@{user.username}</p>}
        </div>
      </div>

      <SectionHeader label="我的" />
      {/* 两行共用一个圆角容器, 中间由 `divide-y` 画发丝线 —— 微信的分组就是这个形状:
          组内不画外框, 只在行之间画线。用 `divide-y` 而不是给第二行加 `border-t`,
          是因为这样加第三行时不用记得给每一行都补边框 */}
      <div className="mx-3 divide-y divide-line overflow-hidden rounded-xl border border-line bg-raised">
        <ListRow
          title="提醒"
          subtitle="到点提醒你的事"
          leading={<IconTile><AlarmClock size={16} /></IconTile>}
          trailing={<ChevronRight size={16} className="text-ink-faint" />}
          onClick={() => navigate('/me/reminders')}
        />
        <ListRow
          title="通知"
          subtitle="有 Agent 主动找过你的时刻"
          leading={<IconTile><Bell size={16} /></IconTile>}
          trailing={<ChevronRight size={16} className="text-ink-faint" />}
          onClick={() => navigate('/me/notifications')}
        />
      </div>

      <SectionHeader label="外观" />
      <div className="mx-3 overflow-hidden rounded-xl border border-line bg-raised">
        <ListRow
          title="深色模式"
          subtitle={theme === 'dark' ? '已开启' : '跟随系统或手动选择'}
          leading={<IconTile>{theme === 'dark' ? <Moon size={16} /> : <Sun size={16} />}</IconTile>}
          trailing={
            <button
              type="button"
              onClick={toggle}
              className="btn-ghost !px-3 !py-1.5 text-xs"
              title="切换主题"
            >
              切换
            </button>
          }
        />
      </div>

      <div className="mx-3 mt-6 overflow-hidden rounded-xl border border-line bg-raised">
        <ListRow
          title="退出登录"
          leading={
            <IconTile tone="danger">
              <LogOut size={16} />
            </IconTile>
          }
          onClick={() => {
            logout()
            navigate('/login', { replace: true })
          }}
        />
      </div>
    </div>
  )
}

/**
 * 列表行左边那个 32×32 的图标方块。
 *
 * 抽出来是因为它在"我"这一屏出现了四次(提醒 / 通知 / 主题 / 退出), 而四次里
 * 三次的底色是强调色、一次是红色 —— 靠 `tone` 区分而不是让调用方各写一串 class:
 * 那串 class 里有 `h-8 w-8 grid place-items-center rounded-lg`, 抄错一个字符
 * 就会让某一行的图标歪一格, 而那种歪很难被看出是哪一行的问题。
 */
function IconTile({ children, tone = 'accent' }: { children: ReactNode; tone?: 'accent' | 'danger' }) {
  const cls = tone === 'danger' ? 'bg-danger/10 text-danger' : 'bg-accent-soft text-accent'
  return <span className={`grid h-8 w-8 place-items-center rounded-lg ${cls}`}>{children}</span>
}
