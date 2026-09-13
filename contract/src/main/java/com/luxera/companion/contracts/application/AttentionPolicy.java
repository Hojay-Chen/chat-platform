package com.luxera.companion.contracts.application;

/**
 * LAP v1 — how much of the digital human's attention performing this action should consume.
 *
 * <p>This is the digital-human's own concern, expressed as a contract so an application can
 * declare it. The mapping from policy to the digital human's internal
 * {@code digitalhuman.perception.PerceptionLevel} lives on the DH side
 * ({@code AttentionPolicies.toPerceptionLevel}) — the reverse direction would make
 * {@code contracts} depend on DH.
 */
public enum AttentionPolicy {
    /** Perform silently; never surfaces in the digital human's awareness. */
    NONE,
    /** Background activity — the digital human does it without narrating it. */
    SUBCONSCIOUS,
    /** The digital human knows it is doing this and may mention it. */
    AWARE,
    /** Foreground activity that deserves the digital human's full attention. */
    FOCUSED
}
