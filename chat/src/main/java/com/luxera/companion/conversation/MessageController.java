package com.luxera.companion.conversation;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.contracts.api.MessageView;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §十一~§十六 POST /messages: 用户消息**同步落库** + clientMessageId 幂等。
 *
 * 与 的关键区别:
 * - 消息在 AgentRuntime 异步线程落库 → 刷新即丢/延迟出现。
 * - 消息在 HTTP 请求内落库(MessageCoreService), 返回 canonical messageId;
 *       事务提交后经 Outbox 异步触发 Agent —— 消息永久存在, Agent 永不阻塞请求。
 *
 * 前端: 乐观消息(temp-xxx)带 clientMessageId 上屏, 收到 201 后替换为 canonical;
 * 后续状态变化/回复全部经 GET /events(带游标)推送, 不再整表重载。
 */
@RestController
@RequestMapping("/api/companions/{companionId}/conversations/{conversationId}/messages")
public class MessageController {

    private final CurrentUser currentUser;
    private final MessageCoreService messageCoreService;

    public MessageController(CurrentUser currentUser, MessageCoreService messageCoreService) {
        this.currentUser = currentUser;
        this.messageCoreService = messageCoreService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> send(
            @PathVariable String companionId,
            @PathVariable String conversationId,
            @RequestBody(required = false) SendRequest req) {
        String userId = currentUser.requireUserId();

        List<MessageCoreService.SendItem> items = resolveItems(req);
        MessageCoreService.SendResult result = messageCoreService.send(userId, companionId, conversationId, items);

        MessageView last = result.last();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (MessageView m : result.getMessages()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.getId());
            mm.put("clientMessageId", m.getClientMessageId());
            mm.put("content", m.getContent());
            mm.put("conversationId", m.getConversationId());
            mm.put("senderType", m.getSenderType());
            mm.put("deliveryStatus", m.getDeliveryStatus());
            mm.put("createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString());
            messages.add(mm);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", result.getStatus(),
                "messageId", last != null ? last.getId() : "",
                "messages", messages));
    }

    private List<MessageCoreService.SendItem> resolveItems(SendRequest req) {
        List<MessageCoreService.SendItem> out = new ArrayList<>();
        if (req == null) return out;
        if (req.getMessages() != null && !req.getMessages().isEmpty()) {
            for (SendItem item : req.getMessages()) {
                if (item == null) continue;
                MessageCoreService.SendItem si = new MessageCoreService.SendItem();
                si.setContent(item.getContent());
                si.setClientMessageId(item.getClientMessageId());
                out.add(si);
            }
            return out;
        }
        if (req.getContent() != null && !req.getContent().isBlank()) {
            MessageCoreService.SendItem si = new MessageCoreService.SendItem();
            si.setContent(req.getContent().trim());
            si.setClientMessageId(req.getClientMessageId());
            out.add(si);
        }
        return out;
    }

    @Data
    public static class SendRequest {
        private String content;
        private String clientMessageId;
        private List<SendItem> messages;
    }

    @Data
    public static class SendItem {
        private String content;
        private String clientMessageId;
    }
}
