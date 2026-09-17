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

    /**
     * 「给这个 Agent 开一段新对话」。
     *
     * <h2>为什么这里**不要**把 {@code ref.peerMemberId()} 传下去</h2>
     *
     * 它看起来正是该传的东西: 对面返回的"这个 Agent 在聊天平台上的参与者 id", 而建会话时
     * 要决定参与者的 member_id。但那样做等于让"这个 Agent 用哪个聊天账号说话"这个问题的
     * 答案来自**另一个平台**, 而这个问题的答案本来就在本仓: {@code simulator_devices} 里
     * {@code companion_id} 与 {@code account_id} 的绑定是本仓写的。
     *
     * <p>传下去的实际代价是: Agent 平台重启一次, 「打开一段新对话」这个动作就会失败 ——
     * 而开一段对话完全不需要它在场。走 {@code ConversationService} 里的本地查询
     * ({@code AgentChatIdentity}) 则两条路都通: 对面在不在都能建会话, 而且新建的会话与
     * 历史会话用的是**同一条规则**算出来的 member_id。
     */
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
        conversationService.requireVisible(userId, conversationId);
        return conversationService.messages(conversationId);
    }

    /** §五十二: 会话参与者(一对一 = Agent + User; 未来群聊多参与者) */
    @GetMapping("/{conversationId}/participants")
    public List<ConversationParticipant> participants(@PathVariable String companionId,
                                                      @PathVariable String conversationId) {
        String userId = currentUser.requireUserId();
        companionDirectory.requireOwned(userId, companionId);
        conversationService.requireVisible(userId, conversationId);
        return participantService.participants(conversationId);
    }

    @Data
    public static class CreateRequest {
        private String title;
    }
}
