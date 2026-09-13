package com.luxera.companion.application.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.action.ActionHandlerContext;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestCatalogueSync;
import com.luxera.companion.contracts.application.ActionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * LAP v1 §Runtime.REMOTE: <b>把一次动作转发给一个跑在别处的应用。</b>
 *
 * <p>它挂在 {@code ActionHandler} 这个位置上是刻意的 —— 于是 {@code ActionGateway} 一行都不用
 * 改: 权限、归属、幂等、审计、事件那七步对远端应用与内置应用完全一样。反过来做(在网关里加一个
 * {@code if (remote) ...} 分支)的话, 那条分支迟早会少做一步主路径做的事, 而少了哪一步都不会
 * 有人立刻发现。这也是"Native + Remote 先行、Hosted 缓一缓"这个决定最好的落地方式:
 * 加一种运行时 = 加一个 handler, 不是加一条路径。
 *
 * <h2>三件必须做对的事</h2>
 * <ol>
 *   <li><b>签名</b> —— 见 {@link RemoteSignature}。密钥按 {@code authRef} 这个名字从平台配置解析,
 *       manifest 里永远不出现密钥(它会进数据库、进日志、进导出包)。</li>
 *   <li><b>幂等键</b> —— 见 {@link #deriveIdempotencyKey}。转发的是<em>派生</em>出来的键, 不是
 *       调用方那个原始字符串。</li>
 *   <li><b>硬超时</b> —— 连接与读取各有上限({@code app.lap.remote.timeout-ms}, 默认 3 秒)。
 *       一个挂住的远端不该把平台的工作线程一直握在手里 —— 那是"一个应用拖垮整个平台"最省事的
 *       写法。</li>
 * </ol>
 *
 * <p><b>已知边界</b>(不是遗漏): 远端本轮只能回 {@code result}, 不能回事件。事件的真实消费者
 * 只有数字人一条链, 而它现在跑在本进程内; 凭想象先造一条远端事件通道, 只会得到一个没有流量、
 * 因而没有人验证过的接口。等真有远端应用需要它时, 形状由那个需求决定。
 */
@Slf4j
@Service
public class RemoteApplicationInvoker {

    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final int timeoutMillis;

    public RemoteApplicationInvoker(ObjectMapper objectMapper,
                                    Environment environment,
                                    @Value("${app.lap.remote.timeout-ms:3000}") int timeoutMillis) {
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.timeoutMillis = timeoutMillis;
    }

    public ActionOutcome invoke(ApplicationManifest manifest, ActionHandlerContext context) {
        ApplicationManifest.RemoteDecl remote = manifest.runtime() == null
                ? null : manifest.runtime().remote();
        if (remote == null || !StringUtils.hasText(remote.baseUrl())) {
            return ActionOutcome.fail("REMOTE_ENDPOINT_MISSING",
                    "REMOTE 应用 " + manifest.applicationId() + " 的 manifest 里没有 runtime.remote.baseUrl");
        }
        String secret = resolveSecret(remote.authRef());
        if (secret == null) {
            // 配置缺失是平台侧的问题, 不是远端的问题 —— 用 FAILED 而不是 DENIED:
            // 调用方的权限没有任何问题, 是平台自己没准备好。
            return ActionOutcome.fail("REMOTE_AUTH_UNRESOLVED",
                    "远端应用 " + manifest.applicationId() + " 的 authRef=" + remote.authRef()
                            + " 在平台配置(app.lap.remote.auth.*)里解析不到密钥");
        }

        String body = serialize(manifest, context);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String idempotencyKey = deriveIdempotencyKey(manifest, context);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(remote.baseUrl()).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Lap-Application", manifest.applicationId());
            connection.setRequestProperty("X-Lap-Version", manifest.version());
            connection.setRequestProperty("X-Lap-Action", context.actionId());
            connection.setRequestProperty("X-Lap-Principal", context.principal().principalType()
                    + ":" + context.principalId());
            connection.setRequestProperty(RemoteSignature.HEADER_TIMESTAMP, timestamp);
            connection.setRequestProperty(RemoteSignature.HEADER_SIGNATURE,
                    RemoteSignature.sign(secret, timestamp, body));
            connection.setRequestProperty("Idempotency-Key", idempotencyKey);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            String responseBody = read(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return interpret(manifest, code, responseBody);
        } catch (java.net.SocketTimeoutException e) {
            log.warn("[Remote] {} 在 {}ms 内没有响应: {}", manifest.applicationId(), timeoutMillis,
                    remote.baseUrl());
            return ActionOutcome.fail("REMOTE_TIMEOUT",
                    "远端应用在 " + timeoutMillis + "ms 内没有响应");
        } catch (Exception e) {
            log.warn("[Remote] 调用 {} 失败: {}", manifest.applicationId(), e.toString());
            return ActionOutcome.fail("REMOTE_UNAVAILABLE",
                    "远端应用调用失败: " + e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * <b>为什么不把调用方那个 Idempotency-Key 原样转发。</b>
     *
     * <p>调用方的键只在本平台的 {@code (principal, key)} 作用域里唯一 —— 这正是网关那条唯一索引
     * 的样子。把它原样发出去, 两个不同的 principal 各自用了字符串 {@code "1"} 时, 远端会把它们
     * 当成同一次调用, 于是第二个人拿到第一个人的结果。这不是理论风险: 键是客户端随手起的。
     *
     * <p>派生出来的键是"这一次逻辑调用"的确定函数(应用 + 版本 + 动作 + 目标 + principal +
     * correlationId)。同一次调用的重试(崩溃后抢占重放、网络重发)会得到同一个键, 于是远端的幂等
     * 依然成立; 不同调用方之间则必然不同。两边都想要的那两件事, 只有这样才同时拿得到。
     */
    public String deriveIdempotencyKey(ApplicationManifest manifest, ActionHandlerContext context) {
        String material = String.join("|",
                manifest.applicationId(),
                manifest.version(),
                context.actionId(),
                String.valueOf(context.target()),
                context.principal().principalType() + ":" + context.principalId(),
                String.valueOf(context.correlationId()));
        return ManifestCatalogueSync.sha256(material);
    }

    /** {@code authRef} 是<b>名字</b>不是密钥; 名字到密钥的那一步在这里, 且只在这里。 */
    private String resolveSecret(String authRef) {
        if (!StringUtils.hasText(authRef)) {
            return null;
        }
        String value = environment.getProperty("app.lap.remote.auth." + authRef);
        return StringUtils.hasText(value) ? value : null;
    }

    private String serialize(ApplicationManifest manifest, ActionHandlerContext context) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("action", context.actionId());
        root.put("applicationId", manifest.applicationId());
        root.put("version", manifest.version());
        root.put("target", context.target());
        root.set("input", context.input() == null ? objectMapper.createObjectNode() : context.input());
        if (context.expectedVersion() != null) {
            root.put("expectedResourceVersion", context.expectedVersion());
        }
        root.put("correlationId", context.correlationId());
        ObjectNode principal = root.putObject("principal");
        principal.put("type", String.valueOf(context.principal().principalType()));
        principal.put("id", context.principalId());
        if (context.companionId() != null) {
            principal.put("companionId", context.companionId());
        }
        if (context.userId() != null) {
            principal.put("userId", context.userId());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("远端请求无法序列化: " + e.getMessage(), e);
        }
    }

    /**
     * HTTP → {@link ActionOutcome}, <b>与 {@code ActionStatusMapper} 是同一张表的方向相反</b>。
     *
     * <p>远端回 200 时, 响应体可以是一个裸的 result 对象, 也可以是
     * {@code {"result":{...},"error":{...}}} 这种带信封的形状 —— 两种都认: 前者是"我就一个结果",
     * 后者是"我按 LAP 的形状回"。不认第二种的话, 一个照文档实现的远端反而会被判成失败。
     */
    private ActionOutcome interpret(ApplicationManifest manifest, int code, String responseBody) {
        if (code >= 200 && code < 300) {
            JsonNode parsed = parse(responseBody);
            JsonNode error = parsed == null ? null : parsed.path("error");
            if (error != null && error.isObject() && !error.isMissingNode()) {
                return ActionOutcome.of(ActionStatus.FAILED,
                        error.path("code").asText("REMOTE_FAILED"),
                        error.path("message").asText(null));
            }
            JsonNode result = parsed != null && parsed.has("result") ? parsed.get("result") : parsed;
            return ActionOutcome.success(result);
        }
        if (code == 404) {
            return ActionOutcome.of(ActionStatus.NOT_FOUND, "REMOTE_NOT_FOUND", errorMessage(responseBody));
        }
        if (code == 403 || code == 401) {
            return ActionOutcome.of(ActionStatus.DENIED, "REMOTE_DENIED", errorMessage(responseBody));
        }
        if (code == 409) {
            return ActionOutcome.of(ActionStatus.STATE_CONFLICT, "REMOTE_CONFLICT", errorMessage(responseBody));
        }
        if (code == 400 || code == 422) {
            return ActionOutcome.of(ActionStatus.INVALID_ARGUMENT, "REMOTE_INVALID_ARGUMENT",
                    errorMessage(responseBody));
        }
        log.warn("[Remote] {} 返回 {}, 视为失败", manifest.applicationId(), code);
        return ActionOutcome.of(ActionStatus.FAILED, "REMOTE_FAILED", errorMessage(responseBody));
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 远端的错误体不一定长成我们的样子 —— 认不出来就退回一行截断的原文, 而不是空指针。 */
    private String errorMessage(String responseBody) {
        JsonNode parsed = parse(responseBody);
        if (parsed != null) {
            JsonNode message = parsed.path("error").path("message");
            if (message.isTextual()) {
                return message.asText();
            }
            JsonNode top = parsed.path("message");
            if (top.isTextual()) {
                return top.asText();
            }
        }
        if (responseBody == null || responseBody.isBlank()) {
            return "远端没有给出错误说明";
        }
        return responseBody.length() > 256 ? responseBody.substring(0, 256) : responseBody;
    }

    private static String read(InputStream in) {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }
}
