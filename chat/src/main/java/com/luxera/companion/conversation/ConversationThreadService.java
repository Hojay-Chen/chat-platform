package com.luxera.companion.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * §30 Conversation Thread 服务: 维护"围绕一个话题的一段对话"的生命周期。
 *
 * 状态机: ACTIVE → PAUSED → RESUMABLE → ENDED / ABANDONED
 * - 连续聊一个话题 → ACTIVE(活跃线程)
 * - 话题切换 → 旧线程 PAUSED(暂时搁置, 可恢复)
 * - 一段时间后回到旧话题 → RESUMABLE(恢复)
 * - 话题自然结束 / 明确收尾 → ENDED
 * - 长期无人问津 → ABANDONED(被遗忘)
 */
@Service
public class ConversationThreadService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_RESUMABLE = "RESUMABLE";
    public static final String STATUS_ENDED = "ENDED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    /** 超过该分钟未活跃的 ACTIVE/PAUSED 线程 → 降级为 RESUMABLE */
    private static final long RESUMABLE_AFTER_MINUTES = 30;
    /** 超过该分钟未活跃的 RESUMABLE 线程 → 遗忘 ABANDONED */
    private static final long ABANDONED_AFTER_MINUTES = 24 * 60;

    private final ConversationThreadRepository repo;

    public ConversationThreadService(ConversationThreadRepository repo) {
        this.repo = repo;
    }

    /**
     * 记录一次消息到线程: 若当前会话有 ACTIVE 线程则复用, 否则创建新线程。
     * 话题由调用方提供(感知结果中的 topic); 若为空则沿用当前活跃线程话题。
     */
    @Transactional
    public ConversationThread touch(String conversationId, String companionId, String userId,
                                    String topic, String emotionalTone, LocalDateTime now) {
        ConversationThread active = repo.findFirstByConversationIdAndStatusOrderByLastMessageAtDesc(
                        conversationId, STATUS_ACTIVE)
                .orElse(null);

        if (active != null && (topic == null || topic.isBlank() || topic.equals(active.getTopic()))) {
            active.setLastMessageAt(now);
            active.setMessageCount(active.getMessageCount() + 1);
            if (emotionalTone != null) active.setEmotionalTone(emotionalTone);
            return repo.save(active);
        }

        // 话题变化 → 旧线程暂停, 开新线程
        if (active != null) {
            active.setStatus(STATUS_PAUSED);
            active.setPausedAt(now);
            repo.save(active);
        }

        ConversationThread t = new ConversationThread();
        t.setConversationId(conversationId);
        t.setCompanionId(companionId);
        t.setUserId(userId);
        t.setTopic(topic == null || topic.isBlank() ? "日常" : topic);
        t.setEmotionalTone(emotionalTone);
        t.setStatus(STATUS_ACTIVE);
        t.setStartedAt(now);
        t.setLastMessageAt(now);
        t.setMessageCount(1);
        return repo.save(t);
    }

    /** 暂停当前活跃线程(例如 对话自然告一段落 / 她去做别的事) */
    @Transactional
    public void pause(String conversationId, LocalDateTime now) {
        repo.findFirstByConversationIdAndStatusOrderByLastMessageAtDesc(conversationId, STATUS_ACTIVE)
                .ifPresent(t -> {
                    t.setStatus(STATUS_PAUSED);
                    t.setPausedAt(now);
                    repo.save(t);
                });
    }

    /** 恢复一个暂停的线程(回到旧话题) */
    @Transactional
    public void resume(String threadId) {
        repo.findById(threadId).ifPresent(t -> {
            t.setStatus(STATUS_ACTIVE);
            t.setLastMessageAt(LocalDateTime.now());
            repo.save(t);
        });
    }

    /** 明确结束一个线程 */
    @Transactional
    public void end(String threadId) {
        repo.findById(threadId).ifPresent(t -> {
            t.setStatus(STATUS_ENDED);
            t.setLastMessageAt(LocalDateTime.now());
            repo.save(t);
        });
    }

    /**
     * 按伴侣衰减该伴侣的线程(定时任务调用)。
     * ACTIVE/PAUSED 太久没消息 → RESUMABLE; RESUMABLE 太久 → ABANDONED。
     */
    @Transactional
    public void decayForCompanion(String companionId, LocalDateTime now) {
        for (ConversationThread t : repo.findByCompanionIdOrderByLastMessageAtDesc(companionId)) {
            if (STATUS_ENDED.equals(t.getStatus()) || STATUS_ABANDONED.equals(t.getStatus())) continue;
            long idleMinutes = t.getLastMessageAt() == null ? 0
                    : java.time.Duration.between(t.getLastMessageAt(), now).toMinutes();
            if (STATUS_ACTIVE.equals(t.getStatus()) || STATUS_PAUSED.equals(t.getStatus())) {
                if (idleMinutes >= RESUMABLE_AFTER_MINUTES) {
                    t.setStatus(STATUS_RESUMABLE);
                    repo.save(t);
                }
            } else if (STATUS_RESUMABLE.equals(t.getStatus()) && idleMinutes >= ABANDONED_AFTER_MINUTES) {
                t.setStatus(STATUS_ABANDONED);
                repo.save(t);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ConversationThread> activeThreads(String companionId) {
        return repo.findByCompanionIdAndStatusOrderByLastMessageAtDesc(companionId, STATUS_ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<ConversationThread> resumableThreads(String companionId) {
        return repo.findByCompanionIdAndStatusOrderByLastMessageAtDesc(companionId, STATUS_RESUMABLE);
    }

    @Transactional(readOnly = true)
    public List<ConversationThread> threads(String companionId) {
        return repo.findByCompanionIdOrderByLastMessageAtDesc(companionId);
    }

    /** 当前会话的活跃线程 */
    @Transactional(readOnly = true)
    public ConversationThread currentActive(String conversationId) {
        return repo.findFirstByConversationIdAndStatusOrderByLastMessageAtDesc(conversationId, STATUS_ACTIVE)
                .orElse(null);
    }
}
