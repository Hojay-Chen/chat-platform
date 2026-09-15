package com.luxera.companion.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import com.luxera.companion.internal.InternalSignature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code CompanionDirectoryPort} 的 HTTP 适配器 —— chat 平台查/通知仿真 Agent 平台
 * 的唯一通道。G1→G2 的进程内占位 {@code AgentPlatformIntegration} 由本类取代
 * （G2 README 已预告: "G3 落地后本类删除"）。
 *
 * <p>语义与占位哲学一致:
 * <ul>
 *   <li><b>{@code requireOwned}</b>: 归属校验需要真的目录。agent-server 404（伴侣不存在
 *     或不属于）→ {@code IllegalArgumentException}；agent-server 不可达/超时 → 也抛 IAE
 *     （"目录暂不可达"），不放行也不 500 —— 把别人的伴侣当成自己的比诚实地说做不到糟糕得多。</li>
 *   <li><b>{@code onUserMessage}</b>: fire-and-forget。任何失败都吞掉只 log —— 聊天
 *     请求线程后面是一个真人盯着 SSE 流，不能等认知；agent-server 缺席时消息照常落库、
 *     SSE 照常推，outbox 兜底链路仍在。</li>
 * </ul>
 *
 * <p>HTTP 客户端用 JDK {@code HttpURLConnection} —— 与 {@code RemoteApplicationInvoker}
 * (R14) 同一先例，不引新依赖。签名与 {@code InternalAuthFilter} 对应（HMAC-SHA256,
 * 头 {@code X-Lap-Timestamp}/{@code X-Lap-Signature}, {@code X-Lap-Service: chat}）。
 */
@Slf4j
@Component
public class HttpCompanionDirectoryAdapter implements CompanionDirectoryPort {

    private final String baseUrl;
    private final String serviceKey;
    private final int timeoutMillis;
    private final ObjectMapper objectMapper;

    public HttpCompanionDirectoryAdapter(
            @Value("${app.agent-platform.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${app.agent-platform.internal-service-key:}") String serviceKey,
            @Value("${app.agent-platform.timeout-ms:5000}") int timeoutMillis,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceKey = serviceKey;
        this.timeoutMillis = timeoutMillis;
        this.objectMapper = objectMapper;
    }

    @Override
    public CompanionRef requireOwned(String userId, String companionId) {
        Map<?, ?> resp = post("/internal/directory/require-owned",
                Map.of("userId", userId, "companionId", companionId), Map.class);
        if (resp == null) {
            throw new IllegalArgumentException("伴侣不存在或不属于该用户: " + companionId);
        }
        return new CompanionRef(
                String.valueOf(resp.get("companionId")),
                String.valueOf(resp.get("name")),
                String.valueOf(resp.get("peerMemberId")));
    }

    @Override
    public void onUserMessage(String userId, String companionId, String conversationId,
                              List<MessageView> messages) {
        // fire-and-forget: 任何失败都吞掉只 log —— 不能阻塞聊天请求线程等认知。
        // agent-server 缺席时消息照常落库、SSE 照常推给前端, outbox 兜底链路仍在。
        try {
            postFireAndForget("/internal/directory/on-user-message", Map.of(
                    "userId", userId, "companionId", companionId,
                    "conversationId", conversationId,
                    "messages", messages == null ? List.of() : messages));
        } catch (Exception e) {
            log.debug("[CompanionDirectory] onUserMessage 通知失败(吞掉): {}", e.toString());
        }
    }

    // ── HTTP 基座(与仓 2 HttpClientSupport 同构, 独立实现不共享) ─────────────

    private <T> T post(String path, Object body, Class<T> responseType) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, objectMapper.writeValueAsString(body));
            int code = conn.getResponseCode();
            if (code == 404) return null;
            if (code >= 400) {
                throw new IllegalArgumentException("agent 平台 " + code + ": " + readError(conn));
            }
            if (responseType == null) return null;
            try (InputStream in = conn.getInputStream()) {
                return objectMapper.readValue(in, responseType);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // 网络/超时/连接拒绝: 目录暂不可达 —— requireOwned 不放行
            throw new IllegalArgumentException("伴侣目录暂不可达: " + e.getClass().getSimpleName(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void postFireAndForget(String path, Object body) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, objectMapper.writeValueAsString(body));
            conn.getResponseCode();
        } catch (Exception e) {
            log.debug("[CompanionDirectory] fire-and-forget {} 失败(吞掉): {}", path, e.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection open(String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMillis);
        conn.setReadTimeout(timeoutMillis);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String payload = body == null ? "" : body;
        conn.setRequestProperty(InternalSignature.HEADER_TIMESTAMP, timestamp);
        conn.setRequestProperty(InternalSignature.HEADER_SIGNATURE,
                InternalSignature.sign(serviceKey, timestamp, payload));
        conn.setRequestProperty(InternalSignature.HEADER_SERVICE, "chat");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private String readError(HttpURLConnection conn) {
        try (InputStream err = conn.getErrorStream()) {
            if (err == null) return "(no body)";
            return new String(err.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(unreadable)";
        }
    }
}
