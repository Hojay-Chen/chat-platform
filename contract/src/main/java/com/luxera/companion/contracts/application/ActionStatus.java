package com.luxera.companion.contracts.application;

/**
 * LAP v1 — the outcome of an action, independent of transport.
 *
 * <p>REST, MCP, the SDK and the in-process {@code ApplicationRuntimePort} all report these same
 * values; a single {@code ActionStatusMapper} on the platform side turns them into HTTP codes.
 * That is the point: the application protocol is not the transport, so the same failure reads the
 * same way whether it arrived over HTTP or from the digital human's own runtime.
 *
 * <p>The status is a first-class field rather than something inferred from an empty result —
 * without it, {@code DENIED} and "succeeded but nothing to report" are indistinguishable, and a
 * caller cannot tell "you may not" from "done".
 */
public enum ActionStatus {
    /** The action ran and produced a result. */
    SUCCESS,
    /** The request body did not satisfy the action's input schema. */
    INVALID_ARGUMENT,
    /** A WRITE/EXECUTE action arrived without an {@code Idempotency-Key}. */
    IDEMPOTENCY_KEY_REQUIRED,
    /** The principal is not permitted: no installation, no grant, or the risk ceiling is exceeded. */
    DENIED,
    /** The target resource does not exist. */
    NOT_FOUND,
    /** The resource moved since the caller read it — re-read and retry. */
    STATE_CONFLICT,
    /** A request with this key is still running. */
    IDEMPOTENCY_IN_PROGRESS,
    /** This key was already used for a different payload. */
    IDEMPOTENCY_KEY_REUSED,
    /** The action was performed before but its outcome is unknown (crashed mid-flight). */
    EXPIRED,
    /** The application itself failed. */
    FAILED,
    /** The permission engine requires an explicit human confirmation before proceeding. */
    REQUIRE_CONFIRMATION
}
