package com.luxera.companion.conversation;

import com.luxera.chatframework.ChatPlatformTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §十一~§十三 Chat Core 核心测试:
 * 1. 用户消息同步落库(请求事务内即可读到)
 * 2. clientMessageId 幂等(同会话重复提交返回同一条, 不重复入库)
 * 3. 返回 canonical messageId(真实 id, 非占位符)
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
class MessageCoreServiceTest {

    @Autowired
    MessageCoreService messageCoreService;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    MessageRepository messageRepository;

    private String companionId;
    private String conversationId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();

        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId("v8-user");
        conv.setCompanionId(companionId);
        conv.setTitle("测试");
        conversationRepository.save(conv);
        conversationId = conv.getId();
    }

    @Test
    void userMessagePersistedSynchronously() {
        MessageCoreService.SendItem item = new MessageCoreService.SendItem();
        item.setContent("你好呀");
        item.setClientMessageId("c-001");

        MessageCoreService.SendResult result = messageCoreService.send(
                "v8-user", companionId, conversationId, List.of(item));

        // canonical id 是真实 UUID, 不是内容占位
        assertNotNull(result.last());
        assertNotEquals("你好呀", result.last().getId());
        assertEquals("c-001", result.last().getClientMessageId());
        assertEquals(conversationId, result.last().getConversationId());

        // 事务内即可读到(同步落库)
        List<Message> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        assertEquals(1, all.size());
        assertEquals("你好呀", all.get(0).getContent());
        assertEquals("user", all.get(0).getSenderType());
        assertEquals("DELIVERED", all.get(0).getDeliveryStatus());
    }

    @Test
    void duplicateClientMessageIdIsIdempotent() {
        MessageCoreService.SendItem item = new MessageCoreService.SendItem();
        item.setContent("幂等测试");
        item.setClientMessageId("c-dup");

        MessageCoreService.SendResult first = messageCoreService.send(
                "v8-user", companionId, conversationId, List.of(item));
        MessageCoreService.SendResult second = messageCoreService.send(
                "v8-user", companionId, conversationId, List.of(item));

        assertEquals(first.last().getId(), second.last().getId(), "重复提交应返回同一条消息");
        long count = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> "user".equals(m.getSenderType()))
                .count();
        assertEquals(1, count, "同 clientMessageId 只入库一次");
    }

    @Test
    void batchMessagesPersistedInOrder() {
        MessageCoreService.SendItem a = new MessageCoreService.SendItem();
        a.setContent("第一条");
        a.setClientMessageId("c-a");
        MessageCoreService.SendItem b = new MessageCoreService.SendItem();
        b.setContent("第二条");
        b.setClientMessageId("c-b");

        MessageCoreService.SendResult result = messageCoreService.send(
                "v8-user", companionId, conversationId, List.of(a, b));

        assertEquals(2, result.getMessages().size());
        List<Message> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        assertEquals(2, all.size());
        assertEquals("第一条", all.get(0).getContent());
        assertEquals("第二条", all.get(1).getContent());
    }

    @Test
    void emptyMessageRejected() {
        MessageCoreService.SendItem empty = new MessageCoreService.SendItem();
        empty.setContent("   ");
        assertThrows(IllegalArgumentException.class, () -> messageCoreService.send(
                "v8-user", companionId, conversationId, List.of(empty)));
    }
}
