package com.luxera.companion.contracts.provision;

/**
 * SPI for cross-platform companion provisioning. Both chat-platform and digital-human-platform
 * depend only on this interface — never on each other's implementation classes. The
 * Strangler bootstrap-app wires a local in-process adapter (R1-R8); the R9 split wires
 * an HTTP adapter using {@code INTERNAL_API_TOKEN}.
 *
 * <p>Implementation note: chat-side and DH-side live in different processes. R9 will swap
 * the bootstrap-app local adapter for an HTTP client without touching any controller.
 */
public interface CompanionProvisioningPort {

    /**
     * Compile a natural-language description into a structured persona.
     * Pure compute; no DB writes.
     */
    PersonaJson compile(String description);

    /**
     * Create a new companion for the given user. Implementations are responsible for:
     * <ol>
     *   <li>writing the DH-side companion/persona records,</li>
     *   <li>asking chat-side via {@link ChatProvisioningPort} to mint a simulator account
     *       (real user row, with secret),</li>
     *   <li>recording the {@code (companionId, deviceId)} binding,</li>
     *   <li>opening the simulator WebSocket connection.</li>
     * </ol>
     */
    CompanionDto create(String userId, PersonaJson persona, String relationshipType);

    /** List all companions owned by {@code userId}. */
    java.util.List<CompanionDto> list(String userId);

    /** Soft-delete a companion. Idempotent. */
    void delete(String userId, String companionId);
}
