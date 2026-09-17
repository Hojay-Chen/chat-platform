import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import AuthShell from '@/components/AuthShell'

export default function Register() {
  const register = useAuthStore((s) => s.register)
  const navigate = useNavigate()
  const [form, setForm] = useState({ username: '', password: '', nickname: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      await register(form)
      navigate('/chat', { replace: true })
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  // 注册页是**给真人开的**(平台开放注册), 所以这句副标题不该以 Agent 为主语。
  // 原来写「给自己起个名字,然后去遇见它」—— 那是"注册就是为了领一个伴侣"的旧
  // 流程。顺带把开通制说在前面: 注册后聊天/小程序都能用, 只有「添加 Agent」需要
  // 管理员开通。与其让人加的时候才撞上, 不如在这一屏就讲明白。
  // (后端 register 一期仍 403, 这是有意的 —— 二期开。)
  return (
    <AuthShell title="创建账号" subtitle="注册后就能聊天、用小程序。添加 Agent 需管理员开通。">
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label className="label">用户名</label>
          <input
            className="input"
            value={form.username}
            onChange={(e) => setForm({ ...form, username: e.target.value })}
            required
            minLength={3}
          />
        </div>
        <div>
          <label className="label">昵称(可选)</label>
          <input
            className="input"
            value={form.nickname}
            onChange={(e) => setForm({ ...form, nickname: e.target.value })}
          />
        </div>
        <div>
          <label className="label">密码</label>
          <input
            className="input"
            type="password"
            value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
            required
            minLength={6}
          />
        </div>
        {error && <p className="text-sm text-danger">{error}</p>}
        <button className="btn-primary w-full" disabled={loading}>
          {loading ? '创建中…' : '创建账号'}
        </button>
        <p className="text-center text-sm text-ink-soft">
          已有账号?
          <Link to="/login" className="ml-1 text-accent hover:underline">
            登录
          </Link>
        </p>
      </form>
    </AuthShell>
  )
}
