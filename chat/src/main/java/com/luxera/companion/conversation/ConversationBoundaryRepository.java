package com.luxera.companion.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationBoundaryRepository extends JpaRepository<ConversationBoundary, String> {
    List<ConversationBoundary> findTop10ByConversationIdOrderByOccurredAtDesc(String conversationId);
    ConversationBoundary findTopByConversationIdOrderByOccurredAtDesc(String conversationId);
}
