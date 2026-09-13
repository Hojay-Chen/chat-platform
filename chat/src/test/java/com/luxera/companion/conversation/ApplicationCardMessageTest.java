package com.luxera.companion.conversation;

import com.luxera.chatframework.ChatPlatformTestApplication;
import com.luxera.chatframework.ChatTestApplicationCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAP v2 §66: <b>应用卡片就是一条消息</b> —— 不是一个新的概念, 不是一张新的表。
 *
 * <p>这一组断言钉的是"卡片没有变成第二种东西": 它有 senderType、有 messageKind、有 metadata,
 * 并且和别的消息一起排在同一条时间线上。任何一条不成立, 就意味着聊天里多了一种需要
 * 单独分页、单独算已读、单独排序的实体 —— 而那正是 §66 选择"做成一条消息"要避免的。
 *
 * <p>另一条同样重要的是<em>它不唤醒数字人</em>: 平台通告不是用户说的话, 把它喂给 LLM 会让
 * 数字人对着"「纸飞机」已在这段对话里开启"生成一句回复, 而那句话是它自己编的。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
class ApplicationCardMessageTest {

    @Autowired
    ConversationApplicationService applications;
    @Autowired
    ConversationRepository conversations;
    @Autowired
    ConversationService conversationService;
    @Autowired
    MessageRepository messages;
    @Autowired
    com.luxera.chatframework.ChatTestApplicationCatalog catalogue;

    private String companionId;
    private String conversationId;

    @BeforeEach
    void setUp() {
        catalogue.reset();
        companionId = UUID.randomUUID().toString();
        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId("r12-card-user");
        conv.setCompanionId(companionId);
        conv.setTitle("R12 卡片");
        conversations.save(conv);
        conversationId = conv.getId();
    }

    @Test
    void 开应用会落下一条能被认出来的卡片消息() {
        ConversationApplicationService.OpenResult opened = applications.open("r12-card-user",
                companionId, conversationId, new ConversationApplicationService.OpenRequest(
                        ChatTestApplicationCatalog.APPLICATION_ID, null, null, null, null));

        Message card = messages.findById(opened.messageId()).orElseThrow();
        assertEquals(ConversationApplicationService.KIND_APPLICATION_CARD, card.getMessageKind());
        // 平台通告: 既不是用户说的, 也不是数字人说的。
        assertEquals("system", card.getSenderType());
        assertEquals(conversationId, card.getConversationId());

        Map<String, Object> metadata = card.getMetadata();
        assertNotNull(metadata, "卡片消息的全部意义都在 metadata 里");
        assertEquals(ChatTestApplicationCatalog.APPLICATION_ID, metadata.get("applicationId"));
        assertEquals(opened.launch().session().sessionId(), metadata.get("sessionId"));
        assertEquals(ChatTestApplicationCatalog.APPLICATION_NAME, metadata.get("name"));
        assertEquals("OWNER", metadata.get("role"));
        assertEquals("ACTIVE", metadata.get("status"));

        // 认不出 messageKind 的客户端把它当普通消息显示 —— 所以 content 必须是一句人能读的话。
        assertTrue(card.getContent().contains(ChatTestApplicationCatalog.APPLICATION_NAME),
                "卡片消息的 content 要能独立成立, 而不是一句空白: " + card.getContent());
    }

    @Test
    void 卡片消息和普通消息排在同一条时间线上() {
        conversationService.addMessage(conversationId, "user", "先聊两句", null);
        applications.open("r12-card-user", companionId, conversationId,
                new ConversationApplicationService.OpenRequest(
                        ChatTestApplicationCatalog.APPLICATION_ID, null, null, null, null));

        List<Message> timeline = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        assertEquals(2, timeline.size(), "卡片没有落在另一个地方 —— 它就在消息流里");
        assertEquals("user", timeline.get(0).getSenderType());
        assertEquals(ConversationApplicationService.KIND_APPLICATION_CARD,
                timeline.get(1).getMessageKind());
        // 会话的消息计数也把它算进去了 —— 否则"这段对话有几条消息"会有两个答案。
        assertEquals(2, conversations.findById(conversationId).orElseThrow().getMessageCount());
    }

    @Test
    void 分享链接也是一条消息_而且带得动明文令牌() {
        ConversationApplicationService.OpenResult opened = applications.open("r12-card-user",
                companionId, conversationId, new ConversationApplicationService.OpenRequest(
                        ChatTestApplicationCatalog.APPLICATION_ID, null, null, null, null));

        ConversationApplicationService.ShareResult shared = applications.share("r12-card-user",
                companionId, conversationId, opened.launch().session().sessionId(), "MEMBER", 5);

        assertNotNull(shared.invitation().token(), "铸造的那一次必须给明文, 否则链接拼不出来");
        assertNotNull(shared.invitation().joinUrl());

        Message invite = messages.findById(shared.messageId()).orElseThrow();
        assertEquals(ConversationApplicationService.KIND_APPLICATION_INVITATION, invite.getMessageKind());
        assertEquals(shared.invitation().joinUrl(), invite.getMetadata().get("joinUrl"));
        assertEquals(opened.launch().session().sessionId(), invite.getMetadata().get("sessionId"));
        assertTrue(invite.getContent().contains(shared.invitation().joinUrl()),
                "点不开的邀请消息是没用的邀请消息");
    }

    @Test
    void 开应用被拒时绝不留下一条说假话的卡片() {
        // 先开一局, 再让应用下架 —— 于是第二次 open 会被拒(与真实平台的 §4.1 一样)。
        applications.open("r12-card-user", companionId, conversationId,
                new ConversationApplicationService.OpenRequest(
                        ChatTestApplicationCatalog.APPLICATION_ID, null, null, null, null));
        long before = messages.count();

        catalogue.allowsNewSession = false;
        assertThrows(RuntimeException.class, () -> applications.open("r12-card-user", companionId,
                conversationId, new ConversationApplicationService.OpenRequest(
                        ChatTestApplicationCatalog.APPLICATION_ID, null, null, null, null)));

        assertEquals(before, messages.count(), "先开应用再落消息的顺序, 挡的就是这条假历史");
    }
}
