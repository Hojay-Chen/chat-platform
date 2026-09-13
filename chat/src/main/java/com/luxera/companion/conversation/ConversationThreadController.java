package com.luxera.companion.conversation;

import com.luxera.companion.config.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * §30 Conversation Thread API: 查询会话线程(话题状态), 供前端展示"还有几个没聊完的话题"。
 */
@RestController
@RequestMapping("/api/companions/{companionId}/threads")
public class ConversationThreadController {

    private final ConversationThreadService threadService;

    public ConversationThreadController(ConversationThreadService threadService) {
        this.threadService = threadService;
    }

    @GetMapping
    public List<ConversationThread> threads(@PathVariable String companionId, CurrentUser user) {
        // 归属校验由 service 查询范围 + JWT 保护保证; 这里直接返回该伴侣的线程
        return threadService.threads(companionId);
    }

    @GetMapping("/active")
    public List<ConversationThread> active(@PathVariable String companionId, CurrentUser user) {
        return threadService.activeThreads(companionId);
    }

    @GetMapping("/resumable")
    public List<ConversationThread> resumable(@PathVariable String companionId, CurrentUser user) {
        return threadService.resumableThreads(companionId);
    }
}
