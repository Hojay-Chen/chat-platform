package com.luxera.companion.internal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /internal/world 的时间参数必须吃下调用方真正发来的那种串。
 *
 * <p>仓 2 的 {@code HttpChatWorldAdapter} 用 {@code DateTimeFormatter.ISO_LOCAL_DATE_TIME}
 * 格式化后拼进 query string, 形如 {@code 2026-09-16T02:06:02.399480610} —— ISO 的
 * 纳秒位是 0~9 位变长, 于是**秒的小数部分位数随当前时刻的纳秒值浮动**: 整秒时
 * 一位都没有, 纳秒非整时可能 9 位。这不是"某一种格式", 是一族格式。
 *
 * <p>{@code @RequestParam LocalDateTime} 不带 {@code @DateTimeFormat} 时, 走的是
 * Spring Boot 给 WebConversionService 装的默认日期格式器, 而那个默认**不是** ISO。
 * 结果是: 一批本该成功的服务间读请求在 8081 抛 DateTimeParseException → 500,
 * 仓 2 侧只看到"读世界失败"的 WARN 然后降级 —— 数字人读不到用户历史消息, 且
 * 这个故障**不报错、只是静默少一块上下文**。G8 验证 SSE 转发时在日志里发现的。
 *
 * <p>本测试钉的是"这个 query string 形态必须被接受", 而不是"必须用 ISO 解析" ——
 * 将来换实现只要还认这种串就仍然绿。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = com.luxera.chatserver.ChatPlatformApplication.class)
@AutoConfigureMockMvc
class InternalWorldTimeParamTest {

    /** 与 application-test.yml 里的 app.agent-platform.internal-service-key 一致。 */
    private static final String KEY = "test-internal-service-key";

    @Autowired
    MockMvc mockMvc;

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(String uri) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return get(uri)
                .header(InternalSignature.HEADER_TIMESTAMP, timestamp)
                .header(InternalSignature.HEADER_SIGNATURE, InternalSignature.sign(KEY, timestamp, ""))
                .header(InternalSignature.HEADER_SERVICE, "agent");
    }

    /**
     * 仓 2 实际发出的形态, 覆盖小数位数的两端 —— 9 位纳秒(非整秒)与 0 位(整秒)。
     * 只测 9 位会漏掉"整秒时串更短"的那一半; 两种都必须能过。
     */
    @Test
    void isoLocalDateTimeQueryParamsAreAccepted() throws Exception {
        String[] stamps = {
                "2026-09-16T02:06:02.399480610",   // 9 位纳秒
                "2026-09-16T02:06:02.39948061",    // 8 位(日志里抓到的那个)
                "2026-09-16T02:06:02",             // 整秒 —— 一位小数都没有
        };
        for (String since : stamps) {
            mockMvc.perform(signed("/internal/world/companions/c1/user-messages?since=" + since))
                    .andExpect(status().isOk());
        }
    }

    /** 两个参数都要过 —— window 是 since + until 同时出现的那条路径。 */
    @Test
    void bothEndsOfTheWindowParse() throws Exception {
        mockMvc.perform(signed("/internal/world/companions/c1/window"
                        + "?since=2026-09-16T02:06:02.399480610"
                        + "&until=2026-09-16T03:00:00"))
                .andExpect(status().isOk());
    }

    /** count-by-kind 的 since 也是同一族参数, 别只修看得见的那两条。 */
    @Test
    void countByKindSinceParsesToo() throws Exception {
        mockMvc.perform(signed("/internal/world/companions/c1/count-by-kind"
                        + "?kind=user&since=2026-09-16T02:06:02.399480610"))
                .andExpect(status().isOk());
    }
}
