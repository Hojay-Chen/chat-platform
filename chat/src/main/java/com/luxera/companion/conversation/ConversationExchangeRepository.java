package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversationExchangeRepository extends JpaRepository<ConversationExchange, String> {
    Optional<ConversationExchange> findTopBySessionIdAndStatusOrderByStartedAtDesc(String sessionId, String status);
    Optional<ConversationExchange> findTopBySessionIdOrderByStartedAtDesc(String sessionId);

    /** 级联清理用, 见 {@link ConversationPurgeService} */
    long deleteByConversationIdIn(java.util.Collection<String> conversationIds);
}
