package com.luxera.companion.conversation;

import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.chat.ApplicationCard;
import com.luxera.companion.contracts.chat.ApplicationInvitation;
import com.luxera.companion.contracts.chat.ApplicationLaunchRequest;
import com.luxera.companion.contracts.chat.ApplicationLaunchResponse;
import com.luxera.companion.contracts.chat.ApplicationSessionView;
import com.luxera.companion.contracts.spi.ApplicationCatalogPort;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LAP v2 §63–§66: <b>把应用带进某段对话</b> —— 聊天侧的全部应用知识都在这里, 一共三件事。
 *
 * <pre>
 *   context()  这段对话里能开什么、已经开着什么
 *   open()     在这段对话里开一个应用, 并落一条能点开的卡片消息
 *   share()    把这一场的加入链接作为一条消息发出去
 * </pre>
 *
 * <h2>这个类不知道任何一个具体应用</h2>
 * <p>整个文件里没有 {@code tictactoe}、没有 {@code game.make_move}、没有"井字棋"。
 * 那不是巧合, 而是 §63 那条验收断言的<em>实现</em>: 它靠的正是"这个类从 {@link
 * ApplicationCatalogPort} 拿到的东西全部是数据"—— 一旦它开始认某个 {@code applicationId},
 * 那句话就假了。{@code ChatApplicationPortTest} 盯着的就是这一点。
 *
 * <h2>为什么这条消息<b>不</b>通知数字人</h2>
 * <p>落库走的是 {@link ConversationService#addMessage} 而不是 {@code MessageCoreService.send}。
 * 后者会在事务提交后唤醒数字人平台 —— 那是对的, 因为一条用户消息值得它看一眼; 而这里落的是
 * <b>平台通告</b>("井字棋已开启"), 把它喂给 LLM 只会让它把一段系统文本当成用户说的话。
 * 数字人要知道"这一局开起来了", 走的是应用事件那条路(§60 的 {@code APPLICATION_*} 家族),
 * 那条路说的事情比这行字准确得多。
 *
 * <h2>卡片是一条消息, 不是一个新概念</h2>
 * <p>{@code messages} 表已经有 {@code message_kind} 和 {@code metadata} 两列, 它们就是为这种
 * "长得像消息、内容不是一句话"的东西准备的。另建一张 {@code application_card} 表会立刻带来
 * 一个没有答案的问题: 卡片和消息谁先出现?分页怎么合?已读状态算谁的?
 */
@Slf4j
@Service
public class ConversationApplicationService {

    /**
     * 一条"应用已经在这段对话里开起来了"的消息。
     *
     * <p>{@code metadata} 里装着 {@code applicationId} / {@code sessionId} / 名称 / 角色 ——
     * 客户端据此把这条消息渲染成一张卡片而不是一段文字。{@code content} 同时写了一句
     * 人能读的话: 认不出这个 {@code messageKind} 的旧客户端会把它当普通消息显示,
     * 而那正是它该做的降级 —— 比一条空白气泡好。
     */
    public static final String KIND_APPLICATION_CARD = "APPLICATION_CARD";

    /** 一条"点这里加入这一场"的消息。{@code metadata.joinUrl} 是它的全部内容。 */
    public static final String KIND_APPLICATION_INVITATION = "APPLICATION_INVITATION";

    /** 平台通告的发送者。不是 "user"(没人说过这句话), 也不是 "companion"(不是它说的)。 */
    private static final String SENDER_SYSTEM = "system";

    private final ConversationService conversations;
    private final CompanionDirectoryPort companionDirectory;
    private final ApplicationCatalogPort catalogue;

    public ConversationApplicationService(ConversationService conversations,
                                          CompanionDirectoryPort companionDirectory,
                                          ApplicationCatalogPort catalogue) {
        this.conversations = conversations;
        this.companionDirectory = companionDirectory;
        this.catalogue = catalogue;
    }

    // ─────────────────────────── 看 ───────────────────────────

    /**
     * 这段对话的应用上下文: 能开什么 + 已经开着什么。
     *
     * <p>两个列表一次给出, 而不是拆成两个端点: 界面要的是同一块面板, 拆开就变成两次往返,
     * 而两次往返之间那段空隙正好够用户在"能开的列表"里按下一个刚刚被下架的应用。
     *
     * <p><b>权限检查在这里, 而且只有一次。</b>{@code requireOwned} 确认调用方拥有这段对话
     * (它与这个数字人之间的那一场); 过了这一关之后, 应用平台那边还会用它自己的判据
     * 回答"这个身份在这一场应用会话里能不能做这件事" —— 两件事, 两个地方, 不重复。
     */
    @Transactional(readOnly = true)
    public ContextView context(String userId, String companionId, String conversationId) {
        requireConversation(userId, companionId, conversationId);
        InvocationContext context = identity(userId);
        return new ContextView(catalogue.cards(context),
                catalogue.sessionsOfConversation(context, conversationId));
    }

    // ─────────────────────────── 开 ───────────────────────────

    /**
     * 在这段对话里打开一个应用。
     *
     * <p>顺序不可换: <b>先开应用, 再落消息</b>。反过来的话, 一次被应用平台拒绝的开启会留下
     * 一条"井字棋已开启"的卡片消息 —— 一条说假话的历史记录, 而且它永远留在那里(消息不可改)。
     *
     * <p>每次调用都会新建一个应用会话, 这是 §130 原则 2: 用户在对话里点第二次"打开",
     * 要的是再开一局, 不是被塞回上一局。
     */
    @Transactional
    public OpenResult open(String userId, String companionId, String conversationId, OpenRequest request) {
        requireConversation(userId, companionId, conversationId);
        if (request == null || request.applicationId() == null || request.applicationId().isBlank()) {
            throw new IllegalArgumentException("必须给出 applicationId");
        }
        InvocationContext context = identity(userId);
        ApplicationLaunchResponse launched = catalogue.launch(context, new ApplicationLaunchRequest(
                request.applicationId(), conversationId, request.visibility(), request.joinPolicy(),
                request.minParticipants(), request.maxParticipants()));

        ApplicationCard card = catalogue.card(context, request.applicationId()).orElse(null);
        Message message = conversations.addMessage(conversationId, SENDER_SYSTEM,
                cardText(card, request.applicationId()), KIND_APPLICATION_CARD,
                cardMetadata(card, request.applicationId(), launched));

        log.info("[ConversationApplication] {} 在对话 {} 里开了 {} (会话 {})",
                userId, conversationId, request.applicationId(), launched.session().sessionId());
        return new OpenResult(launched, message.getId());
    }

    // ─────────────────────────── 分享 ───────────────────────────

    /**
     * 把进这一场的票作为一条消息发到对话里。
     *
     * <p>这是 §63 "邀请其他真人"那一步在聊天侧的形态: 用户不必离开对话去复制链接,
     * 链接就是一条消息 —— 而且它带着 {@code role} 与 {@code maxUses}, 因为它们是铸造时定下的,
     * 不是拿到链接的人能改的。
     *
     * <p>{@code token} 落进 {@code metadata} 的<em>明文</em>份里是刻意的, 也是它唯一该在的
     * 地方: 消息是给这段对话里的人看的, 而 {@code joinUrl} 里必须有明文 ——
     * R10 那条"token 明文不进库"说的是"不进<em>票</em>那一行"(库里只有 SHA-256), 而不是
     * "全宇宙不许有明文"; 一张连收件人都读不出来的票是没有用的票。
     */
    @Transactional
    public ShareResult share(String userId, String companionId, String conversationId,
                             String sessionId, String role, Integer maxUses) {
        requireConversation(userId, companionId, conversationId);
        InvocationContext context = identity(userId);
        ApplicationInvitation invitation = catalogue.invite(context, sessionId, role, maxUses);

        Message message = conversations.addMessage(conversationId, SENDER_SYSTEM,
                invitationText(context, invitation), KIND_APPLICATION_INVITATION,
                invitationMetadata(invitation));

        log.info("[ConversationApplication] {} 把会话 {} 的票 {} 分享到对话 {}",
                userId, sessionId, invitation.invitationId(), conversationId);
        return new ShareResult(invitation, message.getId());
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 调用方在这段对话里说话的权利。
     *
     * <p>两问, 缺一不可: 这个数字人是他的吗({@code companionDirectory}), 这段对话是他的吗
     * ({@code requireOwned})。只问后者是不够的 —— 一个 conversationId 猜对了的人,
     * 会看到别人和别人的数字人之间的对话。这与 {@code MessageCoreService.send} 的两问
     * 刻意同形: 两个入口问同一组问题, 才不会有一个入口少问一句。
     */
    private void requireConversation(String userId, String companionId, String conversationId) {
        companionDirectory.requireOwned(userId, companionId);
        Conversation conversation = conversations.requireOwned(userId, conversationId);
        if (!conversation.getCompanionId().equals(companionId)) {
            throw new IllegalArgumentException("会话与伴侣不匹配");
        }
    }

    /**
     * 聊天平台在这个端口上自报的身份。
     *
     * <p>类型是<b>写下来的</b> {@code HUMAN}, 不是省略后的默认值 ——
     * {@code InternalPrincipalResolver} 拒绝没有类型的上下文, 而那条拒绝是对的:
     * "默认真人"会让一个 Agent 悄悄变成人。这里之所以敢写死 HUMAN, 是因为这段代码
     * 只由一个已认证的真人请求驱动({@code CurrentUser.requireUserId()} 在控制器里)。
     */
    private InvocationContext identity(String userId) {
        return InvocationContext.human(userId, UUID.randomUUID().toString());
    }

    private String cardText(ApplicationCard card, String applicationId) {
        String name = card == null || card.name() == null ? applicationId : card.name();
        return "「" + name + "」已在这段对话里开启";
    }

    private Map<String, Object> cardMetadata(ApplicationCard card, String applicationId,
                                             ApplicationLaunchResponse launched) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", applicationId);
        data.put("sessionId", launched.session().sessionId());
        data.put("version", launched.session().version());
        data.put("name", card == null ? applicationId : card.name());
        data.put("description", card == null ? null : card.description());
        data.put("role", launched.participant().role());
        data.put("status", launched.session().status());
        return data;
    }

    private Map<String, Object> invitationMetadata(ApplicationInvitation invitation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invitationId", invitation.invitationId());
        data.put("sessionId", invitation.sessionId());
        data.put("joinUrl", invitation.joinUrl());
        data.put("role", invitation.role());
        data.put("maxUses", invitation.maxUses());
        return data;
    }

    /**
     * 邀请消息里那句人能读的话。
     *
     * <p>名字要问应用平台要(票里只有 sessionId), 而那是一次<em>额外的</em>往返 ——
     * 所以它失败时不该让整条分享失败: 票已经铸好了, 消息已经该落了。
     * 拿不到名字就用一句不带名字的话, 而不是把一次成功的分享回滚成 500。
     */
    private String invitationText(InvocationContext context, ApplicationInvitation invitation) {
        String name = null;
        try {
            ApplicationSessionView session = catalogue.session(context, invitation.sessionId());
            name = catalogue.card(context, session.applicationId()).map(ApplicationCard::name).orElse(null);
        } catch (RuntimeException e) {
            log.debug("[ConversationApplication] 邀请消息拿不到应用名(不影响分享): {}", e.getMessage());
        }
        String label = name == null ? "这一局" : "「" + name + "」";
        return "点这里加入" + label + ": " + invitation.joinUrl();
    }

    // ─────────────────────────── 视图 ───────────────────────────

    /** 这段对话的应用上下文。 */
    public record ContextView(List<ApplicationCard> openable, List<ApplicationSessionView> open) {}

    /**
     * 开应用的入参。<b>没有 {@code conversationId}</b> —— 它在路径里, 由控制器填;
     * 同一个概念两个来源的问题, 与 v1 的 {@code POST /sessions} 是同一个坑。
     */
    public record OpenRequest(String applicationId, String visibility, String joinPolicy,
                              Integer minParticipants, Integer maxParticipants) {}

    /**
     * 开出来了。{@code messageId} 是那条卡片消息 —— 客户端据此把刚落的这一条直接插进消息流,
     * 而不必重载整段对话(§21.3 的乐观上屏在服务端消息上也成立)。
     */
    public record OpenResult(ApplicationLaunchResponse launch, String messageId) {}

    /** 分享出去了。{@code invitation.token} 在这一条响应里是明文, 此后永远为空。 */
    public record ShareResult(ApplicationInvitation invitation, String messageId) {}
}
