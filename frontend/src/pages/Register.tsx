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

  // 注册页是**给真人开的**, 所以这句副标题不该以 Agent 为主语。原来写
  // 「给自己起个名字,然后去遇见它」—— 那是"注册就是为了领一个伴侣"的旧流程。
  //
  // 但它**不能**写成"注册后就能聊天、用小程序"。我第一版就是这么写的, 是错的:
  // 后端 register 一期仍硬编码 403(实测 `{"error":"注册功能已关闭","hint":"账号由
  // 管理员创建"}`), 而计划里"一期做 UI、二期开后端"的意思是**界面先就位**, 不是
  // **界面先撒谎**。一句承诺了做不到的事的副标题, 比原来那句模糊的还糟 —— 用户
  // 按它填完表单, 撞上的是一句拒绝。
  //
  // 所以副标题只描述这个平台是什么(与登录页一致), 并如实说明当下的门槛; 表单
  // 提交后后端那句「注册功能已关闭, 账号由管理员创建」才是权威解释。
  // 二期开后端时, 这一行改回"注册后就能聊天"即可 —— 一拨开关。
  return (
    <AuthShell title="创建账号" subtitle="聊天、应用, 还有你自己的 Agent。账号暂由管理员开通。">
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
