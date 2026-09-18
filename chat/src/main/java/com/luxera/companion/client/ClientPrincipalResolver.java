package com.luxera.companion.client;

import com.luxera.companion.application.principal.ApiKeyPrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.config.JwtUtil;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.conversation.AgentChatIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * V2.2 §6.4 第①→②层的交接 —— <b>把"你带着什么凭据"换成"你是哪个聊天账号"</b>。
 *
 * <h2>为什么前端与 Agent 可以共用同一组控制器, 而授权不会互相串味</h2>
 *
 * <p>因为两条路进来之后<b>得到的是同一个东西</b>: 一个 {@link ClientPrincipal}, 里面只有
 * 一个聊天账号 id。控制器与它下游的所有业务代码只看这个账号 —— 它们没有机会问"你是人还是
 * Agent", 因为那个答案不在它们能拿到的类型里。于是"共用接口"不是把两套权限混在一起, 而是
 * 两套凭据各自收敛到同一个主语上。
 *
 * <pre>
 *   前端（真人）           Agent（ChatApplication 的驱动程序）
 *        │                              │
 *   Authorization: Bearer &lt;用户JWT&gt;     │  X-Api-Key: cak_...   （只在 login 那一次）
 *        │                              │
 *        └──────────┬───────────────────┘
 *                   ▼
 *           ClientPrincipalResolver
 *             ├── JWT    → accountId = 令牌 subject（那个人自己的账号）
 *             └── APIKEY → agentId    = chat_api_clients.agent_id（管理员写入, 自报不了）
 *                          accountId = 该 agent 的聊天账号（AgentChatIdentity）
 *                   │
 *                   ▼
 *           同一个 ClientPrincipal（只有 accountId）
 * </pre>
 *
 * <h2>它防的是什么(逐层)</h2>
 *
 * <ul>
 *   <li><b>① 平台授权</b>: 没有凭据 → 401; 令牌坏了/过期 → 401; 钥匙不认识 → 401。
 *       防的是"任何能连上 8081 的人读别人的私信"。</li>
 *   <li><b>② 应用会话</b>: 凭据解出来的账号**就是**它之后的权限全集。防的是
 *       "Agent 因为自己是系统内部对象而绕过聊天平台权限"(§6.4 的核心原则) ——
 *       这里它拿到的不是一个更宽的身份, 而是**一个普通聊天账号**, 权限与一个真人登录后
 *       一模一样。</li>
 *   <li><b>钥匙的租户边界</b>: 一把 {@code cak_} 钥匙解出的 agentId 来自
 *       {@code chat_api_clients} 那一行(管理员写入), 调用方无从自报 —— 于是它够不到别的
 *       Agent 的会话, 这不是一条检查, 而是一条结构性事实(与对外开放面同一先例)。</li>
 * </ul>
 *
 * <h2>为什么 SYSTEM / APPLICATION 一律拒绝</h2>
 *
 * <p>管理钥匙({@code X-Admin-Key})解出来的是 {@link PrincipalType#SYSTEM}, 它不代表任何
 * 聊天账号。让它通过的话, 下游每一处"这个账号参与的会话"都会退化成"全部会话" ——
 * 而它看起来还能正常工作(管理面本来就该看得到一切)。<b>那条路是给 provision 用的, 不是
 * 给读消息用的</b>, 两者在这一个类里分开: {@link #resolveAdmin} 只认管理钥匙并且只服务
 * {@code POST /api/client/provision}, 而 {@link #resolve} 永远不返回 SYSTEM。
 *
 * <h2>为什么 Agent 的令牌可以走 JWT 那一路</h2>
 *
 * <p>因为 login 给它签出来的就是一个<em>普通的平台 JWT</em>, 只不过 {@code ptype} 是
 * {@code EXTERNAL_AGENT}、subject 是它的聊天账号。于是"Agent 必须像真人客户端一样登录、
 * 拿 token、带 token 请求"(§6.4)在实现上不是一条纪律, 而是**唯一可行的那条路** ——
 * 钥匙只在 login 那一次被接受, 之后就只剩令牌。
 */
@Slf4j
@Component
public class ClientPrincipalResolver {

    private final JwtUtil jwtUtil;
    private final UserRepository users;
    private final PrincipalResolvers principals;
    private final AgentChatIdentity agentIdentity;

    public ClientPrincipalResolver(JwtUtil jwtUtil,
                                   UserRepository users,
                                   PrincipalResolvers principals,
                                   AgentChatIdentity agentIdentity) {
        this.jwtUtil = jwtUtil;
        this.users = users;
        this.principals = principals;
        this.agentIdentity = agentIdentity;
    }

    /**
     * 客户端面的唯一身份入口 —— <b>只认 {@code Authorization: Bearer}</b>。
     *
     * <p>刻意**不在这里**接受 {@code X-Api-Key}: 一把长期钥匙如果能在每一个业务端点上直接使用,
     * §6.4 的"必须像真人客户端一样登录"就成了一句空话(钥匙一旦泄露, 换不掉也收不回,
     * 而令牌有到期时间)。钥匙只在 {@code POST /api/client/login} 那一个端点被受理, 见
     * {@link ClientSessionService#login}。
     *
     * <p>账号是否存在要现查: {@code POST /api/client/login} 是 permitAll 的, 而本解析器也服务
     * WebSocket 握手 —— 那两处都不过 {@code JwtAuthenticationFilter}, 少这一句的话, 一个
     * 已注销用户的未过期令牌仍然能读到历史会话。
     */
    public ClientPrincipal resolve(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        if (token == null) {
            throw ClientApiException.unauthenticated("缺少凭据",
                    "请带 Authorization: Bearer <token>；没有令牌请先 POST /api/client/login");
        }
        if (!jwtUtil.isValid(token)) {
            // 过期与签名不对对外是同一件事: 换一个有效凭据再来。区分开来只会告诉猜令牌的人
            // "你这一个的形状是对的"。
            throw ClientApiException.unauthenticated("令牌无效或已过期",
                    "重新 POST /api/client/login 换一个");
        }
        String accountId = jwtUtil.getUserId(token);
        if (!StringUtils.hasText(accountId)) {
            throw ClientApiException.unauthenticated("令牌里没有账号", null);
        }
        PrincipalType type = jwtUtil.getPrincipalType(token);
        requireUsableType(type);
        if (!users.existsById(accountId)) {
            throw ClientApiException.unauthenticated("这个账号不存在或已注销", null);
        }
        return new ClientPrincipal(accountId, type, ClientPrincipal.SOURCE_JWT);
    }

    /**
     * 管理面那一条路 —— {@code X-Admin-Key}, 只服务 {@code POST /api/client/provision}。
     *
     * <p>返回值里的 {@code accountId} 是<em>平台自己</em>({@code chat-access-admin}),
     * 它不是一个聊天账号, 所以调用方**只能**拿它去建账号, 不能拿它去读会话 ——
     * {@link #resolve} 永远不会给出 SYSTEM, 于是"用管理钥匙读私信"这件事在类型上就不成立。
     */
    public ResolvedPrincipal resolveAdmin(String apiKeyHeader, String adminKeyHeader,
                                          String correlationId) {
        ResolvedPrincipal me;
        try {
            me = principals.resolveApiKey(apiKeyHeader, adminKeyHeader, correlationId);
        } catch (PrincipalResolver.PrincipalException e) {
            // 接入面的失败码与状态码原样保留(ACCESS_KEY_UNKNOWN → 401,
            // ACCESS_ADMIN_DISABLED → 503) —— 它们已经是"给机器看"的, 而区分"你钥匙错了"与
            // "这个部署没配管理密钥"对运维是有用的。
            throw ClientApiException.fromAccessKeyFailure(e);
        }
        if (me.type() != PrincipalType.SYSTEM) {
            // cak_ 客户端钥匙走到这里: 它是给 login 用的, 不是给建账号用的。
            throw ClientApiException.forbidden("provision 只服务管理密钥(X-Admin-Key)",
                    "第三方 agent 请走 POST /api/client/login");
        }
        return me;
    }

    /**
     * 把"这个 Agent 的钥匙"换成"这个 Agent 的聊天账号"—— login 那条路上唯一的一次换算。
     *
     * @return Agent 的聊天账号 id
     * @throws ClientApiException 这个 agent 还没有聊天账号(409)
     */
    public String accountIdOfAgent(ResolvedPrincipal agentKeyOwner) {
        String agentId = agentKeyOwner.companionId();
        String accountId = agentIdentity.chatAccountIdOf(agentId);
        if (!StringUtils.hasText(accountId)) {
            // 409 而不是 401: 这把钥匙**是对的**, 只是它背后的 agent 还没被铸出聊天账号。
            // 答 401 会让调用方去翻自己的钥匙, 翻到天亮也翻不出结果(与对外开放面
            // 把"管理密钥没配"答成 503 是同一条理由)。
            throw ClientApiException.conflict("这个 Agent 还没有聊天账号",
                    "先由平台为它开通聊天账号(§6.5 POST /api/client/provision), 再登录");
        }
        return accountId;
    }

    /** 解出"这个请求是哪个 Agent 的钥匙"—— 只服务 login 与 provision 两条引导路径。 */
    public ResolvedPrincipal resolveAgentKey(String apiKeyHeader, String adminKeyHeader,
                                            String correlationId) {
        ResolvedPrincipal me;
        try {
            me = principals.resolveApiKey(apiKeyHeader, adminKeyHeader, correlationId);
        } catch (PrincipalResolver.PrincipalException e) {
            throw ClientApiException.fromAccessKeyFailure(e);
        }
        if (me.type() != PrincipalType.EXTERNAL_AGENT) {
            // 管理钥匙走这里: 它没有 companionId, 于是 accountIdOfAgent 会拿到一个 null agent。
            // 与其让后面那次换算去处理 null, 不如在这里说清楚这两把钥匙各走各的门。
            throw ClientApiException.forbidden("login 只服务接入钥匙(X-Api-Key)",
                    me.type() == PrincipalType.SYSTEM
                            ? "管理密钥请走 POST /api/client/provision"
                            : "本端点只服务第三方 agent 程序");
        }
        return me;
    }

    /** {@code Bearer } 前缀的剥离 —— 大小写不敏感, 与 {@code JwtAuthenticationFilter} 的写法保持一致。 */
    public static String bearerToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) return null;
        String trimmed = authorizationHeader.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 这个 principal 类型能不能以"一个聊天账号"的身份做事。
     *
     * <p>{@code SYSTEM} 与 {@code APPLICATION} 不能: 它们不是某个人、也不代表某个聊天账号,
     * 放它们进来就会在上游产生一个"账号 id 是 {@code chat-access-admin} 的会话查询" ——
     * 那个查询会回一个空列表, 看起来像"你还没有会话", 于是没人会发现它是错的。
     */
    private static void requireUsableType(PrincipalType type) {
        if (type == PrincipalType.HUMAN
                || type == PrincipalType.AGENT
                || type == PrincipalType.EXTERNAL_AGENT) {
            return;
        }
        throw ClientApiException.forbidden(
                "以 " + (type == null ? "未知" : type.name()) + " 身份不能使用客户端面",
                "请用真人账号登录, 或用一个 Agent 的接入钥匙换取会话");
    }
}
