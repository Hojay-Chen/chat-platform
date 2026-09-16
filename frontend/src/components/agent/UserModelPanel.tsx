import * as agentApi from '@/api/agent'
import { useAgentData } from '@/hooks/useAgentData'
import { factLabel } from '@/lib/agentLabels'
import type { UserFact, UserHypothesis, UserPattern, UserPreference } from '@/types'
import { PanelEmpty, PanelError, PanelLoading, PanelSection } from './PanelState'

/**
 * 资料页的「她了解的」—— 她心里的那个你。
 *
 * <h2>四栏的排序不是随意的</h2>
 *
 * 「她知道的 / 她注意到的 / 她还在琢磨的」按**确定性从高到低**排: 第一栏是你亲口说的,
 * 第二栏是她观察到的, 第三栏是她的推测。这个顺序是这一屏的全部意义 —— 一个把推测和
 * 事实混在一起展示的系统, 用户没法和它相处, 因为他不知道哪句话是她"知道"的。
 *
 * 所以第三栏的每一条前面都带一个 `?`, 而前两栏不带。那个问号是这一屏最重要的一个像素。
 *
 * 「沟通偏好」放在最后: 它是关于**相处方式**的, 不是关于事实的, 和前三栏不是一回事。
 */

interface ModelBundle {
  facts: UserFact[]
  patterns: UserPattern[]
  hypotheses: UserHypothesis[]
  preferences: UserPreference[]
}

const EMPTY: ModelBundle = { facts: [], patterns: [], hypotheses: [], preferences: [] }

async function load(companionId: string): Promise<ModelBundle> {
  const [facts, patterns, hypotheses, preferences] = await Promise.all([
    agentApi.listUserFacts(companionId),
    agentApi.listUserPatterns(companionId),
    agentApi.listUserHypotheses(companionId),
    agentApi.listUserPreferences(companionId),
  ])
  return { facts, patterns, hypotheses, preferences }
}

export function UserModelPanel({ companionId }: { companionId: string }) {
  const { data, loading, error } = useAgentData(companionId, load, EMPTY)

  if (loading) return <PanelLoading label="正在看她怎么理解你…" />
  if (error) return <PanelError message={error} />

  return (
    <div className="space-y-5">
      <PanelSection title="她知道的" hint="你明确告诉过她的">
        {data.facts.length === 0 && <PanelEmpty>还没有事实</PanelEmpty>}
        {data.facts.map((f) => (
          <ConfRow key={f.id} label={factLabel(f.predicate, f.object)} conf={f.confidence} />
        ))}
      </PanelSection>

      <PanelSection title="她注意到的" hint="从你的习惯里观察到的">
        {data.patterns.length === 0 && <PanelEmpty>还没有行为模式</PanelEmpty>}
        {data.patterns.map((p) => (
          <ConfRow key={p.id} label={p.description || p.pattern} conf={p.confidence} />
        ))}
      </PanelSection>

      <PanelSection title="她还在琢磨的" hint="只是推测, 她不会把它当事实">
        {data.hypotheses.length === 0 && <PanelEmpty>还没有推测</PanelEmpty>}
        {data.hypotheses.map((h) => (
          <ConfRow key={h.id} label={h.description || h.hypothesis} conf={h.confidence} mark="?" />
        ))}
      </PanelSection>

      <PanelSection title="沟通偏好" hint="她倾向怎么和你相处">
        {data.preferences.length === 0 && <PanelEmpty>还没有偏好记录</PanelEmpty>}
        {data.preferences.map((p) => (
          <ConfRow key={p.id} label={p.preference} conf={p.confidence} />
        ))}
      </PanelSection>
    </div>
  )
}

/**
 * 一行「结论 + 置信度」。
 *
 * 百分比用等宽数字: 四条并排时 62% 与 8% 会左右错开, 而这一栏的读者在比较的正是
 * 这些数字的大小。
 */
function ConfRow({ label, conf, mark }: { label: string; conf: number; mark?: string }) {
  return (
    <div className="flex items-center justify-between gap-2 rounded-lg bg-sunken px-3 py-2 text-sm">
      <span className="truncate text-ink-soft">
        {mark && <span className="mr-1 text-accent">{mark}</span>}
        {label}
      </span>
      <span className="shrink-0 text-xs text-ink-faint tnum">{Math.round(conf * 100)}%</span>
    </div>
  )
}
