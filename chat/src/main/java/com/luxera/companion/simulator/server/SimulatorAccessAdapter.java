package com.luxera.companion.simulator.server;

import com.luxera.companion.contracts.spi.SimulatorAccessPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Chat-platform implementation of {@link SimulatorAccessPort}.
 *
 * <p>Keeps {@link SimulatorPairingService} — and with it the signing key, token TTL and revocation
 * rules — entirely inside the chat platform. The digital human only ever sees the token string.
 */
@Slf4j
@Component
public class SimulatorAccessAdapter implements SimulatorAccessPort {

    private final SimulatorPairingService pairingService;

    public SimulatorAccessAdapter(SimulatorPairingService pairingService) {
        this.pairingService = pairingService;
    }

    @Override
    public Optional<String> refreshToken(String deviceId, String secret) {
        if (deviceId == null || secret == null) return Optional.empty();
        try {
            return Optional.ofNullable(pairingService.refreshBySecret(deviceId, secret));
        } catch (Exception e) {
            log.warn("[SimulatorAccess] token 刷新失败: device={}, error={}", deviceId, e.getMessage());
            return Optional.empty();
        }
    }
}
