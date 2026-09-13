package com.luxera.companion.contracts.application;

/**
 * LAP v1 — everything about <em>who</em> is invoking that does not fit in {@link ActionRequest}.
 *
 * <p>This is what {@code ActionRequest} is allowed to leave out. The digital human fills it in
 * when it calls the runtime port; the REST adapter fills it in from the authenticated principal;
 * the MCP adapter fills it in from its own principal resolver.
 *
 * <p>When the digital human constructs one of these it <b>must state
 * {@link PrincipalType#AGENT} explicitly</b>. The {@code HUMAN} default that legacy JWTs get is a
 * compatibility affordance for tokens minted before {@code ptype} existed, not a general fallback
 * — an agent that silently becomes a human would bypass every agent-specific restriction.
 *
 * @param principalType who is acting
 * @param principalId   the acting principal's id (user id or companion id)
 * @param companionId   the digital human on whose behalf this runs, if any
 * @param userId        the human at the other end, if any
 * @param sessionId     the {@code ApplicationSession}; null lets the gateway resolve it from target
 * @param correlationId trace id carried across the platform and into the ledger
 */
public record InvocationContext(
        PrincipalType principalType,
        String principalId,
        String companionId,
        String userId,
        String sessionId,
        String correlationId
) {

    public static InvocationContext agent(String companionId, String userId, String correlationId) {
        return new InvocationContext(PrincipalType.AGENT, companionId, companionId, userId, null, correlationId);
    }

    public static InvocationContext human(String userId, String correlationId) {
        return new InvocationContext(PrincipalType.HUMAN, userId, null, userId, null, correlationId);
    }

    public InvocationContext withSession(String sessionId) {
        return new InvocationContext(principalType, principalId, companionId, userId, sessionId, correlationId);
    }
}
