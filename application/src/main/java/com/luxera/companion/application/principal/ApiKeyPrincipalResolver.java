package com.luxera.companion.application.principal;

import com.luxera.companion.access.ApiKeyGenerator;
import com.luxera.companion.access.ChatApiClient;
import com.luxera.companion.access.ChatApiClientRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * LAP v1 的第四种来源: <b>平台外的程序</b> —— 持有 {@code X-Api-Key} 的第三方 agent 驱动程序,
 * 或持有 {@code X-Admin-Key} 的平台管理员。
 *
 * <h2>它和 MCP 解析器解决的不是同一件事</h2>
 *
 * 两者都"没有 JWT", 但 {@code McpPrincipalResolver} 的身份是调用方<b>自称</b>的
 * ({@code X-Mcp-Principal: AGENT:xxx} + 一把共享服务密钥): 任何持钥者都能声称自己是任何
 * agent。那作为"谁在上架应用"够用, 作为"谁在读某个人的私信"不能 —— 一句声明不是租户边界。
 *
 * <p>这里解出来的 {@code companionId} 来自 {@code chat_api_clients} 的一行, 管理员写入,
 * 调用方无从自报。于是需求⑦"给其他 agent 程序同等的接入能力"可以真的落地: 给的是
 * <b>同等的接口</b>, 但每把钥匙只能碰它绑定的那一个 Agent 的会话。
 *
 * <h2>为什么这是一条独立的路, 而不是把 MCP 面放宽一点</h2>
 *
 * 因为要放给第三方的不是"更多权限", 而是"更小的权限": MCP 面的动作集是 LAP 的
 * 应用网关(20+ 动作, 含建会话/邀请/订阅), 接入面只有四个(列会话/读消息/发消息/标已读)。
 * 公开面必须是内部面的<b>子集</b>, 不是它的镜像 —— 把 MCP 放宽就等于把整套应用网关
 * 连同"删光一个 peer 的全部历史"一起放出去。
 *
 * <h2>管理面为什么也在这个解析器里</h2>
 *
 * {@code X-Admin-Key} 是"发钥匙的那把钥匙", 它与 {@code X-Api-Key} 是同一个机制的两个层级
 * (请求头里的一个共享密钥), 走同一条解析路才不会出现"两个地方各自判一次鉴权"。
 * 解出来的类型是 {@link PrincipalType#SYSTEM}(平台自身), 而第三方钥匙<b>永远</b>解不出
 * SYSTEM —— 所以外来的钥匙够不到管理端点, 这不是路径约定, 是类型上的不可能。
 *
 * <p>管理员头先判、且判错就拒: 一个既带了错的管理员钥匙又带了对的客户端钥匙的请求里,
 * 调用方想表达的是"我是管理员" —— 那就按管理员判, 判不过就是 401, 而不是悄悄降级成
 * 客户端身份把请求执行掉。
 */
@Component
public class ApiKeyPrincipalResolver implements PrincipalResolver {

    /** 每客户端一把的接入钥匙。刻意不是 {@code Authorization} —— 那个头归 JWT。 */
    public static final String HEADER_API_KEY = "X-Api-Key";
    /** 平台管理员一把, 用于签发/吊销上面那种钥匙。 */
    public static final String HEADER_ADMIN_KEY = "X-Admin-Key";

    public static final String SOURCE_APIKEY = "APIKEY";

    /** 管理员 principal 的 id —— 它不代表任何具体的人, 代表"平台自己的管理动作"。 */
    public static final String ADMIN_PRINCIPAL_ID = "chat-access-admin";

    /** 管理员钥匙没配 —— 管理面是死端点(与 MCP "密钥没配就完全不服务"同一哲学)。 */
    public static final String CODE_ADMIN_DISABLED = "ACCESS_ADMIN_DISABLED";
    public static final String CODE_ADMIN_UNAUTHORIZED = "ACCESS_ADMIN_UNAUTHORIZED";
    /** 钥匙不认识、格式不对、或已吊销 —— 三者对外是同一件事。 */
    public static final String CODE_KEY_UNKNOWN = "ACCESS_KEY_UNKNOWN";

    private final String adminKey;
    private final ChatApiClientRepository clients;

    public ApiKeyPrincipalResolver(@Value("${app.chat.access.admin-key:}") String adminKey,
                                   ChatApiClientRepository clients) {
        this.adminKey = adminKey == null ? "" : adminKey.trim();
        this.clients = clients;
    }

    @Override
    public String source() {
        return SOURCE_APIKEY;
    }

    /**
     * 只看这两个头在不在。都没有 → 本解析器不认这个请求, 由解析链继续/最终拒绝。
     *
     * <p>刻意<em>不</em>在这里查库: {@code supports} 是"这是不是我的活", 而查库是"这活能不能干"。
     * 混在一起会让"钥匙是错的"变成一个"没人负责"的请求, 于是落到链尾那句
     * {@code UNIDENTIFIED_PRINCIPAL} 上, 错误信息与"你什么都没带"一模一样。
     */
    @Override
    public boolean supports(PrincipalRequest request) {
        return request != null
                && (StringUtils.hasText(request.apiKeyHeader())
                    || StringUtils.hasText(request.adminKeyHeader()));
    }

    @Override
    public ResolvedPrincipal resolve(PrincipalRequest request) {
        if (StringUtils.hasText(request.adminKeyHeader())) {
            return resolveAdmin(request);
        }
        return resolveClient(request);
    }

    private ResolvedPrincipal resolveAdmin(PrincipalRequest request) {
        if (adminKey.isEmpty()) {
            throw new PrincipalException(CODE_ADMIN_DISABLED,
                    "接入管理面未启用(app.chat.access.admin-key 未配置)");
        }
        if (!constantTimeEquals(adminKey, request.adminKeyHeader().trim())) {
            throw new PrincipalException(CODE_ADMIN_UNAUTHORIZED, "管理密钥不正确");
        }
        return new ResolvedPrincipal(PrincipalType.SYSTEM, ADMIN_PRINCIPAL_ID,
                null, null, null, request.correlationId(), SOURCE_APIKEY);
    }

    private ResolvedPrincipal resolveClient(PrincipalRequest request) {
        String raw = request.apiKeyHeader().trim();
        // 形状初筛: 不是 cak_ 开头的东西连哈希都不必算 —— 这一条挡掉的是扫描器流量,
        // 不是攻击者(他当然会带上正确前缀)。
        if (!raw.startsWith(ApiKeyGenerator.KEY_PREFIX)) {
            throw new PrincipalException(CODE_KEY_UNKNOWN, "接入钥匙无效");
        }
        String hash = ApiKeyGenerator.sha256Hex(raw);
        Optional<ChatApiClient> found =
                clients.findByApiKeyHashAndStatus(hash, ChatApiClient.STATUS_ACTIVE);
        if (found.isEmpty()) {
            // 不区分"没这个哈希"与"已吊销": 对外都是 401。区分开来等于告诉持有一把旧钥匙的人
            // "你这把曾经是对的" —— 那对攻击者是信息, 对正常调用方毫无用处。
            throw new PrincipalException(CODE_KEY_UNKNOWN, "接入钥匙无效");
        }
        ChatApiClient client = found.get();
        return new ResolvedPrincipal(PrincipalType.EXTERNAL_AGENT,
                client.getClientId(),
                client.getAgentId(),
                null, null,
                request.correlationId(),
                SOURCE_APIKEY);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
