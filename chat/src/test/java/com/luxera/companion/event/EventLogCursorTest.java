package com.luxera.companion.event;

import com.luxera.chatframework.ChatPlatformTestApplication;
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
 * §十七 SSE 游标 + 事件日志测试:
 * 1. publish 落日志(瞬态事件除外), payload 带 eventId
 * 2. after(cursor) 能回放错过的消息 —— 断线重连不丢消息
 * 3. 事件按 companion 隔离
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class)
class EventLogCursorTest {

    @Autowired
    CompanionEventBus eventBus;
    @Autowired
    EventLogService eventLogService;
    @Autowired
    EventLogRepository eventLogRepository;

    private final String companionId = UUID.randomUUID().toString();

    @Test
    void publishWritesEventLogWithId() {
        eventBus.publish(companionId, "message_created", Map.of("messageId", "m-1"));
        List<EventLogEntry> entries = eventLogRepository.findByCompanionIdAndIdGreaterThanOrderByIdAsc(companionId, 0);
        assertEquals(1, entries.size());
        assertEquals("message_created", entries.get(0).getEvent());
        assertNotNull(entries.get(0).getId(), "事件日志必须有游标 id");
        assertEquals("m-1", entries.get(0).getPayload().get("messageId"));
    }

    @Test
    void transientEventsNotLogged() {
        eventBus.publish(companionId, "ping", Map.of());
        eventBus.publish(companionId, "companion_typing", Map.of("typing", true));
        assertEquals(0, eventLogRepository.findByCompanionIdAndIdGreaterThanOrderByIdAsc(companionId, 0).size());
    }

    @Test
    void cursorReplaysMissedEvents() {
        eventBus.publish(companionId, "user_message_status", Map.of("messageId", "m-1", "status", "DELIVERED"));
        List<EventLogEntry> afterFirst = eventLogService.after(companionId, 0);
        assertFalse(afterFirst.isEmpty());
        long cursor = afterFirst.get(afterFirst.size() - 1).getId();

        // 断线期间又发生了两件事
        eventBus.publish(companionId, "user_message_status", Map.of("messageId", "m-2", "status", "READ"));
        eventBus.publish(companionId, "companion_message", Map.of("messageId", "m-3"));

        // 重连: 只回放游标之后的
        List<EventLogEntry> replay = eventLogService.after(companionId, cursor);
        assertEquals(2, replay.size(), "重连应只回放游标之后的事件");
        assertEquals("m-2", replay.get(0).getPayload().get("messageId"));
        assertEquals("m-3", replay.get(1).getPayload().get("messageId"));
    }

    @Test
    void eventsIsolatedByCompanion() {
        String other = UUID.randomUUID().toString();
        eventBus.publish(companionId, "companion_message", Map.of("messageId", "mine"));
        eventBus.publish(other, "companion_message", Map.of("messageId", "theirs"));
        List<EventLogEntry> mine = eventLogService.after(companionId, 0);
        assertEquals(1, mine.size());
        assertEquals("mine", mine.get(0).getPayload().get("messageId"));
    }
}
