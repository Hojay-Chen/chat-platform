package com.luxera.companion.contracts.application;

/**
 * LAP v1 — how invasive an action is. Deliberately three values; the design explicitly warns
 * against growing this into dozens.
 *
 * <p>Two consequences are wired into the runtime:
 * <ul>
 *   <li>{@link #READ} actions never record an {@code action_invocation} and never require an
 *       {@code Idempotency-Key} — a read has no side effect to deduplicate, and keying reads was
 *       a real bug in the pre-LAP runtime (a second {@code game.state} replayed the first,
 *       stale board).</li>
 *   <li>{@link #WRITE} and {@link #EXECUTE} require an {@code Idempotency-Key}.</li>
 * </ul>
 */
public enum PermissionLevel {
    READ,
    WRITE,
    EXECUTE
}
