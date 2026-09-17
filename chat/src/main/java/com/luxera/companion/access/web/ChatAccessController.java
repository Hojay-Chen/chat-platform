package com.luxera.companion.access.web;

import com.luxera.companion.application.principal.ApiKeyPrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.conversation.AgentChatIdentity;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationReadState;
import com.luxera.companion.conversation.ConversationReadStateService;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对外开放面 —— 需求⑦「聊天平台也要为其他 agent 程序提供同等的接入能力」的落点。
 *
 * <h2>它给出去的是能力, 不是数据</h2>
 *
 * 四个动作: 列会话 / 读消息 / 发消息 / 标记已读。这四个正好是"一个 agent 要在一段会话里
 * 活着"所需的全部, 也正好是数字人平台在 {@link ChatWorldPort} 上用到的读写信封。
 *
 * <p>{@code ChatWorldPort} 有二十多个方法, 这里只放四个 —— 开放面必须是内部面的<b>子集</b>,
 * 不是它的镜像。{@code updatePerception} / {@code touchThread} / {@code recordBoundary} 是
 * 数字人认知链的内部概念, {@code purgePeer} 更是"删光一个 peer 的全部历史"; 把它们一起
 * 发出去等于发了一个"任何人都能清空别人聊天记录"的接口, 而调用方要的只是聊天。
 *
 * <h2>租户边界是 chat_api_clients 的那一列</h2>
 *
 * 解出来的 {@code companionId} 来自 {@code chat_api_clients.agent_id}(管理员写入),
 * 调用方自报不了 —— 于是"每把钥匙只能碰它绑定的那一个 Agent 的会话"是一条结构性事实,
 * 而不是一段记得写就有的检查。越界一律 <b>404</b>: 403 等于承认"这段会话存在, 只是不归你",
 * 而拿别人钥匙的程序没有资格知道那个 id 是否真实存在(见
 * {@code ConversationService.requireOwnedByAgent})。
 *
 * <h2>响应体里没有"人是谁"</h2>
 *
 * 会话视图不带 {@code userId}, 消息视图不带 {@code senderId} —— 而这两列在库里的值正是
 * 人类那一侧的 {@code users.id}。这不是疏漏: 一把钥匙绑的是一个 agent, 它名下的会话可能
 * 分别属于不同的人, 把人的账号 id 发出去, 就等于给了第三方一把"把同一个人在不同 agent
 * 之间串起来"的尺子, 而这对"在一段会话里收发消息"毫无用处(谁在说话由 {@code senderType}
 * 回答, 一对一里这就是全部所需)。数字人平台能看到 {@code userId} 是因为它是平台自身的一部分,
 * 走的是进程内端口而不是这把钥匙。
 *
 * <h2>为什么错误体不是 LAP 的 {@code ActionResponse}</h2>
 *
 * {@code LapExceptionHandler} 是一个 {@code @Order(HIGHEST_PRECEDENCE)} 的全局 advice, 它会把
 * {@link PrincipalResolver.PrincipalException} 渲染成 LAP 的信封。接入面的调用方读的是聊天
 * 平台的公开文档, 那里所有其它端点的错误体都是 {@code {error, hint}} 形状 —— 于是这里在
 * 控制器内就地捕获并自己渲染, 不让它漂到 LAP 的 advice 上去。附带好处是 503 与 401 的分野
 * 写在看得见的地方(见 {@link #failure})。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
public class ChatAccessController {

    /** 消息的 {@code sender_type}: 从聊天平台看, 第三方 agent 就是会话的另一方。 */
    private static final String SENDER_TYPE_AGENT = "companion";

    /**
     * 允许第三方程序指定的 {@code messageKind}。
     *
     * <p>刻意<b>不含</b> {@code SYSTEM}(平台通告)与 {@code TOOL_RESULT}: 前者在客户端上
     * 渲染成居中的系统提示 —— 放开它就等于让任何持钥匙的程序伪造一条平台公告; 后者是
     * 平台自己往里写的工具结果信封。白名单而不是黑名单: 消息种类以后还会加, 而一个新种类
     * 该不该对外开放, 只有加它的人知道。
     */
    private static final Set<String> ALLOWED_KINDS =
            Set.of("NORMAL", "SHORT_ACK", "PROACTIVE", "FOLLOW_UP");

    private final PrincipalResolvers principals;
    private final ConversationService conversations;
    private final ConversationReadStateService readStates;
    private final ChatWorldPort chatWorld;
    private final AgentChatIdentity agentIdentity;

    public ChatAccessController(PrincipalResolvers principals,
                                ConversationService conversations,
                                ConversationReadStateService readStates,
                                ChatWorldPort chatWorld,
                                AgentChatIdentity agentIdentity) {
        this.principals = principals;
        this.conversations = conversations;
        this.readStates = readStates;
        this.chatWorld = chatWorld;
        this.agentIdentity = agentIdentity;
    }

    // ── 列会话 ────────────────────────────────────────────────────────────────

    /**
     * 「我(这个 agent)参与的全部会话」, 最近说过话的在前。
     *
     * <p>走的是 {@code conversations.companion_id} 而不是参与者表 —— 这正是数字人平台
     * ({@code ChatWorldAdapter.conversationsOf})看到的那一份。两个面给同一个 agent 的
     * 答案必须是同一份, 否则"第三方看到的会话"与"它自己认知里的会话"会漂移。
     */
    @GetMapping("/conversations")
    public ResponseEntity<?> listConversations(
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_API_KEY, required = false) String apiKey,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_ADMIN_KEY, required = false) String adminKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal me;
        try {
            me = principals.resolveApiKey(apiKey, adminKey, correlationId);
        } catch (PrincipalResolver.PrincipalException e) {
            return failure(e);
        }
        if (me.type() != PrincipalType.EXTERNAL_AGENT) {
            return notAClientKey();
        }

        List<Conversation> convs = conversations.listOfAgent(me.companionId());
        // 读状态一次取回再在内存里对 —— 每个会话查一次是这段代码最自然的写法, 也是它
        // 在会话变多之后变慢的原因(与 ConversationService.summariesFor 同一条理由)。
        Map<String, ConversationReadState> states = readStates.statesOf(
                memberIdOf(me.companionId(), agentIdentity.chatAccountIdOf(me.companionId())));

        List<AccessConversation> out = new ArrayList<>(convs.size());
        for (Conversation c : convs) {
            ConversationReadState state = states.get(c.getId());
            out.add(new AccessConversation(c.getId(), c.getTitle(), c.getStatus(),
                    c.getLastMessageAt(), c.getMessageCount(),
                    state == null ? 0 : state.getUnreadCount()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // agentId 回显的是"平台确认的你是谁", 与请求头里那把钥匙无关 —— 钥匙配错 agent
        // 是这套机制唯一容易犯的错, 而它在这一行之前是完全不可见的。
        body.put("agentId", me.companionId());
        body.put("conversations", out);
        return ResponseEntity.ok(body);
    }

    // ── 读消息 ────────────────────────────────────────────────────────────────

    /** 一段会话里说过的话, 时间升序。{@code limit} 给了就只取最近这么多条。 */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<?> listMessages(
            @PathVariable String conversationId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_API_KEY, required = false) String apiKey,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_ADMIN_KEY, required = false) String adminKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal me;
        try {
            me = principals.resolveApiKey(apiKey, adminKey, correlationId);
        } catch (PrincipalResolver.PrincipalException e) {
            return failure(e);
        }
        if (me.type() != PrincipalType.EXTERNAL_AGENT) {
            return notAClientKey();
        }

        // 归属先判, 再取内容 —— 顺序不能反: 先查消息再判归属的话, 越界请求在响应时间上
        // 与不存在的会话没有区别, 但日志里会留下痕迹, 而且"取到内存里再丢掉"是个习惯,
        // 迟早有人把它优化成"反正都查出来了, 直接返回吧"。
        conversations.requireOwnedByAgent(me.companionId(), conversationId);

        List<Message> messages = limit == null || limit <= 0
                ? conversations.messages(conversationId)
                : conversations.recentMessages(conversationId, Math.min(limit, 200));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("messages", messages.stream().map(ChatAccessController::toAccessMessage).toList());
        return ResponseEntity.ok(body);
    }

    // ── 发消息 ────────────────────────────────────────────────────────────────

    /**
     * 以这个 agent 的身份说一句话。
     *
     * <h2>为什么落在 {@link ChatWorldPort#append} 而不是 {@code ConversationService.addMessage}</h2>
     *
     * 因为两者不等价, 而差别是用户看得见的那一种: {@code append} 会往会话事件流上发一条
     * {@code COMPANION_MESSAGE}, 前端的气泡当场出现; 直接写 {@code addMessage} 不会 ——
     * 消息在库里, 而屏幕上要刷新才有。<b>这条差别在一次真机排查里暴露过</b>: WS 命令面的
     * {@code chat.sendMessage} 走的就是 {@code addMessage}, 于是"agent 回了话但界面没动"
     * 看起来像前端的问题。数字人平台走的是 {@code append}, 接入面必须与它逐字一致 ——
     * 否则同一个 agent 从两条路说的话, 一条实时一条不实时。
     *
     * <p>{@code idempotencyKey} 也一并免费拿到: 它映射到 {@code messages.client_message_id}
     * (同会话唯一), 于是"超时重发"不会变成两句一样的话。不给就是不去重, 这是调用方的选择。
     */
    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<?> sendMessage(
            @PathVariable String conversationId,
            @RequestBody(required = false) SendBody body,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_API_KEY, required = false) String apiKey,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_ADMIN_KEY, required = false) String adminKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal me;
        try {
            me = principals.resolveApiKey(apiKey, adminKey, correlationId);
        } catch (PrincipalResolver.PrincipalException e) {
            return failure(e);
        }
        if (me.type() != PrincipalType.EXTERNAL_AGENT) {
            return notAClientKey();
        }
        if (body == null || body.content() == null || body.content().isBlank()) {
            return badRequest("content 不能为空", null);
        }

        String kind = body.messageKind() == null || body.messageKind().isBlank()
                ? "NORMAL" : body.messageKind().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_KINDS.contains(kind)) {
            return badRequest("messageKind 不接受 " + body.messageKind(),
                    "可选: " + String.join(" / ", new java.util.TreeSet<>(ALLOWED_KINDS)));
        }

        conversations.requireOwnedByAgent(me.companionId(), conversationId);

        MessageView saved = chatWorld.append(MessageAppendCommand
                .of(conversationId, SENDER_TYPE_AGENT, body.content().trim())
                .withKind(kind)
                .withIdempotencyKey(body.idempotencyKey())
                .withSession(body.sessionId(), body.exchangeId()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conversationId);
        out.put("message", toAccessMessage(saved));
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    // ── 标记已读 ──────────────────────────────────────────────────────────────

    /**
     * 「这个人说的话我都读过了」—— 两件事一起做, 因为对调用方它们是同一件事:
     *
     * <ol>
     *   <li>把这段会话里真人发的消息推进到 {@code READ}(投递状态, 对方据此看到"已读")。</li>
     *   <li>把这个 agent 在这段会话里的未读数清零(读状态, 决定它自己列表上的角标)。</li>
     * </ol>
     *
     * <p>只动这一段会话、且只动 {@code sender_type='user'} 的行: 接入面收的是第三方给的
     * conversationId, 而 {@code updateDeliveryStatus} 按 messageId 改行、不判归属 ——
     * 让它接受任意 messageId 就等于发了一个"把别人会话里任意一条消息标成已读"的接口。
     *
     * <p>刻意<b>不发</b> {@code user_message_status} 事件: 平台自己的数字人走
     * {@code ChatWorldPort.markRead} 时也不发。开放面与内部面在这里必须同行为, 否则
     * "从接入面标已读"会变成一个能让前端刷新的特权动作。
     */
    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<?> markRead(
            @PathVariable String conversationId,
            @RequestBody(required = false) ReadBody body,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_API_KEY, required = false) String apiKey,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_ADMIN_KEY, required = false) String adminKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal me;
        try {
            me = principals.resolveApiKey(apiKey, adminKey, correlationId);
        } catch (PrincipalResolver.PrincipalException e) {
            return failure(e);
        }
        if (me.type() != PrincipalType.EXTERNAL_AGENT) {
            return notAClientKey();
        }

        Conversation conv = conversations.requireOwnedByAgent(me.companionId(), conversationId);

        String lastMessageId = body == null ? null : body.lastMessageId();
        // 未读那一侧按**这段会话记下的那个身份**清, 而不是按 companionId 现推一个 ——
        // 消息落库时 bumpOnMessage 拿到的是 deriveSenderId 的结果, 两者必须是同一个值,
        // 否则清了半天角标还在(见 ConversationService.memberIdOf 的说明)。
        readStates.markRead(conversationId,
                memberIdOf(conv.getCompanionId(), conv.getAgentAccountId()), lastMessageId);
        int changed = conversations.markHumanMessagesRead(conversationId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conversationId);
        // 回这个数是为了让调用方能区分"标了"与"本来就已读" —— 两者都是成功, 但前者意味着
        // 对方会看到回执刚变化, 后者意味着什么都没发生。
        out.put("markedRead", changed);
        return ResponseEntity.ok(out);
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    /**
     * 这个 agent 在这段会话里的 member_id —— 与 {@code ConversationService.memberIdOf}
     * 逐字同一条回退规则(有账号用账号, 没有退回 companionId)。
     *
     * <p>抄这条规则而不是另立一条, 是因为它决定"未读算在谁头上"。两边一旦不一致, 表现是
     * "标了已读但角标还在", 而两段代码各自看都是对的 —— 这类 bug 只有把两条规则并排看才看得出来。
     */
    private static String memberIdOf(String companionId, String agentAccountId) {
        return agentAccountId != null && !agentAccountId.isBlank() ? agentAccountId : companionId;
    }

    private static AccessMessage toAccessMessage(Message m) {
        return new AccessMessage(m.getId(), m.getConversationId(), m.getSenderType(), m.getContent(),
                m.getMessageKind(), m.getDeliveryStatus(), m.isProactive(), m.getCreatedAt());
    }

    private static AccessMessage toAccessMessage(MessageView m) {
        return new AccessMessage(m.getId(), m.getConversationId(), m.getSenderType(), m.getContent(),
                m.getMessageKind(), m.getDeliveryStatus(), m.isProactive(), m.getCreatedAt());
    }

    /**
     * 管理钥匙走的是客户端面 —— 拒掉, 而不是让它以"没有 companionId 的身份"执行。
     *
     * <p>不拒的话, {@code listOfAgent(null)} 会回一个空列表: 一个**看起来成功**的答复,
     * 而调用方手里那把明明是管理员钥匙。空列表与"你这个 agent 还没有会话"长得一模一样。
     */
    private static ResponseEntity<Map<String, Object>> notAClientKey() {
        Map<String, Object> body = error("本端点只服务接入钥匙(X-Api-Key)");
        body.put("hint", "管理钥匙(X-Admin-Key)请走 /api/v1/chat/clients");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message, String hint) {
        Map<String, Object> body = error(message);
        if (hint != null) body.put("hint", hint);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 401 还是 503, 由<b>原因</b>决定, 不由调用方决定。
     *
     * <p>"钥匙无效"是调用方的问题(401, 换一把); "这个部署没配管理密钥"是运维的问题(503,
     * 没有人能换对钥匙)。把后者答成 401 会让接入方去翻自己的配置, 翻到天亮也翻不出结果。
     *
     * <p>{@code error} 放人话、{@code code} 放机器码 —— 顺序在这里写死, 而不是靠一个
     * {@code error(String, String)} 重载去猜。那个重载曾经存在过, 而它把这一行的两个参数
     * 收到了相反的槽位里: 响应体变成 {@code {"error":"UNIDENTIFIED_PRINCIPAL"}}, 看起来
     * 完全正常, 只有断言 {@code code} 字段的测试会红。
     */
    private static ResponseEntity<Map<String, Object>> failure(PrincipalResolver.PrincipalException e) {
        HttpStatus status = ApiKeyPrincipalResolver.CODE_ADMIN_DISABLED.equals(e.code())
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.UNAUTHORIZED;
        Map<String, Object> body = error(e.getMessage());
        body.put("code", e.code());
        return ResponseEntity.status(status).body(body);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    // ── 线格式 ────────────────────────────────────────────────────────────────

    public record SendBody(String content, String messageKind, String idempotencyKey,
                           String sessionId, String exchangeId) {}

    public record ReadBody(String lastMessageId) {}

    /** 会话视图 —— 刻意不含 {@code userId}(见类注释)。 */
    public record AccessConversation(String id, String title, String status,
                                     LocalDateTime lastMessageAt, int messageCount,
                                     int unreadCount) {}

    /** 消息视图 —— 刻意不含 {@code senderId}(见类注释)。 */
    public record AccessMessage(String id, String conversationId, String senderType, String content,
                               String messageKind, String deliveryStatus, boolean proactive,
                               LocalDateTime createdAt) {}
}
