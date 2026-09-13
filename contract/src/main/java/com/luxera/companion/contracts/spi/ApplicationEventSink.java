package com.luxera.companion.contracts.spi;

import com.luxera.companion.contracts.application.ApplicationEvent;

/**
 * LAP v1 — where application events go when an agent should hear about them.
 *
 * <p>Implemented by {@code digital-human-platform}, permanently. The platform's event publisher
 * consults the emitting application version's manifest and forwards only the event types marked
 * {@code triggersAgent} — so an application cannot decide to wake an agent up, and the digital
 * human does not have to know which applications exist.
 *
 * <p>Emission happens <b>after the application's transaction commits</b>. Forwarding from inside
 * the transaction would let the digital human observe state that is about to roll back, which is
 * how an agent ends up confidently replying to a move that never happened.
 */
public interface ApplicationEventSink {

    /**
     * Deliver one event. Callers must not assume this returns before the digital human has
     * finished reacting — the current implementation runs the processing chain in-process.
     */
    void emit(ApplicationEvent event);
}
