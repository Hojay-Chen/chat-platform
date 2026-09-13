package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Set;

/**
 * V10 §63 CONNECT message. Client opens a WebSocket and immediately sends a CONNECT frame with
 * the highest protocol version it supports. Server replies with CONNECT_ACK (accepted) or
 * ERROR (rejected).
 *
 * <p>{@code supportedVersions} lets the server negotiate. Today only {@code dhcp.v1}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectMessage(Set<String> supportedVersions, String clientInfo) {
    public static ConnectMessage v1(String clientInfo) {
        return new ConnectMessage(Set.of(DhcpConstants.PROTOCOL_VERSION), clientInfo);
    }
}
