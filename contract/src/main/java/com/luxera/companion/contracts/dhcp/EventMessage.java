package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * V10 §63 EVENT message. Server → client. Carries the event payload as opaque JSON. The shape
 * depends on the topic; receivers use {@link com.luxera.companion.contracts.events.EventEnvelope}
 * for the canonical fields (id/type/source/occurredAt/correlationId).
 *
 * <p>{@code sequence} is monotonically increasing per connection (V10 §49). Clients echo it
 * in EVENT_ACK.
 */
public record EventMessage(
        Long sequence,
        String eventId,
        String topic,
        JsonNode envelope
) {}
