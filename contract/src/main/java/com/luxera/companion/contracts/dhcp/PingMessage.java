package com.luxera.companion.contracts.dhcp;

/** V10 §63 PING message. Client → server. Sent on {@link DhcpConstants#DEFAULT_PING_INTERVAL_MS}. */
public record PingMessage(long clientTimeMs) {
    public static PingMessage now() {
        return new PingMessage(System.currentTimeMillis());
    }
}
