import { useCallback, useEffect, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { lap, LapError, newIdempotencyKey, type ResourceView } from '@/api/lap'
import type { EmbeddedAppProps } from '@/surfaces/registry'

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

export default function BoardApp({ sessionId }: EmbeddedAppProps) {
  const [resource, setResource] = useState<ResourceView | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

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
   * 开一局。`target` 传 null —— 这正是 R9 决定 3 第 4/5 档存在的理由:
   * 棋盘还不存在, 所以没有任何 URI 能指向它, 但"我在这一场里"这件事平台知道。
   */
  const create = async () => {
    setBusy(true)
    setError(null)
    try {
      const response = await lap.execute('game.create', null, {}, newIdempotencyKey())
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
      <div className="text-sm text-ink-soft">
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

  const cellSize = board.side > 10 ? 'h-6 w-6 text-[10px]' : 'h-12 w-12 text-lg'

  return (
    <div>
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
        className="grid w-fit gap-0.5"
        style={{ gridTemplateColumns: `repeat(${board.side}, minmax(0, 1fr))` }}
      >
        {board.cells.map((cell, index) => (
          <button
            key={index}
            type="button"
            disabled={busy || cell !== null}
            onClick={() => move(index)}
            className={`${cellSize} rounded-sm bg-sunken text-ink disabled:opacity-50`}
          >
            {cell === null || cell === undefined ? '' : String(cell)}
          </button>
        ))}
      </div>
    </div>
  )
}
