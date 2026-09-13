package com.luxera.companion.contracts.provision;

/**
 * SPI used by DH-side to ask chat-side to mint a simulator account (V10 §26-§27). Lives in
 * contracts so DH depends on the interface only. Implementations are local in single-process
 * (R1-R8) and HTTP in split-process (R9).
 */
public interface ChatProvisioningPort {

    /**
     * Mint a brand-new chat user account for a simulator + an attached device credential.
     * <p>Caller must treat the returned {@code secret} as one-shot: it is the only time the
     * server will emit it in plaintext. Server only stores {@code secretHash} (bcrypt).
     * @return result containing the new accountId, the deviceId, the one-shot secret and
     *         the initial pairing code (6 digits, 10 min TTL).
     */
    SimulatorAccountResult provisionSimulatorAccount(String companionId, String displayName);

    /** Revoke a previously minted device. Idempotent. */
    void revokeSimulatorDevice(String deviceId);
}
