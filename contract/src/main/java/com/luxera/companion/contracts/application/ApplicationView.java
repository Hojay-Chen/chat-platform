package com.luxera.companion.contracts.application;

import java.util.List;

/**
 * LAP v1 — one application as seen by a client or an agent: identity plus the capabilities it
 * offers. Returned by discovery when narrowing "intent → capability → candidate applications".
 *
 * <p>Deliberately not the full manifest: an agent choosing an application does not need the
 * action list yet, and shipping it here would tempt callers into skipping the capability step.
 *
 * @param applicationId stable, globally unique, reverse-DNS ({@code com.luxera.tictactoe})
 * @param version       the published version this view describes
 * @param name          display name
 * @param description   one line, safe to render into an LLM prompt
 * @param category      grouping
 * @param capabilities  capability ids this version declares
 */
public record ApplicationView(
        String applicationId,
        String version,
        String name,
        String description,
        String category,
        List<String> capabilities
) {}
