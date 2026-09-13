package com.luxera.companion.contracts.spi;

import com.luxera.companion.contracts.api.MessageView;

import java.util.List;

/**
 * V10 §2.1 — the chat platform's <em>only</em> way to reach the digital-human platform.
 *
 * <p>Implemented by {@code digital-human-platform}. The chat module never imports a DH type:
 * it learns only the opaque facts it needs (does this user own this companion, what is that
 * companion called, what chat account does it speak through) plus a single fire-and-forget
 * notification that a human message arrived.
 *
 * <p>Deliberately narrow. Anything richer — perception, appraisal, whether she replies at all
 * — is the digital human's own business and must not leak back into the chat platform; the
 * chat platform only ever learns the outcome via {@link ChatWorldPort#append}.
 */
public interface CompanionDirectoryPort {

    /**
     * Assert that {@code userId} owns {@code companionId}.
     *
     * @throws IllegalArgumentException if the companion does not exist or belongs to someone else
     */
    CompanionRef requireOwned(String userId, String companionId);

    /**
     * V10 §5: the human delivered message(s) into a conversation. Fire-and-forget — the digital
     * human decides on its own whether to perceive, ignore, defer or reply, and any reply comes
     * back asynchronously through {@link ChatWorldPort#append}.
     *
     * <p>Implementations must not block the caller on cognition: the chat request thread is a
     * real user waiting for an SSE stream.
     */
    void onUserMessage(String userId, String companionId, String conversationId,
                       List<MessageView> messages);

    /**
     * Minimum facts the chat platform needs about a companion. {@code peerMemberId} is the
     * opaque id chat stores in {@code conversation_participants} for the non-human side —
     * today the companion id, under a provisioned simulator account once pairing is universal.
     */
    record CompanionRef(String companionId, String name, String peerMemberId) {}
}
