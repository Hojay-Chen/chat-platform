package com.luxera.companion.contracts.spi;

import java.util.Optional;

/**
 * V10 §46 — the digital-human platform's way to obtain a short-lived access token for a simulator
 * device, without knowing how the chat platform stores or validates device credentials.
 *
 * <p>A "simulator device" is how the chat platform models the digital human's phone: an ordinary
 * account that happens to be driven by software. The digital human holds {@code (deviceId, secret)}
 * from provisioning; everything else — token TTL, signing key, revocation — is chat's business.
 *
 * <p>Implemented by {@code chat-platform}. In a single-process deployment this is a local bean; in
 * a split deployment the same interface is served over HTTPS. Callers must treat a missing result
 * as "cannot connect right now" and retry later rather than failing the companion permanently.
 */
public interface SimulatorAccessPort {

    /**
     * Exchange a device secret for a short-lived access token.
     *
     * @return the token, or empty if the device is unknown, revoked, or the secret does not match.
     */
    Optional<String> refreshToken(String deviceId, String secret);
}
