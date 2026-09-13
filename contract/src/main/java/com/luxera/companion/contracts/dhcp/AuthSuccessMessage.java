package com.luxera.companion.contracts.dhcp;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Set;

/**
 * V10 §63 AUTH_SUCCESS message. Server returns the sessionId, granted scopes, and the absolute
 * deadline (epoch millis) at which the session must be re-authenticated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthSuccessMessage(
        String sessionId,
        Set<String> grantedScopes,
        Instant expiresAt
) {}
