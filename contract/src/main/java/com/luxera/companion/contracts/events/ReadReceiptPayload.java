package com.luxera.companion.contracts.events;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * V10 §5 CHAT_MESSAGE_READ payload. Bulk read-receipt: when the recipient client confirms
 * reading, all messageIds in the same conversation up to the last one are reported.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReadReceiptPayload(
        String conversationId,
        String readerPersonId,
        List<String> messageIds,
        Long readAtEpochMs
) {}
