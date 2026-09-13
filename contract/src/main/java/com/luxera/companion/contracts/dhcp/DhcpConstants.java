package com.luxera.companion.contracts.dhcp;

/**
 * V10 §62 Simulator protocol constants.
 *
 * <p>Protocol name: <b>DHCP</b> (Digital Human Chat Protocol). Versioned; current is <b>v1</b>.
 * Wire format: JSON text frames over WebSocket. Each frame is a single DhcpFrame envelope.
 *
 * <p>Connection lifecycle (V10 §63):
 * <pre>
 *   Client                  Server
 *   CONNECT   ───────►
 *              ◄───────  CONNECT_ACK (or REJECT)
 *   AUTH      ───────►
 *              ◄───────  AUTH_SUCCESS (or REJECT)
 *   SUBSCRIBE ───────►
 *              ◄───────  READY
 *
 *   (loop: server pushes EVENT, client replies EVENT_ACK; client sends COMMAND, server replies COMMAND_RESULT)
 *
 *   PING      ◄────►  PONG         (30s heartbeat)
 *   DISCONNECT────────►             (clean shutdown; client may also just close socket)
 * </pre>
 */
public final class DhcpConstants {
    private DhcpConstants() {}

    /** Protocol version. Bump on backward-incompatible change. */
    public static final String PROTOCOL_VERSION = "dhcp.v1";

    /** Default heartbeat interval (ms). Server closes connection if no PING in 2x. */
    public static final int DEFAULT_PING_INTERVAL_MS = 30_000;

    /** Default access-token TTL (seconds). Issued by SimulatorTokenService. */
    public static final int DEFAULT_TOKEN_TTL_SECONDS = 300;

    /** Maximum events buffered per (deviceId, topic) for resume. */
    public static final int MAX_RESUME_BUFFER = 1_000;

    /** Simulator WebSocket endpoint. */
    public static final String WS_PATH = "/ws/simulator";
}
