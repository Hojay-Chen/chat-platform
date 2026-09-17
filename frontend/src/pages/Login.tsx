import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import AuthShell from '@/components/AuthShell'

export default function Login() {
  const login = useAuthStore((s) => s.login)
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      await login(username, password)
      navigate('/chat', { replace: true })
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  // 副标题要一句话说清这个平台是什么。原来是「一个会记住你的数字伴侣」——
  // 那是重做前的定位, 而它把整个产品说小成了一件事(伴侣), 恰恰漏掉了这个平台
  // 真正的主体(聊天)和它区别于微信的那块生态(Agent)。三样都点出来, 且让
  // Agent 只是其中之一。
  return (
    <AuthShell title="Luxera 聊天" subtitle="聊天、应用, 还有你自己的 Agent">
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label className="label">用户名 / 邮箱</label>
          <input className="input" value={username} onChange={(e) => setUsername(e.target.value)} required />
        </div>
        <div>
          <label className="label">密码</label>
          <input
            className="input"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        {error && <p className="text-sm text-danger">{error}</p>}
        <button className="btn-primary w-full" disabled={loading}>
          {loading ? '登录中…' : '登录'}
        </button>
      </form>
    </AuthShell>
  )
}
