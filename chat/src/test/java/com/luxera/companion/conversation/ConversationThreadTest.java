package com.luxera.companion.conversation;

import com.luxera.chatframework.ChatPlatformTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §30 Conversation Thread 单元测试:
 * - 首次消息创建 ACTIVE 线程
 * - 同话题复用线程
 * - 话题切换 → 旧线程 PAUSED, 开新线程
 * - 超时衰减 ACTIVE → RESUMABLE → ABANDONED
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class, properties = {
        "app.scheduler.thread-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.unfinished-thought-cron=0 0 0 1 1 *",
        "app.scheduler.event-simulation-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *"
})
class ConversationThreadTest {

    @Autowired
    ConversationThreadService threadService;
    @Autowired
    ConversationThreadRepository repo;

    private String conversationId;
    private String companionId;
    private String userId;

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID().toString();
        companionId = UUID.randomUUID().toString();
        userId = "thread-user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void firstMessageCreatesActiveThread() {
        var t = threadService.touch(conversationId, companionId, userId, "电影", "轻松",
                LocalDateTime.of(2026, 8, 16, 20, 0));
        assertEquals(ConversationThreadService.STATUS_ACTIVE, t.getStatus());
        assertEquals("电影", t.getTopic());
        assertEquals(1, t.getMessageCount());
    }

    @Test
    void sameTopicReusesThread() {
        threadService.touch(conversationId, companionId, userId, "电影", "轻松",
                LocalDateTime.of(2026, 8, 16, 20, 0));
        var t2 = threadService.touch(conversationId, companionId, userId, "电影", "开心",
                LocalDateTime.of(2026, 8, 16, 20, 5));
        assertEquals(ConversationThreadService.STATUS_ACTIVE, t2.getStatus());
        assertEquals(2, t2.getMessageCount(), "同话题应复用线程并计数");
    }

    @Test
    void topicSwitchPausesOldAndCreatesNew() {
        var first = threadService.touch(conversationId, companionId, userId, "电影", "轻松",
                LocalDateTime.of(2026, 8, 16, 20, 0));
        var second = threadService.touch(conversationId, companionId, userId, "工作", "严肃",
                LocalDateTime.of(2026, 8, 16, 20, 5));
        assertEquals(ConversationThreadService.STATUS_ACTIVE, second.getStatus());
        assertEquals("工作", second.getTopic());
        var old = repo.findById(first.getId()).orElseThrow();
        assertEquals(ConversationThreadService.STATUS_PAUSED, old.getStatus(),
                "话题切换后旧线程应 PAUSED");
    }

    @Test
    void decayMovesActiveToResumableThenAbandoned() {
        var t = threadService.touch(conversationId, companionId, userId, "工作", null,
                LocalDateTime.of(2026, 8, 16, 9, 0));
        // 40 分钟后 → RESUMABLE
        threadService.decayForCompanion(companionId, LocalDateTime.of(2026, 8, 16, 9, 40));
        assertEquals(ConversationThreadService.STATUS_RESUMABLE,
                repo.findById(t.getId()).orElseThrow().getStatus());
        // 1 天后 → ABANDONED
        threadService.decayForCompanion(companionId, LocalDateTime.of(2026, 8, 17, 9, 40));
        assertEquals(ConversationThreadService.STATUS_ABANDONED,
                repo.findById(t.getId()).orElseThrow().getStatus());
    }

    @Test
    void resumeReturnsToActive() {
        var t = threadService.touch(conversationId, companionId, userId, "电影", null,
                LocalDateTime.of(2026, 8, 16, 20, 0));
        threadService.decayForCompanion(companionId, LocalDateTime.of(2026, 8, 16, 21, 0));
        threadService.resume(t.getId());
        assertEquals(ConversationThreadService.STATUS_ACTIVE,
                repo.findById(t.getId()).orElseThrow().getStatus());
    }
}
