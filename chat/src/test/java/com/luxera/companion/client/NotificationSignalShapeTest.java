package com.luxera.companion.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.luxera.companion.contracts.client.ClientStreamFrame;
import com.luxera.companion.contracts.client.NotificationSignal;
import com.luxera.companion.contracts.events.PhoneNotificationPayload;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §6.1 / §8.2.3 第 3 条 —— <b>「通知里没有正文」这件事必须由类型本身保证</b>。
 *
 * <h2>为什么这条断言要写成反射, 而不是"我读了代码, 里面确实没有正文"</h2>
 *
 * <p>因为"通知不带内容"是一个**会被后来的改动破坏**的性质, 而破坏它的方式极其自然: 有人想
 * 让客户端的列表页少发一次请求, 于是往信号里加一个 {@code preview}; 有人要显示"谁发的",
 * 于是加一个 {@code senderName}。两次改动都会通过代码评审 —— 它们看起来只是在"补一个字段",
 * 而且编译、序列化、客户端解析全都正常工作。真正坏掉的是那条业务性质(§8.2.4: "她还没读就
 * 知道内容"必须是不可能的状态), 而它没有任何测试在看。
 *
 * <p>所以这里看的不是"某一次实现有没有写漏", 而是这个类型的<b>分量集合</b>本身。任何人往
 * {@link NotificationSignal} 上加字段, 这条用例都会红, 而它红的地方正是他需要停下来想一次
 * 的地方: 这一个字段是不是内容?
 *
 * <h2>反面对照: {@link PhoneNotificationPayload} 为什么没有被复用</h2>
 *
 * <p>V10 里已经有一个"手机通知"的载荷, 而它带着 {@code preview} 与 {@code privacyMode}
 * —— 也就是说它可以被调用方**打开成 FULL_PREVIEW**。§6.1 要的是"通知里永远没有内容",
 * 那不是一个默认值的问题, 而是一个"这个开关不该存在"的问题: 只要开着这个开关是可能的,
 * 某一个调用方就会把它打开, 而服务端无从拒绝。{@link #theOldPhonePayloadIsTheCounterExample}
 * 把这个对照钉在测试里, 免得将来有人"为了少写一个类型"把它接回来。
 *
 * <p>这个类**不需要 Spring 上下文**: 它读的是类型, 不是任何运行态的东西。让它进 Spring
 * 只会让它慢, 而它要防的那种改动与容器无关。
 */
class NotificationSignalShapeTest {

    /**
     * 与线上<b>同一个形状</b>的序列化器 —— 手工把 Spring Boot 那三条隐式配置补齐:
     *
     * <ul>
     *   <li>{@code findAndRegisterModules()} 补上 {@code JavaTimeModule}, 否则
     *       {@link LocalDateTime} 直接抛;</li>
     *   <li>关掉 {@code WRITE_DATES_AS_TIMESTAMPS}, 否则时刻会序列化成
     *       {@code [2026,9,19,10,30]} 这样的数组 —— 而线上发出去的是 ISO-8601 文本。
     *       这一条不是细节: 断言"线上只有那四个键"时, 若时刻变成数组, 那条断言看的就不是
     *       真实形状了;</li>
     *   <li>{@code NON_NULL}, 与 {@code spring.jackson.default-property-inclusion: non_null}
     *       一致 —— 少了它, "线上有没有 {@code reason} 这个键"会被答成"有, 值是 null",
     *       而那是测试侧的配置在说话, 不是端点真的会发出去的东西。</li>
     * </ul>
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * 分量集合是精确的 —— 多一个、少一个、改一个类型都会红。
     *
     * <p>"少了"同样要红: 一个不含 {@code conversationId} 的信号, 客户端没法知道该点开谁,
     * 于是它只能退回去拉整个列表 —— 而那是"一条通知触发了 N 次读取"的来源。
     */
    @Test
    void theSignalHasExactlyFourMetaFields() {
        List<String> names = Arrays.stream(NotificationSignal.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
        assertEquals(List.of("signalId", "conversationId", "fromAccountId", "raisedAt"), names,
                "通知信号的分量集合变了。加字段之前先问一次: 它是内容吗? "
                        + "内容只能从 GET /api/client/conversations/{accountId}/messages 出来。");

        assertEquals(long.class, componentOf("signalId").getType(),
                "signalId 必须能比较大小(补发时按它推进 lastAckSignalId), 所以它是数字而不是 UUID");
        assertEquals(String.class, componentOf("conversationId").getType());
        assertEquals(String.class, componentOf("fromAccountId").getType());
        assertEquals(LocalDateTime.class, componentOf("raisedAt").getType());
    }

    /**
     * 分量名里不能出现任何一个"内容"词。
     *
     * <p>与上面那条是两道不同的闸: 上面那条锁住"现在是这四个", 这一条锁住"将来加的那一个不能
     * 是内容"。只留上面那条的话, 一次合理的改动会让它红, 而**改测试的人**面对的是一个等价于
     * "这四个名字不对"的失败信息 —— 他更新一下期望列表就绿了, 而那个新字段是不是内容, 没有
     * 任何东西在问。这条用例把那个问题写成了断言。
     */
    @Test
    void noComponentNameSmellsLikeContent() {
        Set<String> forbidden = Set.of("content", "text", "body", "preview", "title",
                "sender", "name", "avatar", "attachment", "metadata", "payload");
        for (RecordComponent c : NotificationSignal.class.getRecordComponents()) {
            String lower = c.getName().toLowerCase(Locale.ROOT);
            for (String word : forbidden) {
                assertFalse(lower.contains(word),
                        "分量 " + c.getName() + " 的名字里有 '" + word + "' —— "
                                + "请确认它不是一个会把内容带到手机上的字段");
            }
        }
    }

    /**
     * 序列化之后, 网上跑的字节里也只有那四个键。
     *
     * <p>反射看的是 Java 类型, 而真正离开这台机器的是 JSON。一个 {@code @JsonProperty}
     * 别名、一个 getter 派生出来的字段, 都能让"类型里没有"与"线上没有"变成两件事
     * ({@code default-property-inclusion: non_null} 也只影响空值)。
     */
    @Test
    void theWireFormatCarriesNothingElse() throws Exception {
        NotificationSignal signal = new NotificationSignal(
                17L, "conv-1", "acc-9", LocalDateTime.of(2026, 9, 19, 10, 30));
        JsonNode json = mapper.readTree(mapper.writeValueAsString(signal));

        List<String> keys = new ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("signalId", "conversationId", "fromAccountId", "raisedAt"), keys,
                "线上形状多出了字段: " + json);

        // 逐字段确认没有一个是正文: 四个值只能是数字、两个 id、一个时刻
        assertEquals(17L, json.path("signalId").asLong());
        assertEquals("conv-1", json.path("conversationId").asText());
        assertEquals("acc-9", json.path("fromAccountId").asText());
        assertTrue(json.path("raisedAt").isTextual(), "时刻应当是文本(ISO-8601), 实际: " + json);
    }

    /**
     * WS 上唯一"有内容"的那种帧, 也没有可承载正文的地方。
     *
     * <p>{@link ClientStreamFrame} 的四个分量是 {@code type/signal/reason/lastAckSignalId}
     * —— 一个通知帧里能放东西的位置只有 {@code reason}, 而它服务的是
     * {@code SESSION_EXPIRED} 与 {@code ERROR} 这两种**服务端说自己出了什么事**的帧。
     * 把正文塞进 {@code reason} 是唯一一种可能的绕过, 所以这里把它钉住: 一条
     * {@code NOTIFICATION} 帧的 {@code reason} 必须是空的。
     */
    @Test
    void aNotificationFrameHasNowhereToPutContent() throws Exception {
        NotificationSignal signal = new NotificationSignal(
                3L, "conv-2", "acc-1", LocalDateTime.of(2026, 9, 19, 11, 0));
        JsonNode json = mapper.readTree(
                mapper.writeValueAsString(ClientStreamFrame.notification(signal)));

        assertEquals(ClientStreamFrame.TYPE_NOTIFICATION, json.path("type").asText());
        assertFalse(json.path("reason").isTextual(),
                "NOTIFICATION 帧的 reason 必须是空的 —— 它是唯一一个能放正文的自由文本字段");
        assertEquals(0L, json.path("lastAckSignalId").asLong(),
                "lastAckSignalId 是客户端 → 服务端的方向, 服务端发出去的帧上它恒为 0");

        List<String> keys = new ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("type", "signal", "lastAckSignalId"), keys,
                "线上形状多出了字段: " + json);
    }

    /**
     * 反面对照 —— 旧的手机通知载荷<b>可以</b>带上正文与发件人名。
     *
     * <p>它没有被复用的原因就在这里, 而不是因为它"旧"。§6.1 说"每一条消息一条通知信号、
     * 绝不聚合、不带内容", 而一个带 {@code privacyMode}(FULL_PREVIEW / SENDER_ONLY /
     * NO_PREVIEW)的类型把"带不带内容"变成了**调用方的选择** —— 服务端只能祈祷每个调用方都
     * 选 NO_PREVIEW, 而它没有任何办法拒绝一个选了别的值的请求。
     *
     * <p>这条断言在说: 这个类型**确实**有那个能力(而不是"它其实也没带内容, 白写一个类型")。
     * 于是"为什么另起一个"有一个可以被检验的答案。
     */
    @Test
    void theOldPhonePayloadIsTheCounterExample() {
        List<String> names = Arrays.stream(PhoneNotificationPayload.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
        assertTrue(names.contains("preview"),
                "旧的手机通知载荷带 preview —— 这正是 §6.1 不允许的字段, 也是它没有被复用的原因");
        assertTrue(names.contains("privacyMode"),
                "而 privacyMode 让'带不带内容'成了调用方的选择: 服务端拒绝不了 FULL_PREVIEW");
        assertTrue(names.contains("senderName"),
                "senderName 同样是内容侧的信息 —— 通知里只有 fromAccountId 这个 id");

        // 反过来: 新的信号里一个都不该有
        List<String> signalNames = Arrays.stream(NotificationSignal.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
        for (String leaky : List.of("preview", "privacyMode", "senderName")) {
            assertFalse(signalNames.contains(leaky), "NotificationSignal 不该有 " + leaky);
        }
    }

    private static RecordComponent componentOf(String name) {
        for (RecordComponent c : NotificationSignal.class.getRecordComponents()) {
            if (c.getName().equals(name)) return c;
        }
        throw new AssertionError("NotificationSignal 里没有分量 " + name);
    }
}
