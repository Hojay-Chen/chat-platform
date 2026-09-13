package com.luxera.companion.contracts.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V10 §44 Cross-module read-only view of a user. DH-side gets this from
 * {@code SimulatorClient.getUser} / {@code listContacts}. The chat-side never reveals a
 * {@code userKind} here — to the rest of the system, every user looks the same
 * (V10 §30: "Simulator 登录的就是普通 User Account").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserView(
        String id,
        String displayName,
        String avatarUrl
) {}
