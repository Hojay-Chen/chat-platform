package com.luxera.companion.contracts.provision;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * V10 §26 result of provisioning a simulator device on chat-side. Note: {@code secret} is
 * shown exactly once; chat-side stores only the bcrypt hash.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimulatorAccountResult(
        String accountId,
        String deviceId,
        String secret,
        String pairingCode,
        Instant pairingCodeExpiresAt
) {}
