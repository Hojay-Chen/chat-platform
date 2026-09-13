package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V10 §64 Protocol error.
 *
 * <p>Used in ERROR frames and as the body of failed COMMAND_RESULT. Codes are stable strings so
 * clients can branch on them. See {@link DhcpErrorCode} for the canonical set.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DhcpError(String code, String message, String details) {
    public static DhcpError of(String code, String message) {
        return new DhcpError(code, message, null);
    }
}
