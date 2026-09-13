package com.luxera.companion.contracts.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * LAP v1 — the unified read model. Every application's state is read through a resource URI
 * ({@code game://session/123}, {@code reminder://item/456}), never through an application-specific
 * type. {@code GameSession}, {@code CalendarEntry} and {@code Order} are not concepts the platform
 * knows about; they are all just resources with a type and a JSON body.
 *
 * <p>{@code version} is the optimistic-concurrency token. A caller that read version 7 may pass
 * {@code expectedResourceVersion=7} on a write; if someone else already moved it to 8 the write
 * is refused with {@code STATE_CONFLICT} instead of silently clobbering the other principal's
 * change. This is what makes "two principals, one resource" safe across human and agent.
 *
 * @param uri           canonical resource URI, unique platform-wide
 * @param resourceType  short type tag ({@code game.session}, {@code reminder.item}, …)
 * @param applicationId owning application
 * @param sessionId     the {@code ApplicationSession} this resource hangs beneath, if any
 * @param state         the resource body; opaque to the platform and to the digital human
 * @param version       monotonic state version, incremented on every write
 * @param updatedAt     last write time
 * @param agentHint     optional advice on how to read this resource's state
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceView(
        String uri,
        String resourceType,
        String applicationId,
        String sessionId,
        JsonNode state,
        long version,
        Instant updatedAt,
        String agentHint
) {}
