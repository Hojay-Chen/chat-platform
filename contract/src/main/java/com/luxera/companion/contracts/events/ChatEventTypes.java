package com.luxera.companion.contracts.events;

/**
 * Names of the events the chat platform fans out on a conversation's server-sent event stream.
 *
 * <p>Both platforms must agree on these strings and nothing else about them: the chat platform
 * owns the stream, the digital-human platform only asks for an event to be emitted through
 * {@link com.luxera.companion.contracts.spi.ChatWorldPort#publishEvent}. Keeping the constants in
 * {@code contracts} is what lets the two modules compile against the same vocabulary without
 * either one depending on the other.
 */
public final class ChatEventTypes {

    private ChatEventTypes() {
    }

    /** The human's message changed delivery state: {@code {messageId, status}}. */
    public static final String USER_MESSAGE_STATUS = "user_message_status";

    /** The digital human read the human's message: {@code {messageId}}. */
    public static final String MESSAGE_READ = "message_read";

    /** The digital human is composing a reply: {@code {typing: true|false}}. */
    public static final String COMPANION_TYPING = "companion_typing";

    /** The digital human said something: {@code {messageId, content, senderType}}. */
    public static final String COMPANION_MESSAGE = "companion_message";

    /** A message was persisted (canonical id for the client's optimistic bubble). */
    public static final String MESSAGE_CREATED = "message_created";

    /** A coarse state change worth surfacing to the client (mood, availability). */
    public static final String COMPANION_STATE = "companion_state";

    /** Platform-level notice (maintenance, degradation). */
    public static final String SYSTEM_EVENT = "system_event";

    /** An application/game event inside a conversation: {@code {appCode, type, ...}}. */
    public static final String GAME_EVENT = "game_event";
}
