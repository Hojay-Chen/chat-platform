package com.luxera.companion.application.port;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionInvitationRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.invitation.InvitationService;
import com.luxera.companion.application.lifecycle.ApplicationCatalogue;
import com.luxera.companion.application.lifecycle.Availability;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.chat.ApplicationCard;
import com.luxera.companion.contracts.chat.ApplicationCatalogException;
import com.luxera.companion.contracts.chat.ApplicationInvitation;
import com.luxera.companion.contracts.chat.ApplicationLaunchRequest;
import com.luxera.companion.contracts.chat.ApplicationLaunchResponse;
import com.luxera.companion.contracts.chat.ApplicationSessionView;
import com.luxera.companion.contracts.chat.ParticipantView;
import com.luxera.companion.contracts.spi.ApplicationCatalogPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * LAP v2 §64: {@link ApplicationCatalogPort} 的宿主侧实现 —— <b>聊天平台看见的那个应用生态</b>。
 *
 * <h2>这个类只做三件事, 而且一件判定都不做</h2>
 * <ol>
 *   <li><b>翻译身份</b>: {@link InvocationContext} → {@link ResolvedPrincipal}(经
 *       {@code InternalPrincipalResolver}, 它不接受空的 principalType);</li>
 *   <li><b>翻译形状</b>: 实体 → {@code contracts.chat} 的视图;</li>
 *   <li><b>翻译失败</b>: {@link SessionException} → {@link ApplicationCatalogException}。</li>
 * </ol>
 *
 * <p><b>没有第四件事。</b> 这里没有一行"如果状态是 X 就……": 可用性来自
 * {@code ApplicationCatalogue.availabilityOf}(§4.1 那张表), 参与资格来自 {@code ParticipantService},
 * 铸票的权柄来自 {@code InvitationService}。适配器一旦开始自己判, 平台里就会有两处
 * 回答同一个问题, 而它们不会同时被改。
 *
 * <h2>类名为什么不是 {@code ApplicationCatalogueService}</h2>
 * <p>因为本模块里已经有一个 {@code ApplicationCatalogue}(发现链的两份真相的交点), 而它的
 * 名字恰好也是"目录"。两个都叫目录的类, 在下一个读代码的人眼里必然会有一个被当成另一个 ——
 * 所以这一层按仓库里既有的约定叫 {@code *Adapter}({@code ChatWorldAdapter}、
 * {@code CompanionDirectoryAdapter} 同形), 它说的是"我是一个端口的适配器", 而不是
 * "我又是一份目录"。
 */
@Slf4j
@Component
public class ApplicationCatalogAdapter implements ApplicationCatalogPort {

    private final ApplicationCatalogue catalogue;
    private final ManifestRegistry manifests;
    private final ApplicationSessionService sessions;
    private final ParticipantService participants;
    private final InvitationService invitations;
    private final PrincipalResolvers principals;

    public ApplicationCatalogAdapter(ApplicationCatalogue catalogue,
                                     ManifestRegistry manifests,
                                     ApplicationSessionService sessions,
                                     ParticipantService participants,
                                     InvitationService invitations,
                                     PrincipalResolvers principals) {
        this.catalogue = catalogue;
        this.manifests = manifests;
        this.sessions = sessions;
        this.participants = participants;
        this.invitations = invitations;
        this.principals = principals;
    }

    // ─────────────────────────── 看 ───────────────────────────

    /**
     * 能开新会话的应用, 做成卡片。
     *
     * <p>过滤的是 §4.1 的第二列 —— 卡片上有一个"打开"按钮, 所以给出去的每一张都得按得动。
     * 这里<em>不复用</em> {@code ApplicationCatalogue.discoverable()}: 那个方法筛的是第一列
     * (市场可见), 与"能不能开"在 SUSPENDED/DEPRECATED 上是相反的。两者今天很接近,
     * 所以更不能用错的那一个 —— 它错得很安静。
     */
    @Override
    public List<ApplicationCard> cards(InvocationContext context) {
        require(context);
        return manifests.applications().stream()
                .map(ApplicationManifest::applicationId)
                .distinct()
                .map(this::cardOf)
                .flatMap(Optional::stream)
                .filter(ApplicationCard::allowsNewSession)
                .sorted(Comparator.comparing(ApplicationCard::name,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 一个应用的卡片。应用不存在 → 空。
     *
     * <p>注意这里<b>不</b>按"能不能开"过滤 —— 单张卡片问的是"这是个什么东西",
     * 而 {@code allowsNewSession} 这个字段本身就回答了"此刻能不能开它"。
     * 把不可开的应用在这里也变成空, 会让聊天界面在应用被下架时把整条历史消息渲染成
     * "应用不存在", 而它昨天还好好地在那儿。
     */
    @Override
    public Optional<ApplicationCard> card(InvocationContext context, String applicationId) {
        require(context);
        return cardOf(applicationId);
    }

    @Override
    public List<ApplicationSessionView> sessionsOfConversation(InvocationContext context, String conversationId) {
        ResolvedPrincipal me = require(context);
        return sessions.ofConversation(conversationId).stream()
                .map(row -> view(row, me))
                .toList();
    }

    /**
     * 看一个会话。
     *
     * <p>调用方不是参与者就抛 —— <b>不是返回空, 也不是返回一个 participants 为 0 的视图</b>。
     * §130 原则 3 说会话是协作的边界, 边界的意思就是"外面的人看不见里面"; 把"看不见"实现成
     * "看得见一个空壳", 是把边界做成了毛玻璃。
     */
    @Override
    public ApplicationSessionView session(InvocationContext context, String sessionId) {
        ResolvedPrincipal me = require(context);
        ApplicationSessionRecord row = sessions.require(sessionId);
        if (participants.find(sessionId, me.type(), me.principalId()).isEmpty()) {
            throw refuse("NOT_A_PARTICIPANT",
                    me.typeName() + " " + me.principalId() + " 不在会话 " + sessionId + " 里");
        }
        return view(row, me);
    }

    // ─────────────────────────── 开 ───────────────────────────

    /**
     * 打开一个应用, 会话行上记住它属于哪段对话。
     *
     * <p>这里连 {@code conversationId} 都<b>不校验</b> —— 应用平台不认识对话, 也没有权利
     * 判断"这个 conversationId 是真的"。它是聊天平台报上来的事实, 存下来就是了。
     * 要校验的话, 应用平台就得在编译期看得见 chat-platform, 而那正是这一整个端口
     * 存在的原因。
     */
    @Override
    public ApplicationLaunchResponse launch(InvocationContext context, ApplicationLaunchRequest request) {
        ResolvedPrincipal me = require(context);
        if (request == null || request.applicationId() == null || request.applicationId().isBlank()) {
            throw new ApplicationCatalogException("INVALID_ARGUMENT", "打开应用必须给出 applicationId",
                    ActionStatus.INVALID_ARGUMENT);
        }
        try {
            ApplicationSessionRecord row = sessions.launch(request.applicationId(), me,
                    request.conversationId(), request.minParticipants(), request.maxParticipants());
            row = sessions.applyLaunchOptions(row.getId(), request.visibility(), request.joinPolicy());
            SessionParticipantRecord mine = participants
                    .find(row.getId(), me.type(), me.principalId())
                    .orElseThrow(() -> refuse("NOT_A_PARTICIPANT", "刚开的会话里没有自己"));
            return new ApplicationLaunchResponse(view(row, me), participantView(mine));
        } catch (SessionException e) {
            throw refuse(e);
        }
    }

    // ─────────────────────────── 分享 ───────────────────────────

    /**
     * 铸一张票。
     *
     * <p>明文 token 在 {@code onToken} 回调里被接住 —— 那是 {@code InvitationService} 给出的
     * <em>唯一一次</em>暴露机会(库里只有 SHA-256)。这里把它拼成 {@code joinUrl} 之后立即
     * 装进返回值, 不做任何缓存、不写日志。R10 那条"token 明文不进库"的验收盯着的是数据库,
     * 而这一行盯着的是<em>日志</em>—— 一个把 token 打进日志的适配器, 泄漏程度不亚于存明文。
     */
    @Override
    public ApplicationInvitation invite(InvocationContext context, String sessionId,
                                        String role, Integer maxUses) {
        ResolvedPrincipal me = require(context);
        try {
            String[] holder = new String[1];
            SessionInvitationRecord row = invitations.mint(sessionId, me, role, null, maxUses,
                    null, null, null, token -> holder[0] = token);
            String token = holder[0];
            log.info("[ApplicationCatalog] {} 为会话 {} 铸票 {} (role={}, maxUses={})",
                    me.principalId(), sessionId, row.getId(), row.getRole(), maxUses);
            return new ApplicationInvitation(row.getId(), row.getSessionId(), token,
                    token == null ? null : "/join/" + token, row.getRole(), row.getStatus(),
                    row.getMaxUses(), row.getUsedCount(),
                    text(row.getExpiresAt()));
        } catch (SessionException e) {
            throw refuse(e);
        }
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 收下调用方自报的身份, 并 <b>确认它说得出口</b>。
     *
     * <p>{@code resolveInternal} 会拒绝没有 {@code principalType} 的上下文 —— 理由见
     * {@code InternalPrincipalResolver}: "默认真人"是这套模型里最危险的一行。
     */
    private ResolvedPrincipal require(InvocationContext context) {
        if (context == null) {
            throw new ApplicationCatalogException("INVALID_ARGUMENT",
                    "进程内调用必须带 InvocationContext —— 没有身份的动作在这个平台里不存在",
                    ActionStatus.INVALID_ARGUMENT);
        }
        return principals.resolveInternal(context);
    }

    private Optional<ApplicationCard> cardOf(String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            return Optional.empty();
        }
        Optional<ApplicationManifest> published = manifests.published(applicationId);
        if (published.isEmpty()) {
            return Optional.empty();
        }
        ApplicationManifest manifest = published.get();
        Availability availability = catalogue.availabilityOf(applicationId);
        ApplicationManifest.Identity identity = manifest.identity();
        return Optional.of(new ApplicationCard(
                applicationId,
                manifest.version(),
                identity == null ? applicationId : identity.name(),
                identity == null ? null : identity.description(),
                identity == null ? null : identity.category(),
                manifest.capabilities().stream().map(ApplicationManifest.CapabilityDecl::id).toList(),
                availability.inMarket(),
                availability.allowsNewSession()));
    }

    private ApplicationSessionView view(ApplicationSessionRecord row, ResolvedPrincipal me) {
        List<SessionParticipantRecord> active = participants.activeParticipantsOf(row.getId());
        SessionParticipantRecord mine = participants
                .find(row.getId(), me.type(), me.principalId())
                .orElse(null);
        return new ApplicationSessionView(
                row.getId(),
                row.getApplicationId(),
                sessions.versionLabel(row.getVersionId()),
                row.getStatus(),
                row.getVisibility(),
                row.getJoinPolicy(),
                row.getMinParticipants(),
                row.getMaxParticipants(),
                active.size(),
                row.getConversationId(),
                mine == null ? List.of() : participants.capabilitiesOf(mine),
                text(row.getCreatedAt()),
                text(row.getStartedAt()),
                text(row.getEndedAt()),
                text(row.getLastActiveAt()));
    }

    private ParticipantView participantView(SessionParticipantRecord row) {
        return new ParticipantView(row.getId(),
                com.luxera.companion.contracts.application.PrincipalType.valueOf(row.getPrincipalType()),
                row.getPrincipalId(), row.getRole(), row.getStatus(), row.owner());
    }

    /** {@code SessionException} → 契约异常。<b>code 与 status 原样搬过来, 一个都不翻译。</b> */
    private ApplicationCatalogException refuse(SessionException e) {
        return new ApplicationCatalogException(e.code(), e.getMessage(), e.status());
    }

    private ApplicationCatalogException refuse(String code, String message) {
        throw new ApplicationCatalogException(code, message,
                "NOT_A_PARTICIPANT".equals(code) ? ActionStatus.DENIED : ActionStatus.INVALID_ARGUMENT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
