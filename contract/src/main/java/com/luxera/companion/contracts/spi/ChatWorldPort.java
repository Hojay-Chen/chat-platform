package com.luxera.companion.contracts.spi;

import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V10 §3.1 — the digital-human platform's <em>only</em> way to reach the chat platform.
 *
 * <p>Implemented by {@code chat-platform}. The chat platform is the system of record for users,
 * conversations and messages; the digital human reads the world through this port and writes into
 * it through {@link #append}. Nothing here exposes a chat entity, a JPA repository or a
 * transaction: swapping the chat platform for a different messenger means writing one new adapter
 * for this interface.
 *
 * <p>All methods are synchronous and must be safe to call from the chat request thread as well as
 * from the digital human's own scheduler threads.
 */
public interface ChatWorldPort {

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Every message in a conversation, ascending by creation time. */
    List<MessageView> messages(String conversationId);

    /** The most recent {@code limit} messages in a conversation, ascending. */
    List<MessageView> recentMessages(String conversationId, int limit);

    Optional<MessageView> message(String messageId);

    /** Messages a specific human sent across all conversations, ascending. */
    List<MessageView> userMessagesSince(String companionId, LocalDateTime since);

    /** Messages in a half-open window, ascending — used by reflection and summarisation. */
    List<MessageView> messagesBetween(String companionId, LocalDateTime since, LocalDateTime until);

    /** Recent messages of one {@code messageKind} (e.g. {@code PROACTIVE}), newest first. */
    List<MessageView> recentByCompanionAndKind(String companionId, String kind, int limit);

    long countByCompanionAndKindSince(String companionId, String kind, LocalDateTime since);

    Optional<ConversationView> conversation(String conversationId);

    List<ConversationView> conversations(String userId, String companionId);

    /** Every thread this digital human holds, newest activity first. */
    List<ConversationView> conversationsOf(String companionId);

    /** The single human↔digital-human thread, if it exists. */
    Optional<ConversationView> conversationFor(String userId, String companionId);

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Find-or-create the conversation between a human and a digital human. The title is derived by
     * the chat platform from {@code companionName}; the digital human does not get to name threads.
     */
    ConversationView ensureConversation(String userId, String companionId, String companionName);

    /**
     * Persist one message. Idempotent on {@link MessageAppendCommand#idempotencyKey()} when present
     * — a replayed command returns the already-stored message rather than posting twice.
     */
    MessageView append(MessageAppendCommand command);

    /** Mark the human's messages as read by the digital human. */
    void markRead(String companionId, Collection<String> messageIds);

    /** Move messages along the PENDING → DELIVERED → READ lifecycle. */
    void updateDeliveryStatus(String companionId, Collection<String> messageIds, String status);

    /**
     * Overwrite the perception the digital human attached to a stored message (an LLM refines the
     * heuristic reading a few hundred milliseconds after arrival). Null fields are left untouched.
     */
    void updatePerception(String messageId, String intent, String emotion, String topic);

    /**
     * Emit a platform event on the conversation event stream (the SSE feed the client subscribes
     * to). {@code type} is an event name from
     * {@link com.luxera.companion.contracts.events.ChatEventTypes}.
     */
    void publishEvent(String companionId, String type, Map<String, Object> payload);

    /**
     * Record a conversation boundary — the digital human decided the exchange is over
     * ({@code SOFT_END}, {@code HARD_END}, …). Bookkeeping only; nothing is decided here.
     */
    void recordBoundary(String companionId, String conversationId, String type, String reason);

    /**
     * §30 — report what the current exchange is about. The chat platform owns thread state
     * (ACTIVE → PAUSED → RESUMABLE → ABANDONED) and decays it on its own schedule; the digital
     * human only supplies its reading of the topic and the emotional colour.
     */
    void touchThread(String companionId, String conversationId, String topic, String emotion);
}
