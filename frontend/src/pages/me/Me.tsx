import { useEffect } from 'react'
import { LogOut, Moon, Sun } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Avatar } from '@/components/im/Avatar'
import { ListRow, SectionHeader } from '@/components/im/ListRow'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

/**
 * 「我」。
 *
 * 一期只放**已经存在的东西**: 身份、主题、退出。提醒 / 通知 / 设置 / 隐私那几项
 * 要等第 7 步各自的页面写出来才挂得上来 —— 在那之前挂上去只会点出一片空白,
 * 而一个点进去是白屏的入口比没有这个入口更糟。
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

      <SectionHeader label="外观" />
      <div className="mx-3 overflow-hidden rounded-xl border border-line bg-raised">
        <ListRow
          title="深色模式"
          subtitle={theme === 'dark' ? '已开启' : '跟随系统或手动选择'}
          leading={
            <span className="grid h-8 w-8 place-items-center rounded-lg bg-accent-soft text-accent">
              {theme === 'dark' ? <Moon size={16} /> : <Sun size={16} />}
            </span>
          }
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
            <span className="grid h-8 w-8 place-items-center rounded-lg bg-danger/10 text-danger">
              <LogOut size={16} />
            </span>
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
