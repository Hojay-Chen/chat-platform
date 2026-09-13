package com.luxera.companion.contracts.application;

/**
 * LAP v1 — a coarse-grained "what can be done" (e.g. {@code game.play}, {@code reminder.manage}).
 *
 * <p>Capabilities exist so an agent never has to choose between fifty thousand actions. The
 * discovery path is narrow-to-wide: user intent → capability → candidate applications → chosen
 * application → its actions → one action. This type is the first step of that path, which is why
 * the capability catalogue matters more than any single adapter.
 *
 * @param capabilityId stable id, dotted lowercase ({@code game.play})
 * @param title        short human-readable name
 * @param description  one line describing what installing this lets an application do; this text
 *                     is rendered into the digital human's prompt, so write it for a reader
 * @param category     grouping for the UI and for prompt rendering ({@code game}, {@code life}, …)
 */
public record CapabilityView(
        String capabilityId,
        String title,
        String description,
        String category
) {}
