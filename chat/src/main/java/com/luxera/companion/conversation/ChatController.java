package com.luxera.companion.conversation;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * V10 §2 — the chat platform's conversation API: list threads, read history, read participants.
 *
 * <p>Everything that requires knowing what the other side <em>is</em> — opening a thread for the
 * first time and streaming a reply — lives in the digital-human platform's
 * {@code ChatStreamController}, which maps the same base path. Chat answers "what was said";
 * the digital human answers "what should be said".
 *
 * <p>The only outbound dependency is {@link CompanionDirectoryPort}, used to check that the caller
 * owns the peer they are addressing.
 */
@RestController
@RequestMapping("/api/companions/{companionId}/conversations")
public class ChatController {

    private final ConversationService conversationService;
    private final CompanionDirectoryPort companionDirectory;
    private final CurrentUser currentUser;
    private final ConversationParticipantService participantService;

    public ChatController(ConversationService conversationService,
                          CompanionDirectoryPort companionDirectory,
                          CurrentUser currentUser,
                          ConversationParticipantService participantService) {
        this.conversationService = conversationService;
        this.companionDirectory = companionDirectory;
        this.currentUser = currentUser;
        this.participantService = participantService;
    }

    @GetMapping
    public List<Conversation> list(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionDirectory.requireOwned(userId, companionId);
        return conversationService.list(userId, companionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@PathVariable String companionId, @RequestBody(required = false) CreateRequest req) {
        String userId = currentUser.requireUserId();
        var ref = companionDirectory.requireOwned(userId, companionId);
        return conversationService.create(userId, companionId,
                req != null ? req.getTitle() : null, ref.name());
    }

    @GetMapping("/{conversationId}/messages")
    public List<Message> messages(@PathVariable String companionId, @PathVariable String conversationId) {
        String userId = currentUser.requireUserId();
        companionDirectory.requireOwned(userId, companionId);
        conversationService.requireOwned(userId, conversationId);
        return conversationService.messages(conversationId);
    }

    /** §五十二: 会话参与者(一对一 = Agent + User; 未来群聊多参与者) */
    @GetMapping("/{conversationId}/participants")
    public List<ConversationParticipant> participants(@PathVariable String companionId,
                                                      @PathVariable String conversationId) {
        String userId = currentUser.requireUserId();
        companionDirectory.requireOwned(userId, companionId);
        conversationService.requireOwned(userId, conversationId);
        return participantService.participants(conversationId);
    }

    @Data
    public static class CreateRequest {
        private String title;
    }
}
