import { api } from './client'

/**
 * 账号ID 的现状 + 改号配额。
 *
 * <h2>为什么这个类型留在这里, 而 Agent 那侧只剩一个只读视图</h2>
 *
 * 因为 `used` / `limit` / `remaining` / `nextChangeAt` 这四样东西**只对真人**有意义 ——
 * Agent 的账号ID 由系统分配、不可修改, 配额对它是一组恒定的死值。原先它们挂在
 * `api/agent.ts` 上, 于是"读一个 Agent 的配额"看起来像一件有意义的事, 而这个类型
 * 住在哪就暗示了它能用在哪。
 *
 * <h2>字段名跟着后端 `HandleView` 走</h2>
 *
 * `nextChangeAt` 只在额度用尽时非空 —— 界面上就是"还能改 N 次"与"下次可改 YYYY-MM-DD"
 * 两种互斥状态。
 */
export interface HandleView {
  handle: string | null
  /** 最近 365 天内已改次数 */
  used: number
  limit: number
  /** 还能改几次。界面显示这个, **不显示 used** —— 减法在每个调用点做, 总有人做反 */
  remaining: number
  nextChangeAt: string | null
}

/**
 * 8081 把 `/api/persons/**` 整段转发给 8091(与 `/api/companions/**` 同一条路),
 * 因为 `persons` 表只有 8091 有实体映射。
 *
 * <p>路径里的 `me` 不是省略写法 —— 它是这个端点的**全部**安全性所在: 服务端从已认证的
 * 身份里取 userId, 调用方没有机会指定改谁。所以这里也没有 `personId` 参数可传。
 */
const MINE = '/api/persons/me/handle'

/** 我现在的账号ID + 配额现状。设置页打开时读一次。 */
export function getMyHandle(): Promise<HandleView> {
  return api.get<HandleView>(MINE)
}

/**
 * 改我自己的账号ID。
 *
 * 失败时抛 {@link ApiError}, 带 `status`:
 * `400` 形状不对 / `409` 被占用 / `429` 一年三次用完 —— 三种要给三句不同的话,
 * 而它们各自的 `hint` 里就写着该说的那句。调用方不要写自己的映射表。
 *
 * 大小写与前后空白由后端归一, 这里**不做**前端预校验: 一份前端副本就是一份会漂移的规则,
 * 而漂移的表现是"前端说可以, 后端说不行"。
 */
export function updateMyHandle(handle: string): Promise<HandleView> {
  return api.put<HandleView>(MINE, { handle })
}
