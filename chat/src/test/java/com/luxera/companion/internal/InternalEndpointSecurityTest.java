package com.luxera.companion.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /internal/**} 在<b>过滤器链</b>上的可达性 —— G3 版的
 * {@code McpEndpointSecurityTest}。
 *
 * <p>同一个坑的第三次踩点预警: {@code SecurityConfig} 的
 * {@code anyRequest().authenticated()} 会把每一个 /internal 请求在 Spring Security
 * 过滤器上变成 403 —— 回 Spring 默认错误体, 根本到不了 {@code InternalAuthFilter}。
 * 而 agent-server 的 HTTP 适配器<b>没有 JWT</b> 可给: 它手里只有 HMAC 签名。
 * R14 在 MCP 与 Developer API 上各踩过一次（README 有记）, G3 差点第三次踩 ——
 * 本类的存在就是让"过滤器放行了 /internal"这件事被钉死。
 *
 * <p>三条断言:
 * <ol>
 *   <li><b>放行 + 验签通过</b> —— 带正确 HMAC 签名, 请求真的到达 controller
 *       （2xx, capabilities 列表可读）。</li>
 *   <li><b>不是敞开</b> —— 没签名时 401, 由 InternalAuthFilter 拒, 而不是 permitAll
 *       之后谁都能调。</li>
 *   <li><b>时间窗</b> —— 过期时间戳的签名被拒（重放防御, 纯函数级验证）。</li>
 * </ol>
 */
@ActiveProfiles("test")
@SpringBootTest(classes = com.luxera.chatserver.ChatPlatformApplication.class)
@Import(InternalEndpointSecurityTest.StubAgentPlatform.class)
@AutoConfigureMockMvc
class InternalEndpointSecurityTest {

    /** 与 application-test.yml 里的 app.agent-platform.internal-service-key 一致。 */
    private static final String KEY = "test-internal-service-key";

    @TestConfiguration
    static class StubAgentPlatform {
        @Bean
        @Primary
        CompanionDirectoryPort stubCompanionDirectory() {
            return new CompanionDirectoryPort() {
                @Override
                public CompanionRef requireOwned(String userId, String companionId) {
                    return new CompanionRef(companionId, "桩数字人", companionId);
                }

                @Override
                public void onUserMessage(String userId, String companionId, String conversationId,
                                          List<MessageView> messages) {
                    // 桩: fire-and-forget 上什么都不用做
                }
            };
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void aSignedRequestReachesTheControllerNotTheFilterChain() throws Exception {
        // 读端点没有请求体 —— GET /internal/runtime/capabilities
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = InternalSignature.sign(KEY, timestamp, "");

        MvcResult result = mockMvc.perform(get("/internal/runtime/capabilities")
                        .header(InternalSignature.HEADER_TIMESTAMP, timestamp)
                        .header(InternalSignature.HEADER_SIGNATURE, signature)
                        .header(InternalSignature.HEADER_SERVICE, "agent"))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        assertNotNull(result.getResponse().getContentAsString(),
                "到达 controller: capabilities 列表可读(而非 Spring 403 默认体)");
    }

    @Test
    void aSignedPostRequestReachesTheControllerToo() throws Exception {
        String body = "{}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = InternalSignature.sign(KEY, timestamp, body);

        // POST 一个不存在的会话 ensure —— 到达 controller 的证据是业务错误(4xx 带
        // 业务体/500), 而不是 Spring 的 403 默认体。选一个轻端点: mark-read(只带 id 列表)
        mockMvc.perform(post("/internal/world/messages/mark-read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(InternalSignature.HEADER_TIMESTAMP, timestamp)
                        .header(InternalSignature.HEADER_SIGNATURE, signature)
                        .header(InternalSignature.HEADER_SERVICE, "agent"))
                .andExpect(result -> assertTrue(result.getResponse().getStatus() != 401 && result.getResponse().getStatus() != 403,
                        "签名合法的 POST 不该被过滤器拦下, 实际状态码: "
                                + result.getResponse().getStatus()));
    }

    @Test
    void anUnsignedRequestIsRefusedByTheInternalFilterNotByTheController() throws Exception {
        mockMvc.perform(get("/internal/runtime/capabilities"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBadSignatureIsRefused() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        mockMvc.perform(get("/internal/runtime/capabilities")
                        .header(InternalSignature.HEADER_TIMESTAMP, timestamp)
                        .header(InternalSignature.HEADER_SIGNATURE, "sha256=deadbeef"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStaleTimestampIsRejectedAsReplay() {
        // 1 小时前的时间戳 —— 超出 CLOCK_SKEW_SECONDS(300s), 签名本身算得再对也是重放。
        String body = "";
        String stale = String.valueOf(Instant.now().getEpochSecond() - 3600);
        String signature = InternalSignature.sign(KEY, stale, body);
        assertFalse(InternalSignature.verify(KEY, stale, body, signature),
                "过期时间戳的合法签名也必须被拒");

        String fresh = String.valueOf(Instant.now().getEpochSecond());
        assertTrue(InternalSignature.verify(KEY, fresh, body,
                        InternalSignature.sign(KEY, fresh, body)),
                "新鲜时间戳的同签名必须通过");
    }
}
