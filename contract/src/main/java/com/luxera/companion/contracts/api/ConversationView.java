package com.luxera.companion.contracts.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * V10 §44 — cross-module read-only view of a conversation, returned by
 * {@link com.luxera.companion.contracts.spi.ChatWorldPort}. Persona-specific content is absent on
 * purpose: the digital-human platform owns persona state and must not expect it back from chat.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationView {

    private final String id;
    private final String userId;
    /** Opaque platform-side peer id. For human↔digital-human threads this is the companion id. */
    private final String companionId;
    private final String title;
    private final LocalDateTime lastMessageAt;
    private final int messageCount;
    private final String summary;
    private final String status;
}
