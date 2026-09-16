import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Ticket } from 'lucide-react'
import { lap, LapError, type JoinResponse } from '@/api/lap'

/**
 * 「分享链接加入页」—— `/join/{token}`。
 *
 * <h2>路径里那 30 个字符不是 id</h2>
 * 它是 Capability Token 的**明文**: 拿到链接的人能进这一场, 但看不到这张票编号几号、
 * 谁铸的、本来给谁。库里只有它的 SHA-256(见 `LapInvitationController` 的类注释)。
 * 于是这一页不需要(也拿不到)任何其他信息 —— 它唯一要做的事就是把 token 递回去兑换。
 *
 * <h2>为什么用 ref 挡住第二次兑换</h2>
 * 兑票是**有副作用**的: 它会消耗一次 `maxUses` 名额。React 18+ 的开发模式会故意把
 * effect 跑两遍, 严格模式下这意味着一次点击烧掉两个名额 —— 而"我点了一下, 票却少了两张"
 * 是那种只在开发环境发生、上线后查不出来的 bug。用 ref 而不是 state: 它必须**同步**生效,
 * 而 setState 要等到下一次渲染, 挡不住紧随其后的第二次调用。
 *
 * <h2>加入成功之后不是"到此为止"</h2>
 * 兑票只回答"你进来了"。这一页随后直接把用户送到 `sessionId` 对应的 Session 页 ——
 * 进来的目的是下棋, 不是看一张"加入成功"的凭证。
 */
export default function JoinSession() {
  const { token = '' } = useParams()
  const navigate = useNavigate()
  const attempted = useRef(false)

  const [joined, setJoined] = useState<JoinResponse | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const report = useCallback((e: unknown) => {
    if (e instanceof LapError) setError(`${e.code} — ${e.message}`)
    else setError(e instanceof Error ? e.message : String(e))
  }, [])

  useEffect(() => {
    if (attempted.current || !token) return
    attempted.current = true

    setBusy(true)
    lap
      .joinByToken(token)
      .then((response) => {
        setJoined(response)
        // 兑票已经完成了它该做的事; 换页去现场。应用 id 从这一场里读不出来(兑票响应
        // 只说"你在哪一场"), 所以走一条不需要它的路径。
        navigate(`/sessions/${response.sessionId}`, { replace: true })
      })
      .catch(report)
      .finally(() => setBusy(false))
  }, [token, navigate, report])

  return (
/*
 * `h-full overflow-y-auto` 而不是 `min-h-screen` —— 这一页是 FullScreenLayout 的
 * 子路由, 而那个布局是 `h-dvh overflow-hidden`(它把纵向空间交给页面自己管)。
 * 页面用 `min-h-screen` 又不给自己一个滚动容器, 后果是**超出首屏的内容被裁掉且
 * 滚不到** —— 而这一页的内容恰恰是"编译出人格之后才长出来"的, 首屏一定装不下。
 *
 * 滚动容器放在根节点上, 上面那个 `sticky top-0` 的头部才有东西可吸; 之前
 * `overflow-hidden` 的父级让 sticky 无处可吸, 头部实际是死的。
 */
    <div className="flex h-full items-center justify-center overflow-y-auto bg-surface px-5">
      <div className="card w-full max-w-md text-center">
        <Ticket className="mx-auto text-accent" size={28} />
        <h1 className="mt-3 text-xl text-ink">加入这一场</h1>

        {busy && <p className="mt-3 text-sm text-ink-soft">正在兑换邀请…</p>}

        {error && (
          <>
            <p className="mt-3 text-sm text-danger">{error}</p>
            <p className="mt-2 text-xs text-ink-faint">
              链接可能已经用过、过期, 或者被主人收回了。找发链接的人再要一张。
            </p>
          </>
        )}

        {joined && !error && (
          <p className="mt-3 text-sm text-ink-soft">
            已作为 {joined.role} 加入 —— 正在打开现场…
          </p>
        )}

        <div className="mt-6">
          <Link to="/applications" className="btn-ghost">
            <ArrowLeft size={14} />
            去应用市场
          </Link>
        </div>
      </div>
    </div>
  )
}
