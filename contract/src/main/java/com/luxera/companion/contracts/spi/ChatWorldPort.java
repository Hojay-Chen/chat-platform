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

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * The digital human is gone — delete everything the chat platform holds for it, and
     * <strong>return the ids of the conversations that were destroyed</strong>.
     *
     * <h2>Why the caller must be told the ids</h2>
     * The chat platform owns conversations, messages and their satellites; the digital-human
     * platform owns {@code session_summaries}, which is keyed by {@code conversationId} — an id it
     * cannot derive, because it never allocated it (it called {@link #ensureConversation} and was
     * handed one back, possibly days earlier, without keeping the receipt). Handing the list back
     * is therefore part of the contract, not a courtesy: the caller needs it to finish its own
     * half of the deletion.
     *
     * <h2>Why this is not part of "the world" the digital human perceives</h2>
     * Everything else on this port is the digital human reading or writing its own world. This one
     * is the platform saying that peer no longer exists — it is called by the deletion path, never
     * by the digital human's own reasoning, and it is the only operation here that destroys
     * history rather than adding to it.
     *
     * <p>Hard delete, deliberately: this is what a user asking to delete an agent means. The
     * consequence is that <strong>a caller must reach here only from an explicit delete
     * action</strong>, never from an incidental cleanup.
     *
     * <p>Idempotent — a peer with no conversations, or one already purged, returns an empty list
     * rather than failing. Deleting twice must not blow up the second time.
     */
    List<String> purgePeer(String companionId);
}
