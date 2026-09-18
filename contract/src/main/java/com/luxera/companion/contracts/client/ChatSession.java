package com.luxera.companion.contracts.client;

import java.time.LocalDateTime;

/**
 * V2.2 §6.3 第 1 项 —— {@code POST /api/client/login} 的返回值: <b>"我以谁的身份登进了这个聊天
 * 软件"</b>。
 *
 * <h2>它与平台 JWT 不是一回事</h2>
 *
 * <p>平台 JWT 回答的是"你是谁"(一个平台用户); {@code ChatSession} 回答的是"你在这台聊天软件上
 * 是哪个聊天账号"。两者在真人身上恰好指向同一个 id, 在 Agent 身上则完全不同 —— Agent 的驱动
 * 程序在平台上是另一个主体(靠 {@code X-Api-Key} 认), 而它在聊天软件里的身份是那条
 * {@code users} 行({@code user_kind='SIMULATOR'})。
 *
 * <p>把这两件事分成两个类型, 是为了让下面这条不变量在代码里看得见:
 * <b>会话的权限 = 那个聊天账号的权限, 不多不少</b>(§6.4 第二层)。{@link #accountId()} 之后的
 * 每一个请求都只用它做归属判定 —— 调用方是真人还是 Agent 驱动, 业务逻辑不再关心。
 *
 * @param token      后续请求要带的凭据。REST 放 {@code Authorization: Bearer <token>},
 *                   WebSocket 放查询参数 {@code ?token=}。为 Agent 签发时它的 {@code ptype}
 *                   是 {@code EXTERNAL_AGENT}(见 {@code JwtUtil}), 于是平台能在<em>认证层</em>
 *                   就把"这是聊天软件里的一个账号"与"这是平台上的一个登录用户"分开 —— 而不必
 *                   让每个端点各自猜一遍。
 * @param accountId  这个会话代表的聊天账号 id。**它是本会话的唯一权限来源**。
 * @param agentId    仅当签发对象是 Agent 驱动时非空: 它背后的 agent id({@code companions.id})。
 *                   对真人恒为 {@code null}。它不是权柄(权柄在 accountId 上), 只是回执 ——
 *                   让调用方能自证"我这把钥匙确实配到了这个 agent"(配错 agent 是这套机制唯一
 *                   容易犯的错, 而它在别处完全不可见)。
 * @param expiresAt  过期时刻。
 */
public record ChatSession(
        String token,
        String accountId,
        String agentId,
        LocalDateTime expiresAt
) {
}
