package com.luxera.companion.contracts.errors;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cross-module error payload. Used by HTTP and WS bridges so both sides serialize errors
 * consistently. Prefer {@link DhcpError} for wire-protocol-specific framing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlatformError(String code, String message, String details) {
    public static PlatformError of(String code, String message) {
        return new PlatformError(code, message, null);
    }
}
