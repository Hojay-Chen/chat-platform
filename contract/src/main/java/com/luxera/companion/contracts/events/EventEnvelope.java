package com.luxera.companion.contracts.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * V10 §38 canonical event envelope. Cross-module single shape for everything flowing on the
 * External Event Bus. {@code payload} is a typed JSON value (see
 * {@link PhoneNotificationPayload}, {@link ChatMessageDeliveredPayload}, …) carried as an
 * opaque JsonNode at the contract boundary so chat and DH modules don't have to import each
 * other's domain types.
 *
 * <p>Idempotency: {@code id} is the deterministic V10 §9 eventId (built by
 * {@code ExternalEventId.deterministic} for replayable events). Correlation/causation chains
 * travel in the optional fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
        String id,
        ExternalEventType type,
        String source,
        String subject,
        Instant occurredAt,
        Instant receivedAt,
        String causationId,
        String correlationId,
        JsonNode payload
) {}
