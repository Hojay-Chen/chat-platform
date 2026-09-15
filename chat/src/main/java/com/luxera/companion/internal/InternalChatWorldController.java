package com.luxera.companion.internal;

import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.conversation.ChatWorldAdapter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * chat 平台对仿真 Agent 平台开放的服务间端点 —— {@code ChatWorldPort} 的 HTTP 面。
 * 仓 2 {@code HttpChatWorldAdapter} 调用这里。
 *
 * <p>每个方法一行委托到进程内真实现 {@link ChatWorldAdapter} —— 本类不做任何业务
 * 判断, 世界怎么读、怎么写是 {@code ChatWorldPort} 契约(V10 §3.1)说了算的, HTTP 面
 * 只是把它暴露给另一个进程。
 *
 * <p>鉴权由 {@link InternalAuthFilter} 前置完成(HMAC 签名), 本类的所有端点都在
 * {@code /internal/**} 下。
 *
 * <p><b>时间参数必须显式标 {@code @DateTimeFormat(iso = DATE_TIME)}。</b>
 * 仓 2 用 {@code ISO_LOCAL_DATE_TIME} 格式化后拼进 query string, 而
 * {@code @RequestParam LocalDateTime} 不带该注解时走的是 Spring Boot 的**本地化**
 * 默认格式器(非 ISO)—— 于是每一个带 {@code since}/{@code until} 的调用都在本类
 * 抛 {@code DateTimeParseException} → 500。症状极隐蔽: 仓 2 只记一条"读世界失败"
 * 的 WARN 然后降级, 数字人静默少掉一块用户历史上下文, 不报错、不缺页、只是变笨。
 * 见 {@code InternalWorldTimeParamTest}(它钉的就是这个坑)。
 */
@RestController
@RequestMapping("/internal/world")
public class InternalChatWorldController {

    private final ChatWorldPort chatWorld;   // 进程内真实现; 经端口类型引用而非具体类

    public InternalChatWorldController(ChatWorldPort chatWorld) {
        this.chatWorld = chatWorld;
    }

    // ── 读 ─────────────────────────────────────────────────────────────────

    @GetMapping("/conversations/{conversationId}/messages")
    public List<MessageView> messages(@PathVariable String conversationId,
                                      @RequestParam(required = false) Integer limit) {
        return limit == null
                ? chatWorld.messages(conversationId)
                : chatWorld.recentMessages(conversationId, limit);
    }

    @GetMapping("/messages/{messageId}")
    public Optional<MessageView> message(@PathVariable String messageId) {
        return chatWorld.message(messageId);
    }

    @GetMapping("/companions/{companionId}/user-messages")
    public List<MessageView> userMessagesSince(@PathVariable String companionId,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        return chatWorld.userMessagesSince(companionId, since);
    }

    @GetMapping("/companions/{companionId}/window")
    public List<MessageView> messagesBetween(@PathVariable String companionId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until) {
        return chatWorld.messagesBetween(companionId, since, until);
    }

    @GetMapping("/companions/{companionId}/by-kind")
    public List<MessageView> recentByKind(@PathVariable String companionId,
                                         @RequestParam String kind,
                                         @RequestParam int limit) {
        return chatWorld.recentByCompanionAndKind(companionId, kind, limit);
    }

    @GetMapping("/companions/{companionId}/count-by-kind")
    public Map<String, Long> countByKind(@PathVariable String companionId,
                                         @RequestParam String kind,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        return Map.of("count", chatWorld.countByCompanionAndKindSince(companionId, kind, since));
    }

    @GetMapping("/conversations/{conversationId}")
    public Optional<ConversationView> conversation(@PathVariable String conversationId) {
        return chatWorld.conversation(conversationId);
    }

    @GetMapping("/threads")
    public List<ConversationView> conversations(@RequestParam String userId,
                                                @RequestParam String companionId) {
        return chatWorld.conversations(userId, companionId);
    }

    @GetMapping("/threads-of/{companionId}")
    public List<ConversationView> conversationsOf(@PathVariable String companionId) {
        return chatWorld.conversationsOf(companionId);
    }

    @GetMapping("/thread-for")
    public Optional<ConversationView> conversationFor(@RequestParam String userId,
                                                      @RequestParam String companionId) {
        return chatWorld.conversationFor(userId, companionId);
    }

    // ── 写/变更 ────────────────────────────────────────────────────────────

    @PostMapping("/conversations/ensure")
    public ConversationView ensureConversation(@RequestBody EnsureConversationBody body) {
        return chatWorld.ensureConversation(body.userId(), body.companionId(), body.companionName());
    }

    @PostMapping("/messages")
    public MessageView append(@RequestBody MessageAppendCommand command) {
        return chatWorld.append(command);
    }

    // ── fire-and-forget ─────────────────────────────────────────────────────

    @PostMapping("/messages/mark-read")
    public void markRead(@RequestBody MarkReadBody body) {
        chatWorld.markRead(body.companionId(), body.messageIds());
    }

    @PostMapping("/messages/delivery-status")
    public void updateDeliveryStatus(@RequestBody DeliveryStatusBody body) {
        chatWorld.updateDeliveryStatus(body.companionId(), body.messageIds(), body.status());
    }

    /** PATCH 经 X-HTTP-Method-Override 伪装(HttpURLConnection 不支持 PATCH)。 */
    @PostMapping("/messages/{messageId}/perception")
    public void updatePerception(@PathVariable String messageId,
                                 @RequestBody PerceptionBody body) {
        chatWorld.updatePerception(messageId, body.intent(), body.emotion(), body.topic());
    }

    @PostMapping("/events")
    public void publishEvent(@RequestBody PublishEventBody body) {
        chatWorld.publishEvent(body.companionId(), body.type(), body.payload());
    }

    @PostMapping("/boundaries")
    public void recordBoundary(@RequestBody BoundaryBody body) {
        chatWorld.recordBoundary(body.companionId(), body.conversationId(),
                body.type(), body.reason());
    }

    @PostMapping("/threads/touch")
    public void touchThread(@RequestBody TouchThreadBody body) {
        chatWorld.touchThread(body.companionId(), body.conversationId(),
                body.topic(), body.emotion());
    }

    // ── 请求体 ──────────────────────────────────────────────────────────────

    public record EnsureConversationBody(String userId, String companionId, String companionName) {}
    public record MarkReadBody(String companionId, Collection<String> messageIds) {}
    public record DeliveryStatusBody(String companionId, Collection<String> messageIds, String status) {}
    public record PerceptionBody(String intent, String emotion, String topic) {}
    public record PublishEventBody(String companionId, String type, Map<String, Object> payload) {}
    public record BoundaryBody(String companionId, String conversationId, String type, String reason) {}
    public record TouchThreadBody(String companionId, String conversationId, String topic, String emotion) {}
}
