package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * V10 §63 Root wire frame. All DHCP messages are wrapped in this envelope. The {@code type}
 * determines the expected {@code payload} schema.
 *
 * <p>Used as a single record on the wire. The {@code requestId} correlates request/response
 * (COMMAND ↔ COMMAND_RESULT, AUTH ↔ AUTH_SUCCESS) and is echoed in EVENT_ACK frames.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DhcpFrame(
        String version,
        DhcpFrameType type,
        String requestId,
        Long sequence,
        JsonNode payload
) {
    public static final String CURRENT_VERSION = DhcpConstants.PROTOCOL_VERSION;

    public static DhcpFrame of(DhcpFrameType type, String requestId, JsonNode payload) {
        return new DhcpFrame(CURRENT_VERSION, type, requestId, null, payload);
    }

    public static DhcpFrame of(DhcpFrameType type, String requestId, Long sequence, JsonNode payload) {
        return new DhcpFrame(CURRENT_VERSION, type, requestId, sequence, payload);
    }
}
