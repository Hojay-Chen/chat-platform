package com.luxera.companion.contracts.dhcp;

/**
 * V10 §62 DHCP frame types.
 *
 * <p>Each frame on the wire is {@link DhcpFrame} with one of these types as envelope. Payload
 * shape depends on type — see the message records in this package.
 */
public enum DhcpFrameType {
    /** Client → Server: open connection. */
    CONNECT,
    /** Server → Client: connection accepted (with negotiated sessionId) or rejected. */
    CONNECT_ACK,
    /** Client → Server: present pairing code or short-lived access token. */
    AUTH,
    /** Server → Client: token accepted; on reject returns ERROR. */
    AUTH_SUCCESS,
    /** Client → Server: subscribe to event topics. */
    SUBSCRIBE,
    /** Server → Client: subscription registered, ready for traffic. */
    READY,
    /** Server → Client: push an event (chat, phone-notification, game, …). */
    EVENT,
    /** Client → Server: acknowledge event receipt (per-connection sequence, V10 §49). */
    EVENT_ACK,
    /** Client → Server: invoke a simulator command (sendMessage, readMessages, …). */
    COMMAND,
    /** Server → Client: command result. */
    COMMAND_RESULT,
    /** Heartbeat request. */
    PING,
    /** Heartbeat reply. */
    PONG,
    /** Error frame (server → client for protocol/auth/command errors). */
    ERROR,
    /** Client → Server or Server → Client: clean disconnect notice. */
    DISCONNECT
}
