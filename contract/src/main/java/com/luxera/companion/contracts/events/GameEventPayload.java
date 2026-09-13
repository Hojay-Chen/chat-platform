package com.luxera.companion.contracts.events;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V10 §37 APPLICATION_EVENT payload. Application-agnostic envelope used for both game
 * events and generic mini-app events. {@code appCode} identifies the producer (e.g.
 * {@code tictactoe}); {@code eventType} is application-defined (e.g. {@code MOVE},
 * {@code CHAT}, {@code FINISHED}). Additional structured data goes in {@code data} as
 * opaque JSON so the application can evolve without contract bumps.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameEventPayload(
        String appCode,
        String eventType,
        String sessionId,
        String actorId,
        com.fasterxml.jackson.databind.JsonNode data
) {}
