package com.luxera.companion.contracts.application;

import java.time.Instant;
import java.util.List;

/**
 * LAP v1 — an agent subscribes and waits, rather than polling.
 *
 * <p>Two delivery modes, two exits. {@code SINK} is a filter evaluated just before in-process
 * delivery. {@code INBOX} is durable: matching events are written to the platform's outbox inside
 * the business transaction and retried until delivered, so a subscriber that goes away and comes
 * back still finds them.
 *
 * <p>One thing this is <em>not</em>: a scheduler. "Remind me at 15:00" is an application's own
 * due-time job, not a subscription — modelling it here would produce an agent that never reminds
 * anyone.
 *
 * @param resourceUriPattern URI pattern to watch; {@code *} matches one segment, {@code **} any
 * @param eventTypes         event types of interest; empty means all
 * @param deliveryMode       {@code SINK} (push now) or {@code INBOX} (durable, drain later)
 * @param expiresAt          when the subscription lapses; null means no expiry
 */
public record SubscriptionRequest(
        String resourceUriPattern,
        List<String> eventTypes,
        String deliveryMode,
        Instant expiresAt
) {

    public static final String MODE_SINK = "SINK";
    public static final String MODE_INBOX = "INBOX";

    public SubscriptionRequest {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        if (deliveryMode == null) {
            deliveryMode = MODE_SINK;
        }
    }

    public boolean matches(String resourceUri, String eventType) {
        return ResourceUriPattern.matches(resourceUriPattern, resourceUri)
                && (eventTypes.isEmpty() || eventTypes.contains(eventType));
    }
}
