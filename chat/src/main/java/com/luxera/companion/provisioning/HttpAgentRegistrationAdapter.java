package com.luxera.companion.provisioning;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.common.BusinessException;
import com.luxera.companion.contracts.provision.AgentRegistrationPort;
import com.luxera.companion.contracts.provision.AgentRegistrationRequest;
import com.luxera.companion.contracts.provision.RegisteredAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * {@link AgentRegistrationPort} 的 HTTP 适配器 —— 聊天平台调仿真 Agent 平台 openAPI 的
 * **唯一**通道。一键创建(需求 ④)第三步就走这里。
 *
 * <h2>它和隔壁 {@code HttpCompanionDirectoryAdapter} 不是同一条路, 别弄混</h2>
 *
 * 两个适配器打的是**同一个进程的不同的面**, 而且鉴权方式完全不同:
 *
 * <table border="1">
 *   <tr><th></th><th>目录适配器</th><th>本适配器</th></tr>
 *   <tr><td>端口</td><td><b>8091</b>(server)</td><td><b>8092</b>(openapi)</td></tr>
 *   <tr><td>路径</td><td>{@code /internal/**}</td><td>{@code /api/v1/openapi/**}</td></tr>
 *   <tr><td>身份</td><td>服务间 HMAC({@code X-Lap-Signature})</td><td>API Key({@code Authorization: Bearer sap_...})</td></tr>
 *   <tr><td>代表谁</td><td>聊天平台这个服务</td><td>一个**被平台标记为可信的 API 客户端**</td></tr>
 * </table>
 *
 * <p>走 openAPI 而不是 {@code /internal/**} 是本次需求明确选定的(见计划里的决策 3):
 * 一键创建要用的是**对外开放的同一个端点**, 第三方程序走的那条路。多一条内部捷径的话,
 * 聊天平台就成了唯一一个能创建 agent 而无需过闸门的调用方 —— 而那正是这道闸门要防的事。
 *
 * <h2>密钥从环境变量来</h2>
 *
 * {@code AGENT_PLATFORM_OPENAPI_KEY} 在 8092 侧登记为一个
 * {@code can_act_for_users = true} 的客户端。它<b>不进源码、不进 manifest、不进前端</b>
 * (CLAUDE.md 既定约束), 没配时本适配器**拒绝发请求**并说明是哪个变量 —— 一个"没配密钥
 * 就静默用空串去请求"的实现会把配置错误变成对面一个语焉不详的 401。
 *
 * <h2>远端状态码怎么翻</h2>
 *
 * <p>远端是"两台机器之间的 4xx", 与"浏览器和本服务之间的 4xx"不是一回事。逐个翻:
 * <ul>
 *   <li><b>401 / 503</b> → 本服务回 <b>502</b>。这两个码在本服务这一侧有别的含义
 *       —— 401 是"你没登录"、503 是"本服务没配好", 原样透传会把一次平台配置错误
 *       变成前端把用户踢去重新登录。它们的真实含义是"我们那把 key 不好使", 那是
 *       服务端的问题, 应该是 502。</li>
 *   <li><b>400 / 403 / 409</b> → 原样透传, 并把远端 {@code error} 那句带出来。这三个
 *       说的是调用方这边的事实(人格描述不合法 / 那把 key 没有代建权限 / 这个聊天账号
 *       已经登记过 agent), 用户看到原文才有得改。</li>
 *   <li><b>其余 / 不可达</b> → 502。对面 500 与连不上对调用方是同一件事: 稍后再试。</li>
 * </ul>
 *
 * <p>失败一律抛 {@link BusinessException}(而不是返回 null)—— 端口契约里写明了这一条,
 * 理由在 {@code AgentRegistrationPort} 的 javadoc: 调用方需要知道失败才能去补偿。
 */
@Slf4j
@Component
public class HttpAgentRegistrationAdapter implements AgentRegistrationPort {

    /** 配置里指向 8092 的那把 key 的变量名 —— 出错信息里点名它, 省掉一次翻代码。 */
    static final String KEY_ENV = "AGENT_PLATFORM_OPENAPI_KEY";

    private static final String PATH = "/api/v1/openapi/agents";

    private final String baseUrl;
    private final String apiKey;
    private final int timeoutMillis;
    private final ObjectMapper objectMapper;

    public HttpAgentRegistrationAdapter(
            @Value("${app.agent-platform.openapi-base-url:http://127.0.0.1:8092}") String baseUrl,
            @Value("${app.agent-platform.openapi-key:}") String apiKey,
            @Value("${app.agent-platform.timeout-ms:5000}") int timeoutMillis,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeoutMillis = timeoutMillis;
        this.objectMapper = objectMapper;
    }

    @Override
    public RegisteredAgent register(AgentRegistrationRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            // 死端点而不是"用空 key 试一次": 空 key 在对面是 401, 会被下面翻成 502,
            // 于是运维看到的是一条"对面不通"的日志 —— 而真相是本进程少一个环境变量。
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "聊天平台没有配置 Agent 平台的接入密钥",
                    "需要环境变量 " + KEY_ENV);
        }

        HttpURLConnection conn = null;
        try {
            conn = open(objectMapper.writeValueAsString(request));
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw translate(code, readError(conn));
            }
            try (InputStream in = conn.getInputStream()) {
                return readAgent(in);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[AgentRegistration] 调用 Agent 平台 openAPI 失败: {}", e.toString());
            throw new BusinessException(HttpStatus.BAD_GATEWAY,
                    "Agent 平台暂不可达", "稍后再试; 若持续失败请检查 " + baseUrl);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 反序列化回执。
     *
     * <p>{@code without(FAIL_ON_UNKNOWN_PROPERTIES)} 是**显式写的**, 不靠 Spring Boot 的
     * 默认值: 对面 8092 的 DTO 里还有 {@code clientId / status / createdAt} 三个本 record
     * 不认识的字段, 而它每加一个字段, 靠默认值活着的这行代码就会在某次部署后开始抛
     * —— 症状是"一键创建突然失败了", 而原因在另一个仓库里。
     */
    private RegisteredAgent readAgent(InputStream in) throws IOException {
        return objectMapper.readerFor(RegisteredAgent.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(in);
    }

    /**
     * 远端状态码 → 本服务的答复 —— 映射表与理由见类注释。
     *
     * <p>把远端 {@code error} 那句原文带出来: 它是对面写给人看的一句话
     * ("这个 API Key 没有代建的权限"), 比任何一个本地拼的笼统句子都有用。
     */
    private BusinessException translate(int code, String body) {
        String detail = extractError(body);
        HttpStatus status = switch (code) {
            case 400, 403, 409 -> HttpStatus.resolve(code);
            default -> HttpStatus.BAD_GATEWAY;
        };
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        String message = detail != null ? detail : ("Agent 平台返回 " + code);
        String hint = null;
        if (code == 401 || code == 403) {
            // 403 是"那把 key 没有代建权限"; 401 是"那把 key 根本不被认" —— 两者都要人去 8092 改配置
            hint = "检查 " + KEY_ENV + " 对应的客户端是否已在 Agent 平台登记为可信(canActForUsers)";
        } else if (code >= 500) {
            hint = "Agent 平台内部错误, 稍后再试";
        }
        log.warn("[AgentRegistration] Agent 平台返回 {}: {}", code, body);
        return new BusinessException(status, message, hint);
    }

    /** 取远端 {@code {"error": "..."}} 里那句话; 体不是这个形状就返回 null(不猜)。 */
    private String extractError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode err = node.get("error");
            return err != null && err.isTextual() && !err.asText().isBlank() ? err.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private HttpURLConnection open(String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + PATH).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMillis);
        conn.setReadTimeout(timeoutMillis);
        // openAPI 的客户端面就是 Bearer sap_... —— 与 8092 的 OpenApiAuthFilter 对应。
        // 刻意不用 X-Lap-Signature: 那是 8091 /internal/** 的服务身份, 8092 不认。
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
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
