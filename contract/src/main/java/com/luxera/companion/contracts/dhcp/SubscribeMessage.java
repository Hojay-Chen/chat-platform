package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * V10 §63 SUBSCRIBE message. Client declares which event topics it wants to receive. Topics
 * are dot-separated, e.g. {@code phone.notification.*}, {@code chat.message.delivered},
 * {@code application.event.*}, {@code game.event.*}.
 *
 * <p>{@code resumeAfterSequence} is optional: when set, server replays missed events from
 * {@code client_sync_cursor.last_event_id} (V10 §50). Per-connection FIFO ordering is guaranteed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscribeMessage(
        List<String> topics,
        Long resumeAfterSequence
) {
    public static SubscribeMessage of(List<String> topics) {
        return new SubscribeMessage(topics, null);
    }
}
