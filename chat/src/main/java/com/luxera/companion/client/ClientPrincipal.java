package com.luxera.companion.client;

import com.luxera.companion.contracts.application.PrincipalType;

/**
 * V2.2 §6.4 —— <b>「这次请求是以聊天软件里的哪一个账号做的」</b>, 就这一个问题。
 *
 * <h2>它和 {@code ResolvedPrincipal} 的分工</h2>
 *
 * <p>{@link com.luxera.companion.application.principal.ResolvedPrincipal} 是 LAP 的身份
 * (它带着 companionId / userId / sessionId / correlationId —— 那套词汇服务的是应用网关的
 * 二十多个动作)。客户端面要的比那窄得多, 而且<b>刻意窄</b>: §6.4 第二层的那句话是
 * "这个会话的权限 = 那个聊天账号的权限, 不多不少", 于是这个接口之后的每一行业务代码只需要
 * 知道一个 {@link #accountId()}。
 *
 * <p>窄下来有一个具体的好处: <b>业务逻辑没有机会按来源分叉</b>。如果控制器里拿得到
 * companionId / userId, 迟早会有人写出"如果是 Agent 就跳过这一步" —— 而那句话一旦写下,
 * §6.4 的核心原则(不能因为 Agent 是内部对象就绕过聊天平台权限)就破了。这里连字段都没有,
 * 所以不是"记得别写", 而是写不出来。
 *
 * <h2>三层授权里它是第①②层的交接点</h2>
 *
 * <pre>
 *   ① 平台授权   JWT / X-Api-Key  ──►  能不能访问这台服务器
 *   ② 应用会话   本类型            ──►  以哪个聊天账号的身份做事
 *   ③ 能力授权   Agent 平台内部    ──►  这个 agent 能不能执行这个 Action(不在本仓)
 * </pre>
 *
 * <p>第①层由 {@link ClientPrincipalResolver} 判(令牌/钥匙), 第②层就是本类型的
 * {@code accountId} —— 它由第①层的结果**推导**而来, 调用方自报不了:
 * 真人的令牌解出来的是他自己的 {@code users.id}; Agent 的钥匙解出来的是那把钥匙在
 * {@code chat_api_clients} 上绑定的 agent, 再经 {@code AgentChatIdentity} 换成它的聊天账号。
 * 两条路都不接受请求体里的任何字段。
 *
 * @param accountId 聊天账号 id({@code users.id})。**它同时是"我是谁"与"我能碰什么"** ——
 *                  后续每一段会话都必须是这个账号参与的会话, 判定因此是结构性的
 *                  (从"这个账号参与的会话"里找), 不是一段记得写就有的检查。
 * @param type      这个账号是以什么身份在做事。业务逻辑**不应该**看它 —— 它的用处有两个:
 *                  发消息时决定走哪一侧的写入链(真人说话要触发认知, Agent 说话是回复),
 *                  以及 {@code GET /api/client/contacts/{accountId}} 那个"平台认不认识这个账号"
 *                  的判定。任何第三种用法都值得先想一想。
 * @param source    这个身份是怎么来的({@code JWT} / {@code APIKEY}), 只进日志与审计 ——
 *                  它回答的是"你是从哪扇门进来的", 与"你是谁"无关。
 */
public record ClientPrincipal(String accountId, PrincipalType type, String source) {

    /** 这一侧是不是那个"要触发认知"的真人侧。 */
    public boolean isHuman() {
        return type == PrincipalType.HUMAN;
    }

    /** 从哪扇门进来的两个取值。 */
    public static final String SOURCE_JWT = "JWT";
    public static final String SOURCE_APIKEY = "APIKEY";

    public String typeName() {
        return type == null ? null : type.name();
    }
}
