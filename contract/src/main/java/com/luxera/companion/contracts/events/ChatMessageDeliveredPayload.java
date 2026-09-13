package com.luxera.companion.contracts.events;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * V10 §5 CHAT_MESSAGE_DELIVERED payload. Note: the message BODY is intentionally NOT included;
 * per V10 §4, the agent discovers message content through {@code SimulatorClient.readMessages}
 * — never by reading events directly. The {@code messageIds} list is the pointer the agent
 * uses to query.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessageDeliveredPayload(
        String conversationId,
        String recipientPersonId,
        List<String> messageIds,
        String phase
) {}
