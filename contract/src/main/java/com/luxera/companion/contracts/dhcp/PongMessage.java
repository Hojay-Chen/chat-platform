package com.luxera.companion.contracts.dhcp;

/** V10 §63 PONG message. Server → client, in reply to PING. */
public record PongMessage(long clientTimeMs, long serverTimeMs) {
    public static PongMessage echo(PingMessage ping) {
        return new PongMessage(ping.clientTimeMs(), System.currentTimeMillis());
    }
}
