package com.luxera.companion.contracts.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * LAP v1 — the whole action request model, and it is deliberately this small.
 *
 * <p>Everything else comes from context rather than from the body: who is acting from the token
 * (principal), which application from the action id, which version from the installation, which
 * session from the target resource. The design's rule is "参数尽可能满足全部功能，但是不要冗余" —
 * a field earns its place only if it cannot be derived.
 *
 * <p>{@code expectedResourceVersion} is the one addition beyond the original three fields, and it
 * earns it: without an expected version, two principals acting on one resource silently overwrite
 * each other. Pass null to accept whatever the current version is.
 *
 * @param action                  dotted action id, namespaced by capability ({@code game.make_move})
 * @param target                  resource URI; may be a collection URI for create-style actions
 * @param input                   action input, validated against the action's JSON Schema
 * @param expectedResourceVersion optimistic-concurrency token, or null to skip the check
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionRequest(
        String action,
        String target,
        JsonNode input,
        Long expectedResourceVersion
) {

    public static ActionRequest of(String action, String target, JsonNode input) {
        return new ActionRequest(action, target, input, null);
    }

    public ActionRequest withExpectedVersion(Long version) {
        return new ActionRequest(action, target, input, version);
    }
}
