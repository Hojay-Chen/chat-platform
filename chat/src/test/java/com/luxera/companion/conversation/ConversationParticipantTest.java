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
 * §五十二 ConversationParticipant 测试:
 * 会话创建后自动注册 Agent + User 两个参与者(一对一聊天即最小参与者图;
 * 未来群聊 = 多参与者 + 各自关系)。
 *
 * <p>V10: 参与者只存不透明的 memberId —— chat 不知道对面是数字人、人格表长什么样。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
class ConversationParticipantTest {

    @Autowired
    ConversationService conversationService;
    @Autowired
    ConversationParticipantRepository participantRepository;

    private final String companionId = UUID.randomUUID().toString();
    private final String userId = "cp-user";

    private String conversationId;

    @BeforeEach
    void setUp() {
        conversationId = conversationService
                .create(userId, companionId, "测试会话", "林夏")
                .getId();
    }

    @Test
    void conversationSeedsAgentAndUserParticipants() {
        List<ConversationParticipant> participants = participantRepository.findByConversationId(conversationId);
        assertEquals(2, participants.size(), "一对一会话应有 Agent + User 两个参与者");

        boolean hasAgent = participants.stream().anyMatch(p ->
                ConversationParticipant.ROLE_AGENT.equals(p.getRole()) && companionId.equals(p.getMemberId()));
        boolean hasUser = participants.stream().anyMatch(p ->
                ConversationParticipant.ROLE_USER.equals(p.getRole()) && userId.equals(p.getMemberId()));
        assertTrue(hasAgent, "应有 Agent 参与者");
        assertTrue(hasUser, "应有 User 参与者");
    }

    @Test
    void participantsAreIdempotent() {
        // 同一 (conversation, member) 只出现一次
        for (ConversationParticipant p : participantRepository.findByConversationId(conversationId)) {
            assertEquals(1, participantRepository.findByConversationId(conversationId).stream()
                    .filter(x -> x.getMemberId().equals(p.getMemberId())).count());
        }
    }
}
