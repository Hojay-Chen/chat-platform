package com.luxera.companion.client;

import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.config.JwtUtil;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.client.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * V2.2 §6.3 第 1 项 / §6.4 —— {@code POST /api/client/login}: <b>"打开聊天软件"</b>。
 *
 * <h2>为什么 Agent 也必须走这一步(而不能一直用钥匙)</h2>
 *
 * <p>因为 §6.4 的核心原则是"不能因为 Agent 是系统内部对象, 就绕过聊天平台权限"。绕过有
 * 很多种写法, 最容易写出来的那种是: 让 Agent 的驱动程序拿着 {@code cak_} 钥匙直接调业务
 * 端点。那样做之后, 权限模型里就出现了<em>第三种身份</em> —— 它既不是某个用户、也不是某个
 * 聊天账号, 而它的权限取决于每一个端点自己记得写多少检查。
 *
 * <p>所以这里只做一件事: 把钥匙换成令牌, 让后面的每一个请求与一个真人客户端**逐字相同**
 * (同一个头、同一个令牌校验、同一组控制器)。换法本身很朴素:
 *
 * <pre>
 *   X-Api-Key: cak_…  →  chat_api_clients.agent_id  →  AgentChatIdentity  →  它的聊天账号
 *                     →  JwtUtil.generateToken(账号, agentId, EXTERNAL_AGENT)
 * </pre>
 *
 * <p>{@code ptype=EXTERNAL_AGENT} 不是为了给它更多权限, 而是为了在<em>认证层</em>就留下
 * "这是一个程序化的聊天账号, 不是一个人坐在浏览器前"这个事实 —— 审计与限流将来会用到它,
 * 而业务逻辑看不到它(见 {@link ClientPrincipal} 的说明)。
 *
 * <h2>真人的那一支: 返回原令牌, 不重签</h2>
 *
 * <p>前端本来就已经有一个 JWT(它刚用它登录过平台)。这一步对它来说不是"再登一次", 而是
 * "问一下我在聊天软件里是哪个账号"。重签一个新令牌看似无害, 实际有两个坏处: 旧令牌不会
 * 因此失效(于是同一个用户同时有两个有效令牌), 而且新令牌的过期时间与前端手里那个不一致
 * —— 前端按自己的时钟安排刷新, 却在使用另一个令牌。
 *
 * <p>所以这一支原样回显令牌, 并把**它自己的 {@code exp}** 报回去({@code JwtUtil.getExpiration}),
 * 而不是 {@code now + expiration-ms}。
 */
@Slf4j
@Service
public class ClientSessionService {

    /**
     * 新签令牌活多久 —— 与 {@code JwtUtil} 读的是同一个配置项。
     *
     * <p>本类**只**用它算"刚刚签出去的那个令牌什么时候过期"({@link JwtUtil} 没有暴露这个值,
     * 因为它自己只需要签发)。手里已有的令牌一律解 {@code exp}, 见 {@link #expiryOf}。
     */
    private final long expirationMs;

    private final JwtUtil jwtUtil;
    private final ClientPrincipalResolver resolver;

    public ClientSessionService(JwtUtil jwtUtil,
                                ClientPrincipalResolver resolver,
                                @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.jwtUtil = jwtUtil;
        this.resolver = resolver;
        this.expirationMs = expirationMs;
    }

    /**
     * 两条路都落在这里, 因为它们的**回答形状**是同一个({@link ChatSession}) —— 客户端因此
     * 只有一套解析逻辑, 与"前端和 Agent 调同一套接口"是同一件事的一半。
     *
     * @param authorizationHeader 真人的令牌(可空 —— 空则必须给钥匙)
     * @param apiKeyHeader        Agent 的接入钥匙(可空 —— 空则必须给令牌)
     */
    public ChatSession login(String authorizationHeader, String apiKeyHeader, String adminKeyHeader,
                             String correlationId) {
        if (StringUtils.hasText(apiKeyHeader) || StringUtils.hasText(adminKeyHeader)) {
            return loginAsAgent(apiKeyHeader, adminKeyHeader, correlationId);
        }
        return loginAsHuman(authorizationHeader);
    }

    private ChatSession loginAsAgent(String apiKeyHeader, String adminKeyHeader,
                                     String correlationId) {
        var keyOwner = resolver.resolveAgentKey(apiKeyHeader, adminKeyHeader, correlationId);
        String agentId = keyOwner.companionId();
        String accountId = resolver.accountIdOfAgent(keyOwner);

        String token = jwtUtil.generateToken(accountId, agentId, PrincipalType.EXTERNAL_AGENT);
        log.info("[客户端面] Agent {} 以聊天账号 {} 登录", agentId, accountId);
        return new ChatSession(token, accountId, agentId, LocalDateTime.now().plusNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(expirationMs)));
    }

    private ChatSession loginAsHuman(String authorizationHeader) {
        ClientPrincipal me = resolver.resolve(authorizationHeader);
        String token = ClientPrincipalResolver.bearerToken(authorizationHeader);
        return new ChatSession(token, me.accountId(), null, expiryOf(token));
    }

    /**
     * 解令牌的 {@code exp}。解不开就当"不知道"({@code null}) —— 而**不**退回
     * {@code now + expiration-ms}。
     *
     * <p>编不出一个诚实的值的时候, 一个空值比一个看起来很像真的的假值好: 客户端把
     * {@code expiresAt} 当 null 处理顶多是提前重登一次; 而一个"还有 24 小时"其实是"还有
     * 3 分钟"的答案会让它在第 4 分钟收到一个 401, 然后去查一个根本不存在的问题。
     */
    private LocalDateTime expiryOf(String token) {
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(jwtUtil.getExpiration(token).getTime()),
                    ZoneId.systemDefault());
        } catch (Exception e) {
            log.warn("[客户端面] 令牌的过期时刻解不出来, 回执里留空: {}", e.toString());
            return null;
        }
    }
}
