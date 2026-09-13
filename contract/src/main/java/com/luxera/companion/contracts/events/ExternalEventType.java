package com.luxera.companion.contracts.events;

/**
 * V10 §38 Event types flowing on the External Event Bus. Both chat-side and DH-side resolve
 * events by these names; this enum is the canonical cross-platform vocabulary.
 *
 * <p>The bus itself is in-process per V10 §74, but the envelope shape
 * {@link EventEnvelope} is shared across modules.
 */
public enum ExternalEventType {
    /** V10 §5: a message was persisted and dispatched to the recipient's simulator. */
    CHAT_MESSAGE_DELIVERED,
    /** V10 §5: read-receipt arrived (recipient client confirmed they read). */
    CHAT_MESSAGE_READ,
    /** V10 §5: status of a previously delivered message changed (e.g. DELIVERED → DELIVERED_AND_OPENED). */
    CHAT_MESSAGE_STATUS_CHANGED,
    /** V10 §14: a phone notification was raised for a user (possibly hidden from the agent). */
    PHONE_NOTIFICATION,
    /** V10 §18: the digital human successfully perceived a phone notification. */
    PHONE_NOTIFICATION_PERCEIVED,
    /** V10 §19: a scheduled phone check / open-phone action resolved. */
    PHONE_CHECKED,
    /** V10 §37: an application event (game, mini-app, third-party service) arrived. */
    APPLICATION_EVENT,
    /** Generic life-event trigger (activity ended, plan reminder fired, …). */
    LIFE_EVENT,
    /** V10 §6 §3: a time-based event (clock tick, scheduled action). */
    TIME_EVENT,
    /** Out-of-band system event (e.g. user just registered, conversation archived). */
    SYSTEM_EVENT
}
