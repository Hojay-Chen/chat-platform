package com.luxera.companion.contracts.dhcp;

/**
 * Canonical error codes for DHCP ERROR frames. Stable strings — clients may branch on these.
 */
public final class DhcpErrorCode {
    private DhcpErrorCode() {}

    /** Protocol-level error: malformed frame, unknown version, etc. */
    public static final String PROTOCOL_ERROR = "PROTOCOL_ERROR";
    /** Auth failed: bad/expired/revoked token, wrong pairing code, etc. */
    public static final String AUTH_FAILED = "AUTH_FAILED";
    /** No active session for the supplied sessionId. */
    public static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
    /** Command not allowed by granted scopes. */
    public static final String SCOPE_DENIED = "SCOPE_DENIED";
    /** Command failed at the application layer (e.g. invalid payload, downstream error). */
    public static final String COMMAND_FAILED = "COMMAND_FAILED";
    /** Rate limit hit. */
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** Server shutting down / transient — client should retry with backoff. */
    public static final String TRANSIENT = "TRANSIENT";
}
