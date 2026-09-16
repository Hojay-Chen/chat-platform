import type { Notification, Reminder } from '@/types'

/**
 * 把「每个 Agent 各一份」的列表合成「一个用户的」一份。
 *
 * <h2>为什么需要它</h2>
 *
 * 提醒与通知在后端是**按 Agent** 存的: `GET /api/companions/{id}/reminders` ——
 * 因为提醒是"某个 Agent 对你说的话", 通知是"某个 Agent 发给你的事", 两者的主语都是某一个具体的 Agent。
 *
 * 而用户在「我」这一栏问的是"我有什么事", 主语是他自己。于是这一层合并是必须的。
 *
 * <h2>为什么在客户端合并, 而不是加一个 `/api/reminders`</h2>
 *
 * 一期的 Agent 数量是个位数(开通制 + 配额), 于是这里是 N 个并发请求而不是一个。
 * 二期会有 `GET /api/reminders` —— 那时**只有 `pages/me/*` 那两个页面的取数函数要改**,
 * 下面这些排序与分组的纯函数一个字都不用动, 它们收的已经是合并后的形状。
 *
 * <h2>每一行都必须带着它属于谁</h2>
 *
 * 合并之后两个 Agent 各有一条「记得喝水」是完全正常的, 而如果不带 Agent 名字, 用户
 * 看到的是两条一模一样的行 —— 他会以为系统出了重。所以 `agentName` 不是装饰, 是这一层
 * 合并之所以能成立的前提。
 */

export interface AgentScoped<T> {
  /** 归属的 Agent —— 同时当 React key 用(不同 Agent 的 id 不会撞) */
  agentId: string
  agentName: string
  item: T
}

/** 一个 Agent 的那一份原始数据 */
export interface PerAgent<T> {
  companionId: string
  name: string
  items: readonly T[]
}

export function mergeByAgent<T>(groups: readonly PerAgent<T>[]): AgentScoped<T>[] {
  return groups.flatMap((g) =>
    g.items.map((item) => ({ agentId: g.companionId, agentName: g.name, item })),
  )
}

/**
 * 提醒: 没办完的在前, 各自按时间正序。
 *
 * 时间正序而不是倒序 —— 这是一个**待办列表**, 用户关心的是"接下来该做什么",
 * 而最近刚过去的那些已经不重要了。通知是反过来的(见下一个函数), 因为那是一个
 * **动态流**, 最新发生的才值得看。
 */
export function sortReminders(rows: readonly AgentScoped<Reminder>[]): AgentScoped<Reminder>[] {
  return [...rows].sort((a, b) => {
    const aDone = isDone(a.item)
    const bDone = isDone(b.item)
    if (aDone !== bDone) return aDone ? 1 : -1
    return timeOf(a.item.remindAt) - timeOf(b.item.remindAt)
  })
}

export function splitReminders(rows: readonly AgentScoped<Reminder>[]): {
  pending: AgentScoped<Reminder>[]
  done: AgentScoped<Reminder>[]
} {
  const sorted = sortReminders(rows)
  return {
    pending: sorted.filter((r) => !isDone(r.item)),
    done: sorted.filter((r) => isDone(r.item)),
  }
}

/** 后端用的是 `status: 'pending' | 'done'`; 判"没办完"时只认 done, 别的都算没办完 */
export function isDone(reminder: Reminder): boolean {
  return reminder.status === 'done'
}

/**
 * 通知: 最新发生的在前。
 *
 * 未读的**不**单独提到最上面: 通知是"Agent 做过什么"的流水, 打乱时间顺序去看它,
 * 会让人读不懂那几天发生了什么。未读该用视觉标出来(小圆点), 不是靠重排。
 */
export function sortNotifications(
  rows: readonly AgentScoped<Notification>[],
): AgentScoped<Notification>[] {
  return [...rows].sort((a, b) => timeOf(b.item.createdAt) - timeOf(a.item.createdAt))
}

/** 解析不出来的时间排到最后, 而不是变成 NaN 把整个 sort 搅乱 */
function timeOf(iso: string): number {
  const t = new Date(iso).getTime()
  return Number.isNaN(t) ? Number.MAX_SAFE_INTEGER : t
}
