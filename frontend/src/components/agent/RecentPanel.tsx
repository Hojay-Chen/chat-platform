import * as agentApi from '@/api/agent'
import { useAgentData } from '@/hooks/useAgentData'
import { formatTime } from '@/lib/time'
import type {
  CompanionLife,
  OpenLoop,
  RelationshipNarrative,
  RelationshipThread,
  SelfModel,
} from '@/types'
import { format } from 'date-fns'
import { PanelError, PanelLoading } from './PanelState'

/**
 * 资料页的「最近」。
 *
 * 用户视角, 不暴露数值面板 —— 这一条是老 `Chat.tsx` 里那段注释的原话, 它是对的:
 * 一屏「熟悉度 0.62 / 信任 0.48」是开发者看的东西, 而"{name}今天在干嘛"才是人看的。
 * 数值留在「关系」那一栏, 且换个说法(见 `RelationshipPanel`)。
 *
 * <h2>一个面板里的 5 个请求, 一起发</h2>
 *
 * 老实现是 5 个 `await` 顺着写下来 —— 于是这个面板的加载时间是 5 次往返之和。那看起来
 * 像"反正是异步的, 无所谓", 但在真机上它是 5 × RTT, 而**它们之间没有任何依赖关系**。
 * 更糟的是第一个请求抛错时, 后面 4 个根本不会发, 整个 async IIFE 变成一个未捕获的
 * rejection: 面板永远空着, 控制台里一行红字之外没有任何提示。
 *
 * 现在 5 个并发, 任何一个失败都落进 `error`, 面板里说得清楚。
 */

interface RecentBundle {
  life: CompanionLife | null
  self: SelfModel | null
  narrative: RelationshipNarrative | null
  threads: RelationshipThread[]
  loops: OpenLoop[]
}

const EMPTY: RecentBundle = { life: null, self: null, narrative: null, threads: [], loops: [] }

async function load(companionId: string): Promise<RecentBundle> {
  const [life, self, narrative, threads, loops] = await Promise.all([
    agentApi.getLife(companionId),
    agentApi.getSelfModel(companionId),
    agentApi.getNarrative(companionId),
    agentApi.getThreads(companionId),
    agentApi.getOpenLoops(companionId),
  ])
  return { life, self, narrative, threads, loops }
}

export function RecentPanel({ companionId, agentName }: {
  companionId: string
  /** 必填 —— 见 UserModelPanel 文件头: 指代一律用名字, 不用代词 */
  agentName: string
}) {
  const { data, loading, error } = useAgentData(companionId, load, EMPTY)

  if (loading) return <PanelLoading label={`正在看${agentName}今天过得怎么样…`} />
  if (error) return <PanelError message={error} />

  const { life, self, narrative, threads, loops } = data
  const activities = (life?.todayActivities ?? []).filter((a) => a.type !== 'SLEEP')

  return (
    <div className="space-y-5">
      <section>
        <h4 className="text-sm font-medium text-ink">{agentName}今天在干嘛</h4>
        {life?.todaySummary && (
          <p className="mt-1 text-xs leading-relaxed text-ink-soft">{life.todaySummary}</p>
        )}
        <div className="mt-2 space-y-1.5">
          {activities.map((a) => (
            <div key={a.id} className="flex items-center gap-2 text-xs">
              <span
                className={`marker-dot ${a.status === 'ACTIVE' ? 'bg-accent' : 'bg-ink-faint/40'}`}
              />
              <span className="w-10 text-ink-faint tnum">
                {a.plannedStart ? formatTime(a.plannedStart) : ''}
              </span>
              <span className={a.status === 'ACTIVE' ? 'text-ink' : 'text-ink-soft'}>{a.title}</span>
              {a.status === 'ACTIVE' && (
                <span className="ml-auto text-[10px] text-accent">进行中</span>
              )}
            </div>
          ))}
          {activities.length === 0 && (
            <p className="text-xs text-ink-faint">{agentName}今天还没有安排。</p>
          )}
        </div>
      </section>

      {self?.narrative && (
        <section>
          <h4 className="text-sm font-medium text-ink">{agentName}最近觉得自己</h4>
          <p className="mt-1.5 rounded-xl bg-sunken p-3 text-sm leading-relaxed text-ink-soft">
            {self.narrative}
          </p>
          {(self.concerns?.length ?? 0) > 0 && (
            <p className="mt-1.5 text-xs text-ink-faint">
              最近有点担心:{self.concerns!.join('、')}
            </p>
          )}
        </section>
      )}

      {narrative?.currentSummary && (
        <section>
          <h4 className="text-sm font-medium text-ink">你们之间的故事</h4>
          <p className="mt-1.5 rounded-xl bg-sunken p-3 text-sm leading-relaxed text-ink-soft">
            {narrative.currentSummary}
          </p>
          {narrative.sharedIdentity && (
            <p className="mt-1.5 text-xs text-accent">共同的:{narrative.sharedIdentity}</p>
          )}
        </section>
      )}

      {threads.length > 0 && (
        <section>
          <h4 className="text-sm font-medium text-ink">正在发生的事</h4>
          <div className="mt-1.5 space-y-1.5">
            {threads.map((t) => (
              <div key={t.id} className="rounded-lg bg-sunken px-3 py-2 text-xs text-ink-soft">
                <span className="text-accent">· </span>
                {t.topic}
              </div>
            ))}
          </div>
        </section>
      )}

      {loops.length > 0 && (
        <section>
          <h4 className="text-sm font-medium text-ink">{agentName}记着这些没办完的事</h4>
          <div className="mt-1.5 space-y-1.5">
            {loops.map((l) => (
              <div key={l.id} className="rounded-lg bg-sunken px-3 py-2 text-xs text-ink-soft">
                {l.title}
                {l.expectedResolutionAt && (
                  <span className="ml-2 text-ink-faint tnum">
                    · {format(new Date(l.expectedResolutionAt), 'M月d日 HH:mm')}
                  </span>
                )}
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}
