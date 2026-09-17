import { api } from './client'
import type { Persona } from '@/types'

/**
 * 一键创建 Agent 好友 —— 需求 ④ 的那个端点。
 *
 * <h2>它和 `createAgent`(`api/agent.ts`) 的区别, 就是这一整期需求的分量</h2>
 *
 * `createAgent` 打的是 `POST /api/companions`, 由 8091 建一个 agent 就结束了。那个 agent
 * **没有聊天账号** —— 它出现在你的通讯录里, 但你在聊天平台上找不到它的身份, 它也说不了话。
 * 那条路是"建一个 agent"。
 *
 * 这个端点打的是一次**四步编排**: 聊天平台先铸一个聊天账号(`users`, SIMULATOR) →
 * 拿这个账号 id 调 agent 平台的 openAPI 登记一个 agent → 回绑设备 → 建出会话。
 * 走出来的是"一个好友": 它有聊天账号、能说话、能出现在聊天列表里。
 *
 * <h2>路径为什么是 `/api/agent-friends` 这个顶层名字</h2>
 *
 * 因为 `/api/companions/**` 那一整段**不属于聊天平台** —— 后端有一个兜底代理把它转发给
 * 8091(见后端 `CompanionDomainProxyController`)。任何塞进那个前缀的新端点都会被静默转走,
 * 表现为一个语焉不详的 404, 而代码明明写得没错。所以新面一律走顶层。
 *
 * <p>(这个文件的路径里恰好含有 `/agent` 这五个字符 —— 前端的 `client.single-origin.test.ts`
 * 会扫描源码里的 `/agent` 前缀改写, 但它的判据是**引号紧跟 `/agent`**(即 `'/agent...'`),
 * 而这里的引号后面是 `/api`, 所以不在它的扫描面上。那个测试禁的是 G5 那种给请求打前缀的
 * 分流写法, 不是"路径里不许出现 agent 这个词"。)
 */

/**
 * 创建成功之后的回执。
 *
 * <p>六个字段里前三个是**三件不同的东西**, 混起来最容易出错的也正是这三个:
 *
 * - `agentId` —— agent 平台标识这个 agent 个体的值。只在对 agent 平台说话时用得上,
 *   不该出现在聊天界面里(人念不出来)。
 * - `chatAccountId` —— 它在**聊天平台**的账号 id。这是 `users.id`, 与上面的 `agentId`
 *   是两个平台的标识, 按设计永不可互换。
 * - `handle` —— **账号ID**, 人念得出来那个(`agent_xxx`)。界面上要显示的就是它,
 *   而且由 agent 平台铸、永久不变。
 *
 * <p>后三个可空: `name` 在编译链偶发失败时可能没有; `pairingCode` 在重试命中一台
 * **已经配对过**的设备时为 null —— 那不是失败, 是"这台设备不需要再配一次"。
 */
export interface ProvisionedAgentFriend {
  agentId: string
  chatAccountId: string
  /** 账号ID(`agent_` 前缀)。理论上可空 —— 补号 runner 跑过之前可能还没有 */
  handle: string | null
  name: string | null
  /** 一次性配对码。null = 已配对, 不是失败 */
  pairingCode: string | null
  /** ISO 本地时间(`2026-09-17T21:10:32.123`), 无时区 —— 与后端 `LocalDateTime` 同形 */
  pairingCodeExpiresAt: string | null
}

export interface CreateAgentFriendInput {
  /**
   * **幂等键**, 由调用方生成。
   *
   * <p>这不是一个可有可无的字段: 没有它, "用户双击"或"前端超时重发"会铸出**第二个聊天
   * 账号**, 而多出来的那个不违反任何约束 —— 它只是一行永远没人用的 `users` 加一台永远
   * 配不上的设备。后端因此直接 400 拒绝空缺。
   *
   * <p>语义是「同一次意图复用同一个值」: 重试(同样的输入再点一次)必须复用,
   * 换了输入就是新意图、要换新值。**这个判断在调用方**, 见 `CompanionCreate.tsx`。
   */
  requestId: string
  /** 与 `persona` 二选一 */
  description?: string
  persona?: Persona
  relationshipType?: string
}

/**
 * 走完四步, 返回那个好友的三个 id 与一次性配对码。
 *
 * <p>失败时抛 `ApiError`:
 * - `400` 参数不全(比如 description 与 persona 都没给)
 * - `502` **Agent 平台暂不可达** —— 聊天平台自己没出错, 是下游没接上。这时补偿已经把
 *   设备吊销了, 那个账号说不了话; 用同一个 `requestId` 重试会复活同一台设备、同一个账号,
 *   不会多出第二个。
 * - `503` 聊天平台没配 `AGENT_PLATFORM_OPENAPI_KEY`(部署问题, 不是用户能解决的)
 */
export function createAgentFriend(input: CreateAgentFriendInput): Promise<ProvisionedAgentFriend> {
  return api.post<ProvisionedAgentFriend>('/api/agent-friends', input)
}

/**
 * 造一个幂等键。
 *
 * <p>用 `crypto.randomUUID()` —— 浏览器都支持, 且**不需要**它真随机: 它只需要在同一个
 * 用户的两次不同意图之间不同。后端把它当字符串存在 `simulator_devices.request_id` 上
 * (length 64), UUID 的 36 字符装得下。
 */
export function newRequestId(): string {
  return crypto.randomUUID()
}
