package com.luxera.companion.contracts.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * LAP v1 — one event vocabulary, shared by humans, agents and the platform.
 *
 * <p>Six fields, and deliberately no seventh: a parallel event system is the named failure mode
 * this shape exists to prevent. An application emits these; the platform converts them to its
 * internal {@code ExternalEvent} and feeds the existing processing chain. Nothing downstream
 * invents its own envelope.
 *
 * <p><b>{@code id} must be deterministic</b> for any event an agent should react to. The
 * application derives it from its manifest's {@code events[].idTemplate} (e.g.
 * {@code game://session/{id}#move-4}), never from a random UUID — a fresh id on every retry
 * would defeat deduplication and let the agent act twice on one event. The manifest validator
 * rejects {@code triggersAgent} events that declare no template.
 *
 * <p><b>{@code data} is not quite opaque.</b> For an event whose manifest declaration sets
 * {@code triggersAgent}, the platform reads exactly two keys out of it — everything else it copies
 * through untouched:
 * <ul>
 *   <li>{@code notifyPrincipalIds} (string[]) — <em>who else should know about this</em>. The
 *       application states it in its own vocabulary, as a list of principals; it never says which
 *       of them is an agent, because it must not know. The platform resolves that against
 *       {@code installation} and stamps {@code data.companionId} (plus {@code data.userId} when the
 *       routed digital human has a session in this application) before handing the event on.</li>
 *   <li>{@code agentTrigger} (boolean, default false) — this <em>particular</em> occurrence is worth
 *       waking someone for. The manifest's {@code triggersAgent} says the type <em>may</em> wake a
 *       digital human; this says this one <em>should</em>. An event with no routable recipient is
 *       dropped either way.</li>
 * </ul>
 *
 * @param id         deterministic event id
 * @param type       application-defined event type ({@code game.move}, {@code reminder.due})
 * @param source     applicationId of the emitter
 * @param target     resource URI the event is about
 * @param occurredAt when it happened
 * @param data       event payload; the platform reads {@code notifyPrincipalIds}/{@code agentTrigger}
 *                   from it and adds {@code companionId}/{@code userId}, copying all other keys through
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationEvent(
        String id,
        String type,
        String source,
        String target,
        Instant occurredAt,
        JsonNode data
) {}
