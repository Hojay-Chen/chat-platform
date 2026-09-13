package com.luxera.companion.conversation;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.contracts.chat.ApplicationInvitation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LAP v2 §63/§65: <b>聊天里的应用</b> —— 三个端点, 而且只有三个。
 *
 * <pre>
 *   GET  /api/companions/{c}/conversations/{conv}/applications
 *        这段对话里能开什么、已经开着什么
 *
 *   POST /api/companions/{c}/conversations/{conv}/applications
 *        在这段对话里开一个应用(并落一条能点开的卡片消息)
 *
 *   POST /api/companions/{c}/conversations/{conv}/applications/{sessionId}/share
 *        把进这一场的链接作为一条消息发出去
 * </pre>
 *
 * <h2>为什么路径长这样, 而不是 §65 写的 {@code /api/applications}</h2>
 * <p>因为聊天的每一个资源都长在它所属的那段对话下面 —— 消息
 * ({@code .../conversations/{id}/messages})、线程、参与者, 全部如此。把应用端点的路径
 * 拉平到 {@code /api/applications}, 会让"这段对话里"这个<em>唯一</em>让它成立的前提
 * 从路径里消失, 于是它看上去像一个全局的应用管理面 —— 而它不是, 它一次只服务一段对话。
 *
 * <p>§65 还给应用平台提了 {@code /api/applications}; 那是一套<em>同一批数据</em>的第二组路径,
 * 已按计划修正 6 去掉(应用平台只保留 {@code /api/v1})。所以这里不是"抄了一份", 而是
 * "只有这一份": 聊天侧的三个端点是<em>对话上下文</em>视图, 别的能力(发现、动作、邀请管理、
 * 开发者 API)一律回应用平台。
 *
 * <h2>身份只有一个来源</h2>
 * <p>{@link CurrentUser#requireUserId()}。没有 {@code userId} 查询参数, 也没有请求体里的
 * {@code principalType} —— §36 在聊天侧同样是硬要求, 而且这里比对的地方更明显: 一个能自报
 * 身份的 join 请求就是"谁能替别人报名"的入口。
 */
@RestController
@RequestMapping("/api/companions/{companionId}/conversations/{conversationId}/applications")
public class ConversationApplicationController {

    private final CurrentUser currentUser;
    private final ConversationApplicationService applications;

    public ConversationApplicationController(CurrentUser currentUser,
                                             ConversationApplicationService applications) {
        this.currentUser = currentUser;
        this.applications = applications;
    }

    /**
     * 这段对话的应用上下文。
     *
     * <p>两个列表: {@code openable} 是此刻能开的应用卡片, {@code open} 是这段对话里
     * 开过的会话。<b>不合并成一个带 {@code opened} 标志的列表</b> —— 一个应用可以在这段对话里
     * 开着好几局(下完一局再来一局), 那时"合并"就必须先发明一条合并规则, 而任何一条合并规则
     * 都会在某些真实情况下说出错的话。
     */
    @GetMapping
    public ConversationApplicationService.ContextView context(@PathVariable String companionId,
                                                              @PathVariable String conversationId) {
        return applications.context(currentUser.requireUserId(), companionId, conversationId);
    }

    /**
     * 在这段对话里开一个应用。
     *
     * <p>响应里既有 §16 那个形状({@code launch.session} / {@code launch.participant}),
     * 也有这次落下的那条消息的 id —— 后者是本端点独有的(应用平台不知道"开应用还要落一条消息"),
     * 所以它加在这一层, 而不是塞进契约类型。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> open(@PathVariable String companionId,
                                                    @PathVariable String conversationId,
                                                    @RequestBody(required = false) ConversationApplicationService.OpenRequest request) {
        ConversationApplicationService.OpenResult result = applications.open(
                currentUser.requireUserId(), companionId, conversationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "session", result.launch().session(),
                "participant", result.launch().participant(),
                "messageId", result.messageId()));
    }

    /**
     * 把进这一场的链接发到对话里。
     *
     * <p>{@code token} 在这个响应里<em>非空</em>, 此后永远为空(见 {@code ApplicationInvitation})。
     * 唯一能铸票的人是会话主人 —— 那条判据在应用平台里, 不在这里: 聊天侧不知道"谁是主人",
     * 它也没必要知道。
     */
    @PostMapping("/{sessionId}/share")
    public ResponseEntity<Map<String, Object>> share(@PathVariable String companionId,
                                                     @PathVariable String conversationId,
                                                     @PathVariable String sessionId,
                                                     @RequestBody(required = false) ShareRequest request) {
        ConversationApplicationService.ShareResult result = applications.share(
                currentUser.requireUserId(), companionId, conversationId, sessionId,
                request == null ? null : request.role(),
                request == null ? null : request.maxUses());
        ApplicationInvitation invitation = result.invitation();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invitationId", invitation.invitationId());
        body.put("sessionId", invitation.sessionId());
        body.put("token", invitation.token());
        body.put("joinUrl", invitation.joinUrl());
        body.put("role", invitation.role());
        body.put("maxUses", invitation.maxUses());
        body.put("messageId", result.messageId());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** {@code role} 空 → MEMBER; {@code maxUses} 空 → 不限次数。两个都只能由主人定。 */
    public record ShareRequest(String role, Integer maxUses) {}
}
