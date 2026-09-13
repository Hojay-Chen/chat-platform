package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * V10 §63 COMMAND message. Client → server. Asks the chat platform to perform an action on
 * behalf of the simulator (the digital human's "phone"). {@code command} is a stable string
 * (e.g. {@code chat.sendMessage}, {@code chat.readMessages}, {@code application.open},
 * {@code game.move}); {@code args} is the command-specific JSON payload.
 *
 * <p>{@code idempotencyKey} is required for all mutating commands and uniquely identifies
 * a logical action; the server uses it for de-duplication and safe retry (V10 §48).
 */
public record CommandMessage(
        String command,
        String idempotencyKey,
        JsonNode args
) {}
