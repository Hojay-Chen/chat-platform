package com.luxera.companion.contracts.application;

/**
 * LAP v1 — who is acting.
 *
 * <p>A human client, an Agent, the platform itself and an Application all reach the same
 * {@code ApplicationGateway}; the only thing that differs is the principal. This is why there is
 * no "Agent API" anywhere in the platform: the agent is just another principal.
 *
 * <p>{@link #HUMAN} is the fallback for legacy JWTs that carry no {@code ptype} claim
 * (see {@code PrincipalResolver}). That default exists for backward compatibility only —
 * an in-process digital-human invocation must state {@link #AGENT} explicitly rather than
 * rely on it.
 */
public enum PrincipalType {
    /** A person using a client. */
    HUMAN,
    /** A digital human hosted by this companion platform. */
    AGENT,
    /**
     * LAP v2 — an agent that lives <em>outside</em> this platform and reaches the gateway over
     * MCP or the remote-application protocol.
     *
     * <p>It is a separate value rather than a flavour of {@link #AGENT} because the two differ in
     * exactly one place that matters: {@link #AGENT} is a principal this platform hosts, so the
     * platform can resolve its {@code companionId} internally; an external agent is only ever a
     * name plus a signature. Everything else — participation, permissions, the gateway — is
     * identical, which is why it is a {@code PrincipalType} and not a parallel API.
     */
    EXTERNAL_AGENT,
    /** The platform itself (schedulers, maintenance jobs). */
    SYSTEM,
    /** Another application acting on its own behalf. */
    APPLICATION
}
