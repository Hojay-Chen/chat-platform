import { useCallback, useEffect, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { lap, LapError, newIdempotencyKey, type ResourceView } from '@/api/lap'
import type { EmbeddedAppProps } from '@/surfaces/registry'
// 填模板这件事平台已经有一个函数了, 而且是同一条规矩(只替换那两个变量, 认不出的原样
// 留着)。资源 URI 模板与 entry 模板是同一种字符串, 所以复用而不是再写一个。
import { resolveEntry } from '@/surfaces/entry'

/**
 * 一块棋盘的界面实现 —— 井字棋与五子棋共用。
 *
 * <h2>它为什么对"井字棋"一无所知</h2>
 * 这个组件只知道两件事: 这一场里有一个 `state.board` 是方阵的资源; 落子这个动作叫
 * `game.make_move`, 参数是 `{position}`。剩下的(几乘几、连几个算赢、谁执什么记号)
 * 全在应用那侧 —— 组件从不判断胜负, 它只是把 `state` 画出来。
 *
 * 这不是"通用棋盘组件"那种抽象: 它是**这一层能诚实拥有的全部知识**。任何更多的东西
 * (比如"9 格就是井字棋")都会在下一个棋类应用出现时变成错误的假设。
 *
 * <h2>它走的接口与数字人完全相同</h2>
 * `actions:execute` + 一把新的 `Idempotency-Key`, 拒了就把 `error.code` 原样显示。
 * 页面上没有任何一条"真人特供"的路径 —— 这正是 LAP 值得存在的理由。
 */

/** 方阵边长。井字棋是 3, 五子棋是 15 —— 从资源形状读出来, 不写死。 */
function squareSide(cells: unknown): number | null {
  if (!Array.isArray(cells)) return null
  const side = Math.round(Math.sqrt(cells.length))
  return side > 0 && side * side === cells.length ? side : null
}

function boardOf(resource: ResourceView | null): { cells: unknown[]; side: number } | null {
  const state = resource?.state as { board?: unknown } | null | undefined
  const cells = state?.board
  const side = squareSide(cells)
  if (!side) return null
  return { cells: cells as unknown[], side }
}

export default function BoardApp({ applicationId, sessionId, surface }: EmbeddedAppProps) {
  /**
   * 整页打开时应用拥有整个视口 —— 四周的留白归应用自己管。
   *
   * `FULL_PAGE` 现在是小程序运行时, 平台连那圈 16px 内边距都不给了(`bleed`), 于是
   * 不补这一下的症状是: 文字贴着屏幕最上沿, 而右上角那枚胶囊正压在同一行上。
   * 另外四种外框由平台给内边距, 再加一层就变成双份。
   *
   * 顶上多让出 48px: 平台那枚胶囊浮在右上角(12px 起, 32px 高), 应用的头部若不避开
   * 就会钻到它底下。微信的小程序也有同一件事, 做法也一样 —— 应用自己避让。
   *
   * 这就是 `EmbeddedAppProps.surface` 那个字段存在的理由 —— 它的注释写的是"应用可
   * 据此调整密度, 但**不该**据此改变行为"。留白是密度, 不是行为。
   */
  const shell = surface === 'FULL_PAGE' ? 'px-3 pb-3 pt-12' : ''

  const [resource, setResource] = useState<ResourceView | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  /**
   * 棋盘还不存在时, 动作该指向哪里 —— 见 `create()` 上面那一大段。
   *
   * 这个组件对"棋盘"的全部知识就是"这一场里有一个方阵资源", 所以它不去猜哪个模板是
   * 棋盘: **应用声明了几个资源模板就用第一个**。两个内置棋类应用各自只声明一个。
   * 真出现一个声明多个模板的应用时, 这条规则会第一次变得不够用, 那时再让它去认 ——
   * 提前为不存在的情况设计, 只会让今天这一行变难读。
   */
  const [target, setTarget] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    lap
      .application(applicationId)
      .then((d) => {
        if (cancelled) return
        const template = d.resources?.[0]?.uriTemplate
        setTarget(template ? resolveEntry(template, { applicationId, sessionId }) : null)
      })
      .catch(() => {
        // 拿不到模板只影响"开一局"这一个动作 —— 已经存在的棋盘照样能读能下,
        // 所以这里不把整块界面打成错误态。
        if (!cancelled) setTarget(null)
      })
    return () => {
      cancelled = true
    }
  }, [applicationId, sessionId])

  const load = useCallback(async () => {
    setBusy(true)
    setError(null)
    try {
      const views = await lap.resourcesOfSession(sessionId)
      // 这一场里可能有多个资源; 挑第一个"看起来像棋盘"的。
      setResource(views.find((v) => squareSide((v.state as { board?: unknown } | null)?.board)) ?? null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }, [sessionId])

  useEffect(() => {
    void load()
  }, [load])

  const move = async (position: number) => {
    if (!resource) return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const response = await lap.execute(
        'game.make_move',
        resource.uri,
        { position },
        newIdempotencyKey(),
      )
      if (response.resource) setResource(response.resource)
      setNotice(response.status === 'SUCCESS' ? null : response.status)
    } catch (e) {
      // 被拒是常态(不该你走、格子有人、这一场已结束) —— 平台的 error.code 就是答案,
      // 界面的责任是把它原样摆出来, 而不是翻译成一句"操作失败"。
      if (e instanceof LapError) setError(`${e.code} — ${e.message}`)
      else setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const board = boardOf(resource)
  const state = resource?.state as Record<string, unknown> | null | undefined
  const turn = state?.turn
  const winner = state?.winner

  /**
   * 开一局。
   *
   * <h2>`target` 不能传 null —— 这一行曾经是错的</h2>
   *
   * 原来这里传 `null`, 注释写的理由是"棋盘还不存在, 所以没有任何 URI 能指向它, 但
   * '我在这一场里'这件事平台知道"(R9 决定 3 的第 4/5 档)。那两档确实存在, 但它挂在
   * <b>进程内</b>的调用路径上; HTTP 那条路径上没有任何会话上下文 —— `/actions:execute`
   * 的请求体里只有 `{action, target, input}`, 没有 sessionId。
   *
   * 于是实际发生的是 `ActionGateway` 在解析之前就把空 target 挡掉, 返回
   * `TARGET_REQUIRED — 缺少 target`。也就是说: 两个内置棋类应用的「开一局」按钮,
   * 在网页上**从来没有成功过**。
   *
   * <h2>正确的 target 从哪来</h2>
   *
   * 应用自己声明了它 —— manifest 的 `resources[].uriTemplate`。井字棋是
   * `game://session/{sessionId}`, 五子棋是 `gomoku://match/{sessionId}`: <b>两个应用不
   * 一样</b>, 所以这个组件不能写死其中任何一个, 只能问平台要模板然后做替换 ——
   * 与它已经在做的 entry 替换是同一条规矩(§68)。
   */
  const create = async () => {
    if (!target) {
      setError('这个应用没有声明资源模板, 平台无法为它开一局')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const response = await lap.execute('game.create', target, {}, newIdempotencyKey())
      if (response.resource) setResource(response.resource)
      else await load()
    } catch (e) {
      if (e instanceof LapError) setError(`${e.code} — ${e.message}`)
      else setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  if (!board) {
    return (
      <div className={`text-sm text-ink-soft ${shell}`}>
        <p>这一场还没有棋盘。</p>
        <div className="mt-3 flex items-center gap-2">
          <button type="button" onClick={create} disabled={busy} className="btn-primary">
            开一局
          </button>
          <button type="button" onClick={load} disabled={busy} className="btn-ghost">
            <RefreshCw size={14} />
            再读一次
          </button>
        </div>
        {error && <p className="mt-2 text-xs text-danger">{error}</p>}
      </div>
    )
  }

  /**
   * 棋盘的尺寸 —— 格子跟着**轨道**走, 而不是各自定尺。
   *
   * <h2>`minmax(0, 1fr)` 配 `w-fit`, 画出来是 15 条横带</h2>
   *
   * 原来这里是 `grid w-fit` 加 `repeat(side, minmax(0, 1fr))`, 格子则是定尺的
   * `h-6 w-6`。`1fr` 在 `w-fit`(即 fit-content)容器里**不**按"平分剩余宽度"结算:
   * 15 条轨道塌成 21.53px, 而格子自己仍是 24px —— 每格多出来的 2.47px 正好把列间距
   * 盖掉。量出来的网格是 351×388, 列宽 21.53px, 格子 24px。
   *
   * 这个症状之所以难看出来, 是因为它**不对称**: 纵向的轨道高度由内容撑开, 行间距好好
   * 地在, 横向的被盖住了 —— 一个 15×15 的棋盘看着像 15 条横带, 而不是一张格网。
   *
   * 改法是让格子变成 `aspect-square w-full`: 恒等于轨道、恒为正方形。网格自己的宽度
   * 上限由 `maxWidth` 给出 —— "15 路该密、3 路该疏"是这一层唯一还知道的事情, 而它只是
   * 一个**上限**, 装不下时轨道自己会缩。
   *
   * 顺带修好的第二件事: 15×15 在 375px 的手机上本来就放不下(15×24+28 = 388 > 351)。
   * 定尺格子会硬溢出, 轨道方案缩到 21.5px 一格, 仍然方方正正。
   */
  const cellPx = board.side > 10 ? 24 : 48
  const cellText = board.side > 10 ? 'text-[10px]' : 'text-lg'

  return (
    <div className={shell}>
      <div className="mb-3 flex flex-wrap items-center gap-3 text-xs text-ink-soft">
        <span>
          轮到 <span className="text-ink">{turn ? String(turn) : '—'}</span>
        </span>
        {winner ? (
          <span className="text-accent">
            {winner === 'DRAW' ? '平局' : `${String(winner)} 胜`}
          </span>
        ) : null}
        <span className="text-ink-faint">version {resource?.version}</span>
        <button type="button" onClick={load} disabled={busy} className="btn-ghost !px-2 !py-0.5">
          <RefreshCw size={12} />
          刷新
        </button>
      </div>

      {error && (
        <div className="mb-2 rounded border border-danger/30 bg-danger/10 px-3 py-1.5 text-xs text-danger">
          {error}
        </div>
      )}
      {notice && !error && <div className="mb-2 text-xs text-ink-soft">{notice}</div>}

      <div
        className="grid w-full gap-0.5"
        style={{
          gridTemplateColumns: `repeat(${board.side}, minmax(0, 1fr))`,
          // 见上面 `cellPx` —— 这是个上限, 不是定尺: 列间距 (side-1) 条各 2px 也要算进去
          maxWidth: board.side * cellPx + (board.side - 1) * 2,
        }}
      >
        {board.cells.map((cell, index) => (
          <button
            key={index}
            type="button"
            disabled={busy || cell !== null}
            onClick={() => move(index)}
            className={`aspect-square w-full rounded-sm bg-sunken text-ink disabled:opacity-50 ${cellText}`}
          >
            {cell === null || cell === undefined ? '' : String(cell)}
          </button>
        ))}
      </div>
    </div>
  )
}
