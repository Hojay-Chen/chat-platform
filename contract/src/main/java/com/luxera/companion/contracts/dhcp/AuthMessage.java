package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V10 §63 AUTH message. Client sends the access token (issued by SimulatorPairingService) along
 * with the deviceId. The pair is verified on the server; the granted scopes are returned in
 * AUTH_SUCCESS.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthMessage(String deviceId, String accessToken) {}
