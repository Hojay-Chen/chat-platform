package com.luxera.companion.conversation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * §30 Thread 维护 Job: 周期衰减线程状态。
 * ACTIVE/PAUSED 太久没消息 → RESUMABLE(可恢复); RESUMABLE 太久 → ABANDONED(被遗忘)。
 * 模拟真人"聊到一半去做别的事情, 过一段时间回来继续, 再久就忘了"。
 *
 * <p>Peer ids come from the {@code conversations} table itself rather than from a persona
 * registry: chat has no persona registry, and a peer with no conversation has no threads to decay.
 */
@Component
public class ThreadMaintenanceJob {

    private final ConversationThreadService threadService;
    private final ConversationRepository conversationRepository;

    public ThreadMaintenanceJob(ConversationThreadService threadService,
                                ConversationRepository conversationRepository) {
        this.threadService = threadService;
        this.conversationRepository = conversationRepository;
    }

    @Scheduled(cron = "${app.scheduler.thread-maintenance-cron}")
    @Transactional
    public void maintain() {
        LocalDateTime now = LocalDateTime.now();
        for (String companionId : conversationRepository.findDistinctCompanionIds()) {
            try {
                threadService.decayForCompanion(companionId, now);
            } catch (Exception ignored) {
                // 单个伴侣失败不影响整体
            }
        }
    }
}
