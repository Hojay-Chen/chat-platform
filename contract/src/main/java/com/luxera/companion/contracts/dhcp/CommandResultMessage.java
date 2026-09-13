package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * V10 §63 COMMAND_RESULT message. Server → client. {@code ok} true means the command
 * succeeded and {@code data} carries the result body (e.g. a sent message id). On failure
 * {@code ok} is false and {@code error} carries a {@link DhcpError}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandResultMessage(
        boolean ok,
        String command,
        String idempotencyKey,
        JsonNode data,
        DhcpError error
) {
    public static CommandResultMessage ok(String command, String idempotencyKey, JsonNode data) {
        return new CommandResultMessage(true, command, idempotencyKey, data, null);
    }

    public static CommandResultMessage fail(String command, String idempotencyKey, DhcpError error) {
        return new CommandResultMessage(false, command, idempotencyKey, null, error);
    }
}
