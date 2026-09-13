package com.luxera.companion.contracts.provision;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * V10 §11 — the persona as a structured JSON document. Cross-module canonical shape (chat and
 * DH both speak this; the human-friendly form is DH-side only).
 */
public record PersonaJson(JsonNode identity, JsonNode relationship, JsonNode personality,
                          JsonNode communication, JsonNode behaviors, JsonNode values,
                          JsonNode boundaries, JsonNode life) {}
