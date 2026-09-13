package com.luxera.companion.contracts.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.List;

/**
 * LAP v1 — the unified action response: {@code Result → Event → Reality Ledger}.
 *
 * <p>The original design prescribed {@code {result, resource, events}}; {@code status} and
 * {@code error} are added because without them a denial and an empty success are the same object.
 * Both are always present, so a caller never has to guess.
 *
 * <p>Note what is <em>not</em> here: no reality-ledger payload. The application reports what
 * happened; the digital human decides what it means for its own ledger. Letting an application
 * dictate ledger semantics across a module boundary inverts the dependency.
 *
 * @param status   outcome; drives the HTTP mapping and the caller's branching
 * @param result   action-specific result body, already projected for the caller
 * @param resource the resource as it stands after the action; null when the action has no resource
 * @param events   events emitted as a side effect; empty, never null
 * @param error    failure detail; null on success
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionResponse(
        ActionStatus status,
        JsonNode result,
        ResourceView resource,
        List<ApplicationEvent> events,
        ActionError error
) {

    public ActionResponse {
        events = events == null ? List.of() : List.copyOf(events);
        // 显式 null 反序列化后是 NullNode 而不是 Java null —— 不归一化的话
        // `response.result() != null` 会把"没有结果"判成"有结果", 而调用方无从察觉。
        result = normalize(result);
    }

    private static JsonNode normalize(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    public static ActionResponse success(JsonNode result, ResourceView resource) {
        return new ActionResponse(ActionStatus.SUCCESS, result, resource, List.of(), null);
    }

    public static ActionResponse success(JsonNode result, ResourceView resource, List<ApplicationEvent> events) {
        return new ActionResponse(ActionStatus.SUCCESS, result, resource, events, null);
    }

    public static ActionResponse failure(ActionStatus status, String code, String message) {
        return new ActionResponse(status, null, null, List.of(), ActionError.of(code, message));
    }

    /** Derived, not part of the wire shape — Jackson would otherwise emit a {@code success} field. */
    @JsonIgnore
    public boolean isSuccess() {
        return status == ActionStatus.SUCCESS;
    }
}
