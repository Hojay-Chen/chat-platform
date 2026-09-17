package com.luxera.companion.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 查/写仓 2 的 Agent 名单 —— 补铸与对账两条 runner 的唯一通道。
 *
 * <h2>它和 {@link HttpCompanionDirectoryAdapter} 的区别(以及为什么是两个类)</h2>
 *
 * 那一个是 {@code CompanionDirectoryPort} 的实现, 服务的是**认知链**: 一条用户消息落库前的
 * 归属校验、落完之后的通知。它的调用方在聊天请求线程上, 每一次调用都对着一个正在等回复的真人。
 *
 * <p>这一个不被任何端口实现, 只被两个一次性 runner 调用: 补铸(给历史 Agent 铸聊天账号)和
 * 对账(比对两边名单)。它们的调用方是启动钩子, 没有人在等, 而且它们要传达的东西
 * (404 = 这个 agent 早就没了 / 409 = 两边记的账号不一样)与认知链那两条路**没有任何重叠**。
 *
 * <p>合成一个类的话, 那个类会有五个方法、两个调用方群体和两套失败语义 —— 而"一次补铸的
 * 失败"和"一条消息的归属校验失败"要采取的行动完全不同(前者记日志继续, 后者拒掉这次写入)。
 *
 * <h2>失败一律抛, 由 runner 逐条决定怎么办</h2>
 *
 * 本类**不吞**任何异常(与 {@code HttpCompanionDirectoryAdapter#onUserMessage} 的
 * fire-and-forget 相反): 调用方是 runner, 它需要知道这一条失败了好继续下一条, 而不是
 * 让一个静默的 catch 把失败变成"看起来都成功了"。
 *
 * <p>HTTP 基座与那一个类同构(JDK {@code HttpURLConnection}, HMAC 签名, 不引新依赖),
 * 但**刻意不复用它的私有方法** —— 那两个 private 方法各自绑着"post 一个 Map 然后读回一个
 * 类型"的假设, 抽出来共用会让两处一起变复杂, 而好处只是少二十行。
 */
@Slf4j
@Component
public class HttpAgentInventoryClient {

    private final String baseUrl;
    private final String serviceKey;
    private final int timeoutMillis;
    private final ObjectMapper objectMapper;

    public HttpAgentInventoryClient(
            @Value("${app.agent-platform.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${app.agent-platform.internal-service-key:}") String serviceKey,
            @Value("${app.agent-platform.timeout-ms:5000}") int timeoutMillis,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceKey = serviceKey;
        this.timeoutMillis = timeoutMillis;
        this.objectMapper = objectMapper;
    }

    /**
     * 仓 2 那边一个 Agent 的**身份三件套**(不含人格、关系、状态)。
     *
     * <p>{@code chatAccountId} 与 {@code handle} 都可空 —— 而且必须是 {@code null} 而不是空串,
     * 补铸的判据就落在 {@code chatAccountId == null} 上(见 {@code CompanionService} 那边的说明)。
     */
    public record AgentSummary(String companionId, String name, String chatAccountId, String handle) {
        /** 「这个 agent 还没有聊天账号」—— 补铸要处理的那些。 */
        public boolean missingChatAccount() {
            return chatAccountId == null || chatAccountId.isBlank();
        }
    }

    /**
     * 列活着的 Agent。
     *
     * @param missingChatAccount {@code true} 只要还没有账号的(补铸输入);
     *                           {@code false} 全部活着的(对账输入)
     */
    public List<AgentSummary> list(boolean missingChatAccount) {
        Map<?, ?> resp = post("/internal/companions/list",
                Map.of("missingChatAccount", missingChatAccount), Map.class);
        List<AgentSummary> result = new ArrayList<>();
        if (resp == null) return result;
        Object raw = resp.get("companions");
        if (!(raw instanceof List<?> items)) return result;
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) continue;
            result.add(new AgentSummary(str(m.get("companionId")), str(m.get("name")),
                    str(m.get("chatAccountId")), str(m.get("handle"))));
        }
        return result;
    }

    /**
     * 把一个已经存在的聊天账号登记到仓 2 的那个 agent 上 —— 补铸的最后一步。
     *
     * <p>仓 2 侧对同一个值重放返回 200(见 {@code CompanionService#attachChatAccount}),
     * 所以这一步天然可重跑: 上次推成功了但本仓没记下"推成功了"的话, 再推一次不会报错。
     *
     * @throws AgentInventoryException 404(agent 不存在或已删除) / 409(账号对不上) / 5xx / 不可达
     */
    public void attachChatAccount(String companionId, String chatAccountId) {
        // 先自己挡一道空值: Map.of 对 null 直接抛 NPE, 于是"参数没给"会表现成一个
        // NullPointerException, 而不是一句"账号为空"。补铸里这个值的来源是上一句
        // provisionSimulatorAccount 的返回, 它理论上不会是空的 —— 但"理论上"正是
        // 那种会在半年后某次重构里被证伪的词。
        if (companionId == null || companionId.isBlank()) {
            throw new AgentInventoryException(0, "companionId 为空", null);
        }
        if (chatAccountId == null || chatAccountId.isBlank()) {
            throw new AgentInventoryException(0, "chatAccountId 为空", null);
        }
        post("/internal/companions/attach-chat-account",
                Map.of("companionId", companionId, "chatAccountId", chatAccountId), null);
    }

    /**
     * 服务间调用失败 —— 带上状态码, 因为**可重试与不可重试的区别全在这个码上**。
     *
     * <p>补铸 runner 对这两类的处理不同: 4xx 是"两边记录对不上", 重跑一百次还是同一个结果,
     * 该报出来让人看; 5xx / 连不上是"对面此刻不行", 下次启动再跑一遍就好了。把两者都当
     * {@code RuntimeException} 抛出去的话, runner 只能给它们同一句日志, 而"需要人介入"的
     * 那一类就会被淹没在"对面在重启"里。
     */
    public static class AgentInventoryException extends RuntimeException {
        /** HTTP 状态码; 0 = 连接/超时之类的传输层失败(对面可能根本没收到) */
        private final int status;

        AgentInventoryException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }

        /** 重跑不会改变结果的那种失败 —— 需要人介入。 */
        public boolean permanent() {
            return status >= 400 && status < 500;
        }
    }

    // ── HTTP 基座(与 HttpCompanionDirectoryAdapter 同构, 刻意不共享) ─────────

    private <T> T post(String path, Object body, Class<T> responseType) {
        HttpURLConnection conn = null;
        try {
            String payload = objectMapper.writeValueAsString(body);
            conn = open(path, payload);
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new AgentInventoryException(code,
                        "agent 平台 " + code + ": " + readError(conn), null);
            }
            if (responseType == null) return null;
            try (InputStream in = conn.getInputStream()) {
                return objectMapper.readValue(in, responseType);
            }
        } catch (AgentInventoryException e) {
            throw e;
        } catch (Exception e) {
            // 网络/超时/连接拒绝: 状态码 0 —— runner 据此知道这是"对面此刻不行", 可以再跑一次
            throw new AgentInventoryException(0,
                    "agent 平台暂不可达: " + e.getClass().getSimpleName(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection open(String path, String payload) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMillis);
        conn.setReadTimeout(timeoutMillis);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        conn.setRequestProperty(InternalSignature.HEADER_TIMESTAMP, timestamp);
        conn.setRequestProperty(InternalSignature.HEADER_SIGNATURE,
                InternalSignature.sign(serviceKey, timestamp, payload));
        conn.setRequestProperty(InternalSignature.HEADER_SERVICE, "chat");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    /** 两边的 JSON 里缺字段 / 显式 null 都归一成 {@code null}, 不编一个空串出来。 */
    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
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
