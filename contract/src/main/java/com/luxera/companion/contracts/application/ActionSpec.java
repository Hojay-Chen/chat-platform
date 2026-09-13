package com.luxera.companion.contracts.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * LAP v1 — one fine-grained thing an application can do ({@code game.make_move}).
 *
 * <p>This is the digital human's entire view of an application's behaviour. The agent reads the
 * resource, asks the platform which actions are currently pending, and gets these back — then
 * picks one and fills in {@code inputSchema}. Everything it needs to decide well lives here:
 * the description, the JSON Schema for the input, and {@code agentHint} (strategy advice the
 * application author wrote, e.g. "if you can complete three in a row, do that first").
 *
 * <p>{@code agentHint} is the mechanism that keeps game strategy out of the digital human: the
 * hint travels with the action, so adding a second game teaches the agent nothing new.
 *
 * @param actionId        dotted, namespaced by the capability it belongs to ({@code game.make_move})
 * @param applicationId   owning application
 * @param capabilityId    the capability this action implements
 * @param description     what it does, rendered into the agent's prompt
 * @param permissionLevel READ actions are never idempotency-recorded
 * @param riskLevel       drives the permission engine's allow/confirm/deny decision
 * @param attention       how much of the digital human's attention it consumes
 * @param inputSchema     JSON Schema for {@code input}; null when the action takes no input
 * @param agentHint       optional strategy/usage advice for an autonomous caller
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionSpec(
        String actionId,
        String applicationId,
        String capabilityId,
        String description,
        PermissionLevel permissionLevel,
        RiskLevel riskLevel,
        AttentionPolicy attention,
        JsonNode inputSchema,
        String agentHint
) {

    /** A read of application state — never recorded, never deduplicated. */
    @JsonIgnore
    public boolean isRead() {
        return permissionLevel == PermissionLevel.READ;
    }

    /** Whether an {@code Idempotency-Key} is mandatory for this action. */
    @JsonIgnore
    public boolean requiresIdempotencyKey() {
        return permissionLevel != PermissionLevel.READ;
    }
}
