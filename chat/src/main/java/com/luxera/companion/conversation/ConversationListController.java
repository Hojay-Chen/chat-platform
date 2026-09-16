package com.luxera.companion.conversation;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.contracts.api.MessageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「我的会话」—— 只按 {@code conversationId} 寻址的会话面。
 *
 * <h2>为什么另立一个顶层路径, 而不是往 {@code /api/companions/{c}/conversations} 里加</h2>
 *
 * 两个理由, 后一个是硬的:
 * <ol>
 *   <li>聊天 tab 要的是"我**所有**会话", 而老面的每一条路由都以 {@code companionId}
 *       开头 —— 拿全局列表必须先知道有哪些伴侣, 那就成了 N 次请求。</li>
 *   <li><b>新端点不能放在 {@code /api/companions/**} 下面。</b> {@code
 *       CompanionDomainProxyController} 用 {@code @RequestMapping({"/api/companions",
 *       "/api/companions/**"})} 兜住整个子树转发给 8091。今天 8081 自己的端点靠"更精确的
 *       映射赢过 /**"活着, 那是 Spring 的 {@code AntPatternComparator} 在替我们做排序 ——
 *       可以依赖, 但不该主动往那个战场里再加一条。{@code /api/conversations} 与它零交集。</li>
 * </ol>
 *
 * <h2>没有 {@code POST /api/conversations/{id}/messages}</h2>
 *
 * <p>刻意没写。那个端点的语义是"真人发消息, 不经过 LLM", 而**一期里每一个会话的对面
 * 都是一个数字人** —— 从这条路发出去的消息不会触发任何事件, agent 永远不会回。
 * 今天它唯一的用处是把"agent 不回我了"这个 bug 引进来的话, 那不如等二期真人会话
 * 真的存在时再写。
 *
 * <p>发消息仍然走 {@code POST /api/companions/{c}/conversations/{v}/messages}
 * (那边会触发 agent)。二期加真人会话时, 这个端点在这里落地, 并且**不**触发 LLM。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationListController {

    private final ConversationService conversationService;
    private final ConversationReadStateService readStateService;
    private final CurrentUser currentUser;

    public ConversationListController(ConversationService conversationService,
                                      ConversationReadStateService readStateService,
                                      CurrentUser currentUser) {
        this.conversationService = conversationService;
        this.readStateService = readStateService;
        this.currentUser = currentUser;
    }

    /** 我的全部会话, 最近的排前面。置顶由前端排（服务端排了前端还要再排一次） */
    @GetMapping
    public List<ConversationSummaryView> list() {
        return conversationService.summariesFor(currentUser.requireUserId());
    }

    @GetMapping("/{conversationId}")
    public ConversationSummaryView one(@PathVariable String conversationId) {
        String userId = currentUser.requireUserId();
        return conversationService.summaryOf(userId,
                conversationService.requireVisible(userId, conversationId));
    }

    @GetMapping("/{conversationId}/messages")
    public List<MessageView> messages(@PathVariable String conversationId) {
        String userId = currentUser.requireUserId();
        conversationService.requireVisible(userId, conversationId);
        return MessageViews.toViews(conversationService.messages(conversationId));
    }

    /**
     * 读到某条消息为止。{@code lastMessageId} 可以不传 —— 不传就是"全读了"。
     *
     * <p>进入聊天室、以及窗口重新获得焦点时调它。不做成"拉一次消息就算读了":
     * 拉消息是渲染路径, 读到哪是用户行为, 两者混在一起会让"我只是切了个窗口"
     * 也算读完了。
     */
    @PostMapping("/{conversationId}/read")
    public void markRead(@PathVariable String conversationId,
                         @RequestBody(required = false) ReadRequest req) {
        String userId = currentUser.requireUserId();
        conversationService.requireVisible(userId, conversationId);
        readStateService.markRead(conversationId, userId, req == null ? null : req.getLastMessageId());
    }

    @PostMapping("/{conversationId}/pin")
    public void pin(@PathVariable String conversationId, @RequestBody PinRequest req) {
        String userId = currentUser.requireUserId();
        conversationService.requireVisible(userId, conversationId);
        readStateService.setPinned(conversationId, userId, req == null || req.getPinned() == null || req.getPinned());
    }

    @PostMapping("/{conversationId}/mute")
    public void mute(@PathVariable String conversationId, @RequestBody PinRequest req) {
        String userId = currentUser.requireUserId();
        conversationService.requireVisible(userId, conversationId);
        readStateService.setMuted(conversationId, userId, req != null && Boolean.TRUE.equals(req.getMuted()));
    }

    @lombok.Data
    public static class ReadRequest {
        private String lastMessageId;
    }

    @lombok.Data
    public static class PinRequest {
        private Boolean pinned;
        private Boolean muted;
    }
}
