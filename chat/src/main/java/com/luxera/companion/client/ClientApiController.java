package com.luxera.companion.client;

import com.luxera.companion.application.principal.ApiKeyPrincipalResolver;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.contracts.client.ChatSession;
import com.luxera.companion.contracts.client.ClientConversation;
import com.luxera.companion.contracts.client.ContactProfile;
import com.luxera.companion.contracts.client.ConversationNotificationSetting;
import com.luxera.companion.contracts.client.MessagePage;
import com.luxera.companion.contracts.client.ProvisionedChatAccount;
import com.luxera.companion.contracts.client.SendResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * V2.2 §6.3 —— <b>聊天平台客户端接口: 一组端点, 两个调用方</b>。
 *
 * <h2>为什么前端与 Agent 用同一套控制器</h2>
 *
 * <p>因为用户要的就是这个: "让聊天平台前端和 agent 调用的是同一套聊天平台后端接口(但这里要
 * 解决授权问题)"。共用带来的好处是具体的 —— 一份接口文档、一套分页语义、一套错误体, 而
 * 最要紧的一条是: <b>Agent 看到的世界与真人看到的逐字相同</b>。分成两套的话, "列表里能看到
 * 但点开是空"这类漂移会在两个实现之间慢慢长出来, 而它只在把两边并排看时才看得出来。
 *
 * <p>授权问题在 {@link ClientPrincipalResolver} 里解决, 不在这里。本控制器从头到尾**只认一个
 * 值** —— {@link ClientPrincipal#accountId()}, 它由凭据推导而来, 调用方自报不了。于是这里
 * 没有任何一处需要问"你是人还是 Agent", 也就没有一处会写歪。
 *
 * <h2>十个动作</h2>
 *
 * <pre>
 *   1  POST /api/client/login                                    打开聊天软件
 *   2  WS   /api/client/stream                                   建立长连接（见 ClientStreamEndpoint）
 *   3  GET  /api/client/conversations[?q=]                       看会话列表 / 搜索（第 10 项）
 *   4  GET  /api/client/conversations/{accountId}/messages       点开某人
 *   5  GET  /api/client/conversations/{accountId}/messages?cursor=  上翻拉更多
 *   6  POST /api/client/conversations/{accountId}/messages       打字发送
 *   7  POST /api/client/conversations/{accountId}/read           已读
 *   8  PUT  /api/client/conversations/{accountId}/notification   设置免打扰 / 置顶
 *   9  GET  /api/client/contacts/{accountId}                     查看某人资料
 *   +  POST /api/client/provision                                §6.5 为 Agent 铸聊天账号
 * </pre>
 *
 * <p>第 4 与第 5 是同一个端点(区别只在带不带 {@code cursor}), 第 10 项与第 3 项也是同一个
 * (区别只在带不带 {@code q}) —— 这正是 §6.3 表里写的形状, 没有为了凑数拆成两条。
 *
 * <h2>{@code provision} 为什么也在这里, 而它的调用方是另一种身份</h2>
 *
 * <p>因为 §6.5 就是把它画在 {@code /api/client/provision} 上的。它接受的是<b>管理密钥</b>
 * ({@code X-Admin-Key}, 平台自己的身份)而不是接入钥匙 —— Agent 的驱动程序不该有能力凭空铸出
 * 聊天账号。这条边界落在 {@link ClientPrincipalResolver#resolveAdmin} 里, 而不是靠路径约定:
 * 一把 {@code cak_} 钥匙走这个端点会被 403, 而它能不能解出 SYSTEM 是
 * {@code ApiKeyPrincipalResolver} 的类型判断说了算。
 *
 * <h2>错误体</h2>
 *
 * <p>全部由 {@link ClientApiExceptionHandler} 渲染成 {@code {error, code?, hint?}} ——
 * 与 {@code /api/v1/chat} 同一个形状。刻意不让它们漂到 LAP 的 {@code ActionResponse} 上:
 * 这个面的调用方读的是聊天平台的公开文档, 那里成功与失败都该是同一套解析逻辑。
 */
@Slf4j
@RestController
@RequestMapping("/api/client")
public class ClientApiController {

    private final ClientPrincipalResolver resolver;
    private final ClientSessionService sessions;
    private final ClientConversationService conversations;
    private final ClientProvisioningService provisioning;

    public ClientApiController(ClientPrincipalResolver resolver,
                               ClientSessionService sessions,
                               ClientConversationService conversations,
                               ClientProvisioningService provisioning) {
        this.resolver = resolver;
        this.sessions = sessions;
        this.conversations = conversations;
        this.provisioning = provisioning;
    }

    // ── 1. 打开聊天软件 ──────────────────────────────────────────────────────

    /**
     * 两条路: 带 {@code X-Api-Key} 的是 Agent 的驱动程序(换一个令牌), 带
     * {@code Authorization: Bearer} 的是前端(它已经有令牌了, 这里只是问"我是哪个账号")。
     *
     * <p>这是**唯一**接受接入钥匙的业务端点 —— 之后每一个请求都只认令牌。见
     * {@link ClientSessionService} 的说明: 让钥匙能直接用, 权限模型里就多出一种"既不是用户
     * 也不是聊天账号"的身份, 而它的权限只能靠每个端点各自记得写多少检查。
     */
    @PostMapping("/login")
    public ChatSession login(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_API_KEY, required = false) String apiKey,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_ADMIN_KEY, required = false) String adminKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return sessions.login(authorization, apiKey, adminKey, correlationId);
    }

    // ── 9. 查看某人资料 ──────────────────────────────────────────────────────

    /**
     * {@code displayName} 对 Agent 一侧恒为空 —— 见
     * {@code ClientConversationService#contact} 的说明("这个人叫什么"是她自己的知识)。
     */
    @GetMapping("/contacts/{accountId}")
    public ContactProfile contact(
            @PathVariable String accountId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return conversations.contact(my(authorization), accountId);
    }

    // ── 3 / 10. 看会话列表 / 搜索 ────────────────────────────────────────────

    /** 一行一个对方的账号 + 未读数; **没有正文预览**(见 {@code ClientConversation})。 */
    @GetMapping("/conversations")
    public List<ClientConversation> listConversations(
            @RequestParam(value = "q", required = false) String q,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return conversations.list(my(authorization), q);
    }

    // ── 4 / 5. 点开某人 / 上翻拉更多 ─────────────────────────────────────────

    /**
     * {@code cursor} 不传就是最新的一屏; 之后原样回传上一页的 {@code nextCursor}。
     *
     * <p>{@code limit} 的上限在服务端({@value ClientConversationService#MAX_PAGE_SIZE} 条) ——
     * 分页由聊天平台控制, 与真实聊天软件一致。
     */
    @GetMapping("/conversations/{accountId}/messages")
    public MessagePage messages(
            @PathVariable String accountId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return conversations.page(my(authorization), accountId, limit, cursor);
    }

    // ── 6. 打字发送 ──────────────────────────────────────────────────────────

    /**
     * 消息以**这个会话账号**的身份落库。真人侧会触发对方的认知, Agent 侧是一次回复 ——
     * 差别在 {@code ClientConversationService#send} 里, 不在这一层。
     *
     * <p>201 而不是 200: 一个新的资源(消息)被创建了, 而它的 id 在 {@link SendResult} 里。
     */
    @PostMapping("/conversations/{accountId}/messages")
    public ResponseEntity<SendResult> sendMessage(
            @PathVariable String accountId,
            @RequestBody(required = false) SendBody body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (body == null) {
            throw ClientApiException.badRequest("请求体不能为空", null);
        }
        SendResult result = conversations.send(my(authorization), accountId,
                body.content(), body.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    // ── 7. 已读 ──────────────────────────────────────────────────────────────

    /**
     * {@code lastMessageId} 可以不传 —— 不传就是"全读了", 调用方不必先查出最后一条的 id
     * (与 {@code ConversationListController.markRead} 同一条约定)。
     */
    @PostMapping("/conversations/{accountId}/read")
    public void markRead(
            @PathVariable String accountId,
            @RequestBody(required = false) ReadBody body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        conversations.markRead(my(authorization), accountId,
                body == null ? null : body.lastMessageId());
    }

    // ── 8. 设置免打扰 / 置顶 ────────────────────────────────────────────────

    /**
     * {@code PUT} = 整体替换两个开关。写完**立刻**影响响铃行为: 平台在产生信号之前现查这一行
     * (§6.2)。
     */
    @PutMapping("/conversations/{accountId}/notification")
    public ConversationNotificationSetting setNotification(
            @PathVariable String accountId,
            @RequestBody(required = false) NotificationBody body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean muted = body != null && body.muted();
        boolean pinned = body != null && body.pinned();
        return conversations.setNotification(my(authorization), accountId, muted, pinned);
    }

    // ── §6.5 为 Agent 铸聊天账号 ─────────────────────────────────────────────

    /**
     * 见 {@link ClientProvisioningService}。调用方是 <b>Agent 平台</b>(管理密钥),
     * 不是 Agent 的驱动程序。
     */
    @PostMapping("/provision")
    public ResponseEntity<ProvisionedChatAccount> provision(
            @RequestBody(required = false) ProvisionBody body,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_API_KEY, required = false) String apiKey,
            @RequestHeader(value = ApiKeyPrincipalResolver.HEADER_ADMIN_KEY, required = false) String adminKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        if (body == null) {
            throw ClientApiException.badRequest("请求体不能为空", null);
        }
        // 身份解析在这一行, 而它**只**接管理密钥; 解出来的 principal 除了"你是平台自己"之外
        // 不带任何信息 —— 它不是某一个聊天账号, 所以它读不了任何人的会话(见 resolver 的说明)。
        ResolvedPrincipal admin = resolver.resolveAdmin(apiKey, adminKey, correlationId);
        ProvisionedChatAccount created = provisioning.provision(
                body.requestId(), body.displayName(), body.ownerAccountId(),
                body.relationshipType(), body.agentId());
        log.info("[客户端面] {} 触发了一次聊天账号铸号: {}", admin.principalId(), created.accountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private ClientPrincipal my(String authorization) {
        return resolver.resolve(authorization);
    }

    // ── 线格式 ────────────────────────────────────────────────────────────────

    /**
     * 发送请求体。
     *
     * <p>刻意**没有** {@code kind}: §6.3 第 6 项是"打字发送", 而消息种类
     * ({@code SHORT_ACK / PROACTIVE / SYSTEM …}) 是平台的内部词表 —— 开放它等于让任何客户端
     * 伪造一条"平台通告"({@code SYSTEM} 在界面上渲染成居中的系统提示)。对外开放面之所以能
     * 接受 {@code messageKind}, 是因为那边只有 agent 程序、且带白名单; 而这里是**覆盖面最广的
     * 那个面**(含真人浏览器), 白名单之外的那几个取值在这里没有一个是需要的。
     */
    public record SendBody(String content, String idempotencyKey) {}

    public record ReadBody(String lastMessageId) {}

    /** 两个布尔一起给: {@code PUT} 的语义是整体替换(见 {@code ConversationNotificationSetting})。 */
    public record NotificationBody(boolean muted, boolean pinned) {}

    /**
     * {@code ownerAccountId} / {@code relationshipType} / {@code agentId} 都是 Agent 平台
     * 那边的概念, 聊天平台只是把它们接过来、放进同一次编排里, 再原样交回去 —— 它们不影响
     * 本平台的任何一条判定。
     */
    public record ProvisionBody(String requestId, String displayName, String ownerAccountId,
                                String relationshipType, String agentId) {}
}
