package com.luxera.companion.application.mcp;

import com.luxera.companion.application.principal.ResolvedPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LAP v1 §MCP: <b>MCP 会话 ≠ ApplicationSession。</b>
 *
 * <p>这个类就是那句话的实现。MCP 的会话是<b>传输协议的状态</b> —— 协商出来的协议版本、
 * 客户端信息、初始化过没有。它活在本进程的堆里, 不落库, 重启即失效, 也不代表任何一次
 * "打开这个应用做一件事"。
 *
 * <p>{@code ApplicationSession} 完全是另一件东西(见
 * {@link com.luxera.companion.application.session.ApplicationSessionService}): 它是
 * {@code Application → Installation → ApplicationSession → Resource} 这条归属链的一环,
 * 是权限与资源归属的锚。把 MCP 会话写进那张表的代价是具体的: 每个 MCP 客户端握手一次就多一个
 * 会话行, 而这些行不属于任何安装、不指向任何资源, 于是归属不变量要么被违反, 要么得为它们开口子。
 *
 * <p><b>会话不是凭据。</b>身份在每一个请求上重新解析({@code X-Mcp-Principal} + 服务密钥),
 * 会话里记的 principal 只用来拒绝"拿着别人的会话 id 继续说话"。所以会话 id 泄漏不构成越权,
 * 一个不握手、不带会话 id 的无状态客户端也是完全合法的用法。
 *
 * <p>有界: 超过 {@value #MAX_SESSIONS} 个就按最近使用时间淘汰最旧的一批。这是一个内存储的
 * 协议状态表, 不设界就是一个缓慢的内存泄漏 —— 而它承载的东西(协议版本)丢了完全可以重建。
 */
@Slf4j
@Component
public class McpSessions {

    /** 本适配器实现到哪一版规范。 */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    /** 愿意回退到哪几版 —— 客户端报这其中的任何一版, 就按它说的来。 */
    static final List<String> SUPPORTED = List.of("2025-06-18", "2025-03-26");

    private static final int MAX_SESSIONS = 512;

    public record McpSession(String id,
                             String principalType,
                             String principalId,
                             String protocolVersion,
                             boolean initialized,
                             Instant lastSeenAt) {

        public boolean belongsTo(ResolvedPrincipal principal) {
            return principal != null
                    && principalType.equals(principal.typeName())
                    && principalId.equals(principal.principalId());
        }

        McpSession seen(Instant now) {
            return new McpSession(id, principalType, principalId, protocolVersion, initialized, now);
        }

        McpSession initialized(Instant now) {
            return new McpSession(id, principalType, principalId, protocolVersion, true, now);
        }
    }

    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    /** 开一个会话。协议版本按客户端要求协商, 支持不了就报自己的最新版(规范允许)。 */
    public McpSession open(ResolvedPrincipal principal, String requestedVersion) {
        evictIfFull();
        Instant now = Instant.now();
        McpSession session = new McpSession(UUID.randomUUID().toString(),
                principal.typeName(), principal.principalId(), negotiate(requestedVersion), false, now);
        sessions.put(session.id(), session);
        log.info("[MCP] 开启协议会话 {} for {}:{} (协议 {})",
                session.id(), session.principalType(), session.principalId(), session.protocolVersion());
        return session;
    }

    public Optional<McpSession> find(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** 记一次使用 —— 淘汰顺序靠它, 与"这个会话还在不在"无关。 */
    public McpSession touch(McpSession session) {
        McpSession fresh = session.seen(Instant.now());
        sessions.replace(session.id(), fresh);
        return fresh;
    }

    /** {@code notifications/initialized} 的落点。只记录, 不设门槛 —— 见 {@code McpController}。 */
    public McpSession markInitialized(McpSession session) {
        McpSession fresh = session.initialized(Instant.now());
        sessions.replace(session.id(), fresh);
        return fresh;
    }

    public boolean close(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    /** 进程里活着的协议会话数 —— 观测用, 与任何一张表都无关。 */
    public int size() {
        return sessions.size();
    }

    /**
     * 版本协商: 客户端说的这一版我们认识就用它, 不认识就报我们最新的那一版 ——
     * 由客户端决定要不要继续(规范就是这么定的, 服务端不替它做决定)。
     */
    static String negotiate(String requested) {
        if (StringUtils.hasText(requested) && SUPPORTED.contains(requested.trim())) {
            return requested.trim();
        }
        return PROTOCOL_VERSION;
    }

    private void evictIfFull() {
        if (sessions.size() < MAX_SESSIONS) {
            return;
        }
        sessions.values().stream()
                .sorted(Comparator.comparing(McpSession::lastSeenAt))
                .limit(MAX_SESSIONS / 8 + 1)
                .map(McpSession::id)
                .forEach(id -> {
                    sessions.remove(id);
                    log.info("[MCP] 协议会话 {} 因容量淘汰", id);
                });
    }
}
