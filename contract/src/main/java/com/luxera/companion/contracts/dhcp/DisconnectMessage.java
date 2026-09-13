package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V10 §63 DISCONNECT message. Either side may send before closing the socket. {@code reason}
 * is a free-form string for diagnostics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisconnectMessage(String reason) {}
