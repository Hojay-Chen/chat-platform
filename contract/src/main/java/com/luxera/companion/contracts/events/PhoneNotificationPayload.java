package com.luxera.companion.contracts.events;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V10 §14 PHONE_NOTIFICATION payload. A notification is what the recipient's phone would show
 * them; it is not the message content. The agent must explicitly {@code OPEN_CONVERSATION} /
 * {@code READ_MESSAGE} actions to discover the content (V10 §15-§19).
 *
 * <p>{@code preview} visibility respects the user's privacy mode (V10 §69):
 * FULL_PREVIEW / SENDER_ONLY / NO_PREVIEW. The simulator — not the chat platform — enforces
 * the mode; this payload carries the maximum information the phone could surface.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PhoneNotificationPayload(
        String notificationType,
        String conversationId,
        String messageId,
        String senderId,
        String senderName,
        String preview,
        String privacyMode,
        Boolean sound,
        Boolean vibration,
        Integer priority
) {}
