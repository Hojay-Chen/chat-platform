import * as agentApi from '@/api/agent'
import type { RelationshipBundle } from '@/api/agent'
import { useAgentData } from '@/hooks/useAgentData'
import { stageZh } from '@/lib/agentLabels'
import { relationshipTypeZh } from '@/lib/relationships'
import { format } from 'date-fns'
import { PanelError, PanelLoading } from './PanelState'

/**
 * 资料页的「关系」。
 *
 * 这是资料页上唯一一屏**直接展示数值**的地方 —— 「最近」那一栏刻意不展示。理由:
 * 用户在「关系」里问的是"我们现在到哪一步了", 那个问题本身就需要刻度; 而在「最近」里
 * 他问的是"她今天怎么样", 给他 0.62 是答非所问。
 *
 * 即便如此, 七个刻度也不是裸的: 每一条都说得出中文名, 「联系压力」超过 0.4 时旁边
 * 会多一句人话(「有一阵没好好聊了」)—— 数值回答"多少", 那句话回答"所以呢"。
 */

const EMPTY: RelationshipBundle = {
  relationship: null,
  events: [],
  sharedExperiences: [],
  state: null,
}

/** 关系刻度一共七条, 顺序固定 —— 抽成数组是为了不出现"漏画了一条"这种沉默的错误 */
const METERS: { key: string; label: string }[] = [
  { key: 'familiarity', label: '熟悉度' },
  { key: 'trust', label: '信任' },
  { key: 'intimacy', label: '亲密度' },
  { key: 'affection', label: '好感' },
  { key: 'tension', label: '张力' },
  { key: 'reciprocity', label: '双向性' },
  { key: 'connectionPressure', label: '联系压力' },
]

export function RelationshipPanel({ companionId }: { companionId: string }) {
  const { data, loading, error } = useAgentData(companionId, agentApi.getRelationship, EMPTY)

  if (loading) return <PanelLoading label="正在看你们的关系…" />
  if (error) return <PanelError message={error} />

  const rel = data.relationship

  return (
    <div className="space-y-5">
      {rel && (
        <div className="rounded-xl border border-line bg-raised p-4">
          <div className="flex items-center justify-between gap-2">
            <span className="text-xl font-semibold tracking-tight text-ink">
              {stageZh(rel.relationshipStage)}
            </span>
            <span className="shrink-0 text-xs text-ink-faint">
              {relationshipTypeZh(rel.relationshipType)}
              {rel.startedAt ? ` · ${format(new Date(rel.startedAt), 'yyyy年M月')} 开始` : ''}
            </span>
          </div>

          <div className="mt-3 space-y-1.5 text-xs text-ink-soft">
            {METERS.map((m) => (
              <Meter
                key={m.key}
                label={m.label}
                value={Number((rel as unknown as Record<string, number>)[m.key] ?? 0)}
              />
            ))}
          </div>

          <p className="mt-3 text-xs text-ink-faint tnum">
            累计 {rel.messageCount} 条消息 · {rel.sharedExperienceCount} 段共同经历
          </p>

          {/* 数值给"多少", 这句话给"所以呢" —— 两者都有才叫看得懂 */}
          {(rel.connectionPressure ?? 0) > 0.4 && (
            <p className="mt-2 text-xs text-accent">有一阵没好好聊了, 她心里惦记着。</p>
          )}
        </div>
      )}

      {data.state && (
        <div className="rounded-xl border border-line bg-raised p-4">
          <h4 className="text-sm font-medium text-ink">她此刻</h4>
          <div className="mt-2 grid grid-cols-2 gap-1.5 text-xs text-ink-soft">
            <span>心情:{data.state.mood || '平静'}</span>
            <span className="tnum">精力:{pct(data.state.energy)}</span>
            <span className="tnum">压力:{pct(data.state.stress)}</span>
            <span className="tnum">亲密感:{pct(data.state.emotionalCloseness)}</span>
          </div>
        </div>
      )}

      {data.sharedExperiences.length > 0 && (
        <div>
          <h4 className="mb-2 text-sm font-medium text-ink">共同经历</h4>
          <div className="space-y-1.5">
            {data.sharedExperiences.map((s) => (
              <div key={s.id} className="rounded-lg bg-sunken px-3 py-2 text-sm text-ink-soft">
                <span className="text-accent">◆ </span>
                {s.title}
                <span className="ml-2 text-xs text-ink-faint tnum">
                  {format(new Date(s.occurredAt), 'M月d日')}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {data.events.length > 0 && (
        <div>
          <h4 className="mb-2 text-sm font-medium text-ink">关系里程碑</h4>
          <div className="space-y-1.5">
            {data.events.map((e) => (
              <div key={e.id} className="rounded-lg bg-sunken px-3 py-2 text-sm text-ink-soft">
                {e.title}
                <span className="ml-2 text-xs text-ink-faint tnum">
                  {format(new Date(e.occurredAt), 'M月d日 HH:mm')}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {!rel && data.sharedExperiences.length === 0 && data.events.length === 0 && (
        <p className="py-8 text-center text-sm text-ink-faint">还没有关系记录, 去和她聊聊吧。</p>
      )}
    </div>
  )
}

function Meter({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-14 shrink-0">{label}</span>
      <div className="h-1 flex-1 overflow-hidden rounded-full bg-line">
        <div
          className="h-full rounded-full bg-accent"
          style={{ width: `${Math.round(value * 100)}%` }}
        />
      </div>
      <span className="w-8 shrink-0 text-right tnum">{Math.round(value * 100)}</span>
    </div>
  )
}

/** 0~1 的概率式数值 → 百分比。越界的值先夹回去, 而不是画出一条超出容器的条 */
function pct(v: number): string {
  return `${Math.round(Math.max(0, Math.min(1, v)) * 100)}%`
}
