package com.luxera.companion.contracts.provision;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Compact view of a companion. Used both directions: chat-side facade returns it from
 * {@code /api/companions} endpoints; DH-side carries the canonical id and timestamps.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanionDto(
        String id,
        String userId,
        String name,
        String gender,
        String relationshipType,
        String personaVersionId,
        Instant createdAt,
        Instant updatedAt
) {}
