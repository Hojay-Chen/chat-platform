package com.luxera.companion.conversation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 会话管理器(设计文档 §二十~二十六): 把消息归入 Session/Exchange, 记录对话边界。
 * Conversation(长期空间) → Session(一次连续聊天) → Exchange(一次自然互动) → Message
 */
@Component
public class SessionManager {

    /** 超过该间隔视为新 Session(一次连续聊天) */
    private static final Duration SESSION_GAP = Duration.ofMinutes(30);
    /** 超过该间隔视为新 Exchange(一次自然互动) */
    private static final Duration EXCHANGE_GAP = Duration.ofMinutes(5);

    private final ConversationSessionRepository sessionRepo;
    private final ConversationExchangeRepository exchangeRepo;
    private final ConversationBoundaryRepository boundaryRepo;
    private final MessageRepository messageRepo;

    public SessionManager(ConversationSessionRepository sessionRepo,
                          ConversationExchangeRepository exchangeRepo,
                          ConversationBoundaryRepository boundaryRepo,
                          MessageRepository messageRepo) {
        this.sessionRepo = sessionRepo;
        this.exchangeRepo = exchangeRepo;
        this.boundaryRepo = boundaryRepo;
        this.messageRepo = messageRepo;
    }

    /** 用户发消息: 归入正确的 Session/Exchange, 并持久化消息归属 */
    @Transactional
    public void assign(Message m, String userId, String companionId, LocalDateTime now) {
        ConversationSession session = sessionRepo
                .findTopByConversationIdOrderByStartedAtDesc(m.getConversationId())
                .filter(s -> s.getEndedAt() == null)
                .filter(s -> Duration.between(s.getStartedAt(), now).compareTo(SESSION_GAP) < 0)
                .orElseGet(() -> {
                    ConversationSession s = new ConversationSession();
                    s.setConversationId(m.getConversationId());
                    s.setCompanionId(companionId);
                    s.setUserId(userId);
                    s.setStartedAt(now);
                    return sessionRepo.save(s);
                });
        m.setSessionId(session.getId());
        session.setMessageCount(session.getMessageCount() + 1);
        sessionRepo.save(session);

        ConversationExchange exchange = exchangeRepo
                .findTopBySessionIdAndStatusOrderByStartedAtDesc(session.getId(), "OPEN")
                .filter(e -> e.getEndedAt() == null)
                .filter(e -> Duration.between(e.getStartedAt(), now).compareTo(EXCHANGE_GAP) < 0)
                .orElseGet(() -> {
                    ConversationExchange e = new ConversationExchange();
                    e.setSessionId(session.getId());
                    e.setConversationId(m.getConversationId());
                    e.setCompanionId(companionId);
                    e.setUserId(userId);
                    e.setStartedAt(now);
                    return exchangeRepo.save(e);
                });
        m.setExchangeId(exchange.getId());
        exchange.setMessageCount(exchange.getMessageCount() + 1);
        exchangeRepo.save(exchange);

        // 持久化消息的 session/exchange 归属
        messageRepo.save(m);
    }

    /** 记录对话边界(如"我去忙了"→SOFT_END), 并关闭 Session/Exchange */
    @Transactional
    public void boundary(String userId, String companionId, String conversationId, String type, String reason) {
        LocalDateTime now = LocalDateTime.now();
        ConversationBoundary b = new ConversationBoundary();
        b.setConversationId(conversationId);
        b.setCompanionId(companionId);
        b.setUserId(userId);
        b.setType(type);
        b.setReason(reason);
        b.setOccurredAt(now);
        boundaryRepo.save(b);

        sessionRepo.findTopByConversationIdOrderByStartedAtDesc(conversationId)
                .filter(s -> s.getEndedAt() == null)
                .ifPresent(s -> {
                    s.setEndedAt(now);
                    sessionRepo.save(s);
                    exchangeRepo.findTopBySessionIdAndStatusOrderByStartedAtDesc(s.getId(), "OPEN")
                            .filter(e -> e.getEndedAt() == null)
                            .ifPresent(e -> {
                                e.setEndedAt(now);
                                e.setStatus("CLOSED");
                                exchangeRepo.save(e);
                            });
                });
    }

    public ConversationBoundary lastBoundary(String conversationId) {
        return boundaryRepo.findTopByConversationIdOrderByOccurredAtDesc(conversationId);
    }
}
