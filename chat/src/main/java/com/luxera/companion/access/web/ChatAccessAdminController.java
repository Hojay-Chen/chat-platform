package com.luxera.companion.access.web;

import com.luxera.companion.access.ApiKeyGenerator;
import com.luxera.companion.access.ChatApiClient;
import com.luxera.companion.access.ChatApiClientRepository;
import com.luxera.companion.application.principal.ApiKeyPrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.contracts.application.PrincipalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 接入面的管理面 —— 签发 / 列出 / 吊销每客户端一把的 {@code X-Api-Key}。
 *
 * <h2>它为什么与客户端面在同一个解析器里, 却在不同的控制器里</h2>
 *
 * 两把钥匙({@code X-Admin-Key} 与 {@code X-Api-Key})是同一个机制的两个层级, 走同一条解析路
 * 才不会出现"两个地方各自判一次鉴权"(见 {@link ApiKeyPrincipalResolver} 的说明)。分开的是
 * <b>端点</b>, 不是鉴权方式 —— 而端点必须分开, 因为这里做的事(发钥匙)与接入面做的事(聊天)
 * 权限等级完全不同。
 *
 * <h2>够不到这里, 是类型上的不可能</h2>
 *
 * 每个方法第一步都判 {@code type == SYSTEM}。而 {@code ApiKeyPrincipalResolver} 只在一个
 * 地方产出 SYSTEM —— 管理钥匙对上了那一处。用 {@code cak_} 客户端钥匙解出来的永远是
 * {@code EXTERNAL_AGENT}, 带什么路径、什么 body 都一样。所以这不是"记得在路径上写拦截",
 * 而是一个第三方钥匙<b>没有一种输入能构造出</b> SYSTEM 身份。
 *
 * <p>管理员钥匙本身没配({@code app.chat.access.admin-key} 为空)时, 解析器抛
 * {@code ACCESS_ADMIN_DISABLED} → 这里答 <b>503</b>: 这个部署上接入面是死的, 而那不是
 * 调用方的问题(401 会让运维去翻接入方的配置, 翻不出来)。
 *
 * <h2>明文只出现一次</h2>
 *
 * 库里有且只有 sha256。{@link #create} 的响应是明文唯一一次露面, 之后列不出来、也补发不了
 * —— 补发的方式只有"吊销再签一把"。这不是洁癖: 能补发就等于库里的哈希表等价于明文表。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat/clients")
public class ChatAccessAdminController {

    private final PrincipalResolvers principals;
    private final ChatApiClientRepository clients;

    public ChatAccessAdminController(PrincipalResolvers principals, ChatApiClientRepository clients) {
        this.principals = principals;
        this.clients = clients;
    }

    /**
     * 签一把新钥匙, 绑在一个 Agent 上。
     *
     * <p>{@code agentId} 由管理员指定 —— 这正是它成为租户边界的原因: 调用方拿不到这个参数,
     * 它只能收到一把"已经绑好了"的钥匙。
     */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody(required = false) CreateBody body,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_ADMIN_KEY, required = false) String adminKey,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_API_KEY, required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResponseEntity<?> denied = requireAdmin(adminKey, apiKey, correlationId);
        if (denied != null) return denied;

        if (body == null || body.agentId() == null || body.agentId().isBlank()) {
            return badRequest("agentId 不能为空", "它决定这把钥匙能碰哪个 Agent 的会话, 没有默认值");
        }

        String agentId = body.agentId().trim();
        ApiKeyGenerator.GeneratedKey key = ApiKeyGenerator.generate();

        ChatApiClient client = new ChatApiClient();
        client.setClientId(UUID.randomUUID().toString());
        client.setName(body.name() == null || body.name().isBlank() ? null : body.name().trim());
        client.setApiKeyHash(key.hash());
        client.setApiKeyPrefix(key.prefix());
        client.setAgentId(agentId);
        client.setStatus(ChatApiClient.STATUS_ACTIVE);
        clients.save(client);

        log.info("[接入面] 已签发客户端 {} (agent={}, 前缀={})", client.getClientId(), agentId, key.prefix());

        Map<String, Object> out = describe(client);
        // 明文只在这里出现一次。字段名刻意叫 apiKey 而不是 key —— 它出现在一段复制粘贴里时,
        // 读的人需要一眼看出这是"一把钥匙", 而不是某个 id。
        out.put("apiKey", key.plaintext());
        out.put("hint", "明文只出现这一次, 请现在存好; 之后只能吊销重签");
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_ADMIN_KEY, required = false) String adminKey,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_API_KEY, required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResponseEntity<?> denied = requireAdmin(adminKey, apiKey, correlationId);
        if (denied != null) return denied;

        List<Map<String, Object>> out = clients
                .findByStatusOrderByCreatedAtDesc(ChatApiClient.STATUS_ACTIVE).stream()
                .map(ChatAccessAdminController::describe)
                .toList();
        return ResponseEntity.ok(Map.of("clients", out));
    }

    /**
     * 吊销——设备那一侧的 {@code revokeDevice} 是同一个动作的另一种对象。
     *
     * <p>保留行、只改状态: 一把被吊销的钥匙再被使用时, 日志与列表里要能答出"它何时被吊销的"。
     * 删行的话那个问题永远没有答案, 而"这把钥匙曾经存在过"本身是一条审计事实。
     *
     * <p>幂等: 吊销一把已吊销的钥匙返回成功。重试的语义是"我没收到上次的答复"。
     */
    @PostMapping("/{clientId}/revoke")
    public ResponseEntity<?> revoke(
            @PathVariable String clientId,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_ADMIN_KEY, required = false) String adminKey,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_API_KEY, required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResponseEntity<?> denied = requireAdmin(adminKey, apiKey, correlationId);
        if (denied != null) return denied;

        ChatApiClient client = clients.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "客户端不存在"));
        }
        if (client.isActive()) {
            client.setStatus(ChatApiClient.STATUS_REVOKED);
            client.setRevokedAt(LocalDateTime.now());
            clients.save(client);
            log.info("[接入面] 已吊销客户端 {}", clientId);
        }
        return ResponseEntity.ok(describe(client));
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    /**
     * 解析并断言"这是平台自己的管理动作"。
     *
     * @return 拒绝时的响应; 通过时 {@code null}
     */
    private ResponseEntity<?> requireAdmin(String adminKey, String apiKey, String correlationId) {
        ResolvedPrincipal me;
        try {
            me = principals.resolveApiKey(apiKey, adminKey, correlationId);
        } catch (PrincipalResolver.PrincipalException e) {
            HttpStatus status = ApiKeyPrincipalResolver.CODE_ADMIN_DISABLED.equals(e.code())
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.UNAUTHORIZED;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            body.put("code", e.code());
            return ResponseEntity.status(status).body(body);
        }
        if (me.type() != PrincipalType.SYSTEM) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "本端点只服务管理钥匙(X-Admin-Key)"));
        }
        return null;
    }

    /** 出参里**没有**哈希 —— 那一列对任何调用方都没有用处, 而一个"能读到哈希"的接口迟早会被拿去比对。 */
    private static Map<String, Object> describe(ChatApiClient c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientId", c.getClientId());
        m.put("name", c.getName());
        m.put("agentId", c.getAgentId());
        m.put("apiKeyPrefix", c.getApiKeyPrefix());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt());
        m.put("revokedAt", c.getRevokedAt());
        return m;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message, String hint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (hint != null) body.put("hint", hint);
        return ResponseEntity.badRequest().body(body);
    }

    public record CreateBody(String name, String agentId) {}
}
