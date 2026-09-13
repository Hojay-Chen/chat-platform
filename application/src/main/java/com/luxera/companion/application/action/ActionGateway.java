package com.luxera.companion.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.audit.ActionAuditRecorder;
import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.event.LapEventPublisher;
import com.luxera.companion.application.invitation.InvitationService;
import com.luxera.companion.application.lifecycle.ApplicationCatalogue;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.permission.PermissionDecision;
import com.luxera.companion.application.permission.PermissionEvaluator;
import com.luxera.companion.application.principal.PrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.resource.ResourceStore;
import com.luxera.companion.application.resource.StateConflictException;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ApplicationSessionStateMachine;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionError;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <b>真人、Agent、MCP 客户端共用的唯一入口。</b>
 *
 * <p>这个类里没有"如果调用方是 Agent 就……"的任何一个分支。差异全部落在
 * {@link ResolvedPrincipal} 上, 而它只影响两件事: 权限判定用谁的参与者行与授权、
 * 幂等键的作用域是谁。除此之外, 一条路径。
 *
 * <p>一次执行经过八道, 顺序是刻意的:
 *
 * <pre>
 *   1. 动作解析     (actionId, target) → 哪个应用的哪个动作; 多个候选且 target 消歧不出唯一 → 拒绝
 *   2. handler 存在 启动时已校验过, 这里是纵深防御
 *   3. 会话解析     五档, 见 {@link #resolveSession} —— <b>永不返回 null</b>
 *   4. 归属校验     会话必须属于这个应用, 且通过三条不变量
 *   5. 权限         Participant × Session permission × Capability × Action × Risk
 *   6. 状态         这个会话现在接不接受这个级别的动作
 *   7. 幂等         WRITE/EXECUTE 才要 key; READ 从不记录
 *   8. 执行         handler + 资源写入 + 终态回填, 同一个事务
 * </pre>
 *
 * <p><b>第 3 步从 v1 的"三选一、可能为 null"变成"五档、必有结果"。</b> 这是删掉 installation
 * 之后整条链上最需要想清楚的一处: v1 里"没有会话"是合法的({@code resolveSessionId} 返回
 * {@code null}, 权限层用 installation 兜住), v2 里没有任何东西可以兜底了 —— 参与者行挂在
 * 会话上, 没有会话就没有参与者, 没有参与者连"你是谁"都答不出来。于是改成: <em>要么解析出一个
 * 已有会话, 要么造一个</em>。第 4/5 档就是为了让这件事对提醒收件箱那种<em>URI 里根本没有
 * sessionId</em> 的资源也成立 —— 少了它们, 每一次提醒调用都会新建一个会话, 而那是静默的
 * 资源泄漏, 不是报错。
 *
 * <p><b>第 8 步"同一个事务"是整套崩溃恢复推理的地基。</b>业务写入与 {@code action_invocation}
 * 的终态一起提交, 于是"还停在 IN_PROGRESS"就等价于"业务没发生" —— 重试安全, 回收器也能有把握
 * 地下结论。拆成两个事务的话, 这句话立刻不成立。
 *
 * <p>第 5、6 步都在第 7 步之前, 理由是同一个: 走不到执行的请求不该占用一个幂等键。先占键再判
 * 权限的话, 一个被拒的请求会留下一条终态记录, 之后真正有权的调用带着同一个 key 来会被"重放"成
 * 拒绝 —— 一个正确的调用拿到一个属于别人的失败。
 *
 * <p><b>第 5 步为什么在第 6 步前面。</b> 两者拒绝的码不同({@code NOT_A_PARTICIPANT} vs
 * {@code SESSION_NOT_ACTIVE})、要调用方做的事也不同(去找人邀请自己 vs 等一会儿)。一个根本
 * 不在场的人, 该听到的是前者 —— 告诉他"会话现在不接受写"等于请他去重试一件他永远做不成的事。
 */
@Slf4j
@Service
public class ActionGateway implements ApplicationRuntimePort {

    private final ManifestRegistry manifests;
    private final ApplicationCatalogue catalogue;
    private final ActionHandlerRegistry handlers;
    private final PendingActionRegistry pendingActions;
    private final ActionResolver resolver;
    private final ResourceStore resources;
    private final PermissionEvaluator permissions;
    private final IdempotencyService idempotency;
    private final ApplicationSessionService sessions;
    private final ApplicationSessionStateMachine sessionStates;
    private final ParticipantService participants;
    private final LapEventPublisher events;
    private final ActionAuditRecorder audit;
    private final PrincipalResolvers principals;
    private final InvitationService invitations;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public ActionGateway(ManifestRegistry manifests,
                         ApplicationCatalogue catalogue,
                         ActionHandlerRegistry handlers,
                         PendingActionRegistry pendingActions,
                         ActionResolver resolver,
                         ResourceStore resources,
                         PermissionEvaluator permissions,
                         IdempotencyService idempotency,
                         ApplicationSessionService sessions,
                         ApplicationSessionStateMachine sessionStates,
                         ParticipantService participants,
                         LapEventPublisher events,
                         ActionAuditRecorder audit,
                         PrincipalResolvers principals,
                         InvitationService invitations,
                         ObjectMapper objectMapper,
                         PlatformTransactionManager transactionManager) {
        this.manifests = manifests;
        this.catalogue = catalogue;
        this.handlers = handlers;
        this.pendingActions = pendingActions;
        this.resolver = resolver;
        this.resources = resources;
        this.permissions = permissions;
        this.idempotency = idempotency;
        this.sessions = sessions;
        this.sessionStates = sessionStates;
        this.participants = participants;
        this.events = events;
        this.audit = audit;
        this.principals = principals;
        this.invitations = invitations;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 一次执行的结果: 响应, 以及它是不是幂等重放(调用方据此加 {@code Idempotent-Replay} 头)。 */
    public record ActionExecution(ActionResponse response, boolean replayed) {

        public static ActionExecution fresh(ActionResponse response) {
            return new ActionExecution(response, false);
        }
    }

    // ═══════════════════════════ 发现 ═══════════════════════════

    @Override
    public List<CapabilityView> capabilities() {
        return manifests.capabilityCatalogue().values().stream()
                .map(c -> new CapabilityView(c.id(), c.title(), c.description(), c.category()))
                .toList();
    }

    @Override
    public List<ApplicationView> applicationsFor(String capabilityId) {
        return catalogue.discoverableFor(capabilityId).stream().map(ActionGateway::toView).toList();
    }

    /** 全部在架应用 —— 真人 UI 的"应用商店"页用。被挂起的应用在这里就看不见了。 */
    public List<ApplicationView> applications() {
        return catalogue.discoverable().stream().map(ActionGateway::toView).toList();
    }

    @Override
    public List<ActionSpec> actionsOf(String applicationId) {
        return catalogue.discoverable(applicationId)
                .map(m -> m.actions().stream().map(a -> toSpec(m, a)).toList())
                .orElse(List.of());
    }

    @Override
    public Optional<ResourceView> read(String resourceUri) {
        return resources.find(resourceUri).map(v -> withHint(v, resourceUri));
    }

    @Override
    public List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx) {
        if (!StringUtils.hasText(resourceUri) || ctx == null) {
            return List.of();
        }
        ApplicationManifest manifest = resolver.ownerOf(resourceUri).orElse(null);
        if (manifest == null) {
            return List.of();
        }
        ResolvedPrincipal principal;
        try {
            principal = principals.resolveInternal(ctx);
        } catch (PrincipalResolver.PrincipalException e) {
            return List.of();
        }
        // 只查不建: 这是一条读路径, "这个人还没开过会话"不该在这里顺手落一行。
        String sessionId = explicitSessionId(resourceUri, manifest, principal);
        if (sessionId == null) {
            sessionId = sessions.findLive(manifest.applicationId(), principal)
                    .map(ApplicationSessionRecord::getId).orElse(null);
        }
        // 不在场的人不该"想做点什么" —— 空列表, 不是错误。
        if (sessionId == null
                || participants.find(sessionId, principal.type(), principal.principalId())
                        .filter(SessionParticipantRecord::active).isEmpty()) {
            return List.of();
        }
        PendingActionProvider provider = pendingActions
                .find(manifest.applicationId(), manifest.version()).orElse(null);
        if (provider == null) {
            return List.of();
        }
        ResourceView resource = read(resourceUri).orElse(null);
        List<String> ids = provider.pendingActionIds(resource, ctx);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ActionSpec> out = new ArrayList<>();
        for (String actionId : ids) {
            manifest.action(actionId).ifPresent(action -> out.add(toSpec(manifest, action)));
        }
        return out;
    }

    // ═══════════════════════════ 执行 ═══════════════════════════

    /**
     * 进程内的"保证有一个会话" —— 幂等, 走的是和 HTTP 开启会话<em>同一段</em>代码。
     *
     * <p>刻意不做成"建不出来就静默跳过": 建不出来(应用没发布版本 / 身份不合法)应当让调用方知道,
     * 它才好决定是降级还是报错。静默跳过会让"提醒功能不工作"变成一个需要翻日志才能定位的现象。
     *
     * <p>返回 id 而不是 void, 因为"在哪个会话里"就是调用方接下来要的东西 —— v1 那个 void 版本
     * 逼着每个调用方自己再去查一遍"我刚才到底装到哪儿了", 而它没有那个信息。
     */
    @Override
    public String ensureSession(String applicationId, InvocationContext ctx) {
        ResolvedPrincipal principal = principals.resolveInternal(ctx);
        return sessions.ensureSession(applicationId, principal).getId();
    }

    // ─────────────────────────── 参与 (LAP v2 R13) ───────────────────────────

    /** @see ApplicationRuntimePort#sessionsOf */
    @Override
    public List<SessionRef> sessionsOf(String applicationId, InvocationContext ctx) {
        ResolvedPrincipal principal;
        try {
            principal = principals.resolveInternal(ctx);
        } catch (PrincipalResolver.PrincipalException e) {
            // 身份不合法时"看得见的会话"这个问题没有答案 —— 返回空表而不是抛, 与
            // pendingActions 对未认证调用的处理一致: 它是一条读路径, 而"什么都没有"
            // 正是"我不知道你是谁"该得到的答复。写路径(下面两个)才抛。
            log.debug("[ActionGateway] sessionsOf 身份不合法: {}", e.getMessage());
            return List.of();
        }
        return sessions.visibleSessions(applicationId, principal);
    }

    /**
     * @see ApplicationRuntimePort#joinByInvitation
     *
     * <p>这里只有一行, 而且是刻意的: 认票、判死因、加入、记账四件事全在
     * {@code InvitationService.consume} 里, 与真人点 {@code /join/{token}} 走的是同一个方法。
     * 在网关这一层多写一句"如果是数字人就……", 就等于把"Agent 是另一种用户"变回了一句口号。
     */
    @Override
    public String joinByInvitation(String token, InvocationContext ctx) {
        ResolvedPrincipal principal = principals.resolveInternal(ctx);
        return invitations.consume(token, principal).getSessionId();
    }

    /** @see ApplicationRuntimePort#joinSession */
    @Override
    public void joinSession(String sessionId, InvocationContext ctx) {
        ResolvedPrincipal principal = principals.resolveInternal(ctx);
        // role 传 null: 这是"我自己要进来", 不是"主人请我当管理员"。能拿到什么角色由
        // normalizeRole 按会话行上的 owner 判定 —— 陌生人自报 OWNER 会被降级成 MEMBER。
        participants.join(sessionId, principal, null, false);
    }

    /** @see ApplicationRuntimePort#leaveSession */
    @Override
    public void leaveSession(String sessionId, InvocationContext ctx) {
        ResolvedPrincipal principal = principals.resolveInternal(ctx);
        participants.leave(sessionId, principal);
    }

    /** {@link ApplicationRuntimePort} 的进程内形态: 身份来自 {@link InvocationContext}。 */
    @Override
    public ActionResponse execute(ActionRequest request, InvocationContext ctx) {
        ResolvedPrincipal principal;
        try {
            principal = principals.resolveInternal(ctx);
        } catch (PrincipalResolver.PrincipalException e) {
            return ActionResponse.failure(ActionStatus.DENIED, e.code(), e.getMessage());
        }
        if (request == null || !StringUtils.hasText(request.action())) {
            return ActionResponse.failure(ActionStatus.INVALID_ARGUMENT,
                    "INVALID_ARGUMENT", "缺少 action");
        }
        return execute(request, principal, deriveIdempotencyKey(request, principal)).response();
    }

    /**
     * 传输层(REST / MCP)用的入口: 幂等键来自请求头, 不是派生出来的。
     *
     * <p>两条路径必须分开, 不能合成"有头就用头、没有就派生": 一个没带
     * {@code Idempotency-Key} 的 HTTP 写请求是<em>客户端 bug</em>, 应当 400 报错;
     * 而进程内调用确实没有头可带, 派生一个确定性键是正确行为。合并的话, 前者会被静默
     * 兜底成一个派生键, 于是"忘了带幂等键"永远暴露不出来。
     */
    public ActionExecution execute(ActionRequest request, ResolvedPrincipal principal, String idempotencyKey) {
        if (request == null || !StringUtils.hasText(request.action())) {
            return ActionExecution.fresh(reject(null, principal, null, ActionStatus.INVALID_ARGUMENT,
                    "INVALID_ARGUMENT", "缺少 action"));
        }
        if (!StringUtils.hasText(request.target())) {
            return ActionExecution.fresh(reject(request.action(), principal, null,
                    ActionStatus.INVALID_ARGUMENT, "TARGET_REQUIRED", "缺少 target"));
        }

        ActionResolution resolution;
        try {
            resolution = resolver.resolve(request.action(), request.target()).orElse(null);
        } catch (AmbiguousActionException e) {
            return ActionExecution.fresh(reject(request.action(), principal, request.target(),
                    ActionStatus.INVALID_ARGUMENT, "AMBIGUOUS_ACTION",
                    "动作 " + e.actionId() + " 被多个应用声明 " + e.candidateApplicationIds()
                            + ", 请给出能消歧的 target"));
        }
        if (resolution == null) {
            return ActionExecution.fresh(reject(request.action(), principal, request.target(),
                    ActionStatus.NOT_FOUND, "ACTION_NOT_FOUND",
                    "没有已发布的应用声明动作 " + request.action()));
        }

        ActionHandler handler = handlers.find(resolution.handlerKey()).orElse(null);
        if (handler == null) {
            // 启动时 ManifestRegistrar 已经校验过一遍; 走到这里说明注册表被中途动过。
            return ActionExecution.fresh(reject(request.action(), principal, request.target(),
                    ActionStatus.FAILED, "ACTION_HANDLER_MISSING",
                    "动作没有处理器: " + resolution.handlerKey()));
        }

        ApplicationSessionRecord session;
        try {
            session = resolveSession(request.target(), resolution.manifest(), principal);
        } catch (SessionException e) {
            return ActionExecution.fresh(reject(request.action(), principal, request.target(),
                    e.status(), e.code(), e.getMessage()));
        }
        String sessionId = session.getId();

        PermissionDecision decision = permissions.evaluate(
                resolution.manifest(), resolution.spec(), sessionId,
                principal.type(), principal.principalId());
        ActionAuditRecorder.AuditEntry entry = auditEntry(request, resolution, principal)
                .withPermission(decision.auditLabel());
        if (!decision.allowed()) {
            audit.record(entry.withExecution(decision.confirmationRequired()
                            ? ActionStatus.REQUIRE_CONFIRMATION.name() : ActionStatus.DENIED.name(),
                    decision.message()));
            if (decision.confirmationRequired()) {
                return ActionExecution.fresh(ActionResponse.failure(
                        ActionStatus.REQUIRE_CONFIRMATION, decision.code(), decision.message()));
            }
            return ActionExecution.fresh(ActionResponse.failure(
                    ActionStatus.DENIED, decision.code(), decision.message()));
        }

        try {
            sessionStates.requireAllows(session, resolution.spec().permission());
        } catch (SessionException e) {
            audit.record(entry.withExecution(e.status().name(), e.getMessage()));
            return ActionExecution.fresh(ActionResponse.failure(e.status(), e.code(), e.getMessage()));
        }

        boolean needsKey = resolution.spec().requiresIdempotencyKey();
        if (needsKey && !StringUtils.hasText(idempotencyKey)) {
            audit.record(entry.withExecution(ActionStatus.IDEMPOTENCY_KEY_REQUIRED.name(),
                    "WRITE/EXECUTE 动作必须带 Idempotency-Key"));
            return ActionExecution.fresh(ActionResponse.failure(ActionStatus.IDEMPOTENCY_KEY_REQUIRED,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "动作 " + resolution.actionId() + " 是 "
                            + resolution.spec().permission() + " 级别, 必须带 Idempotency-Key"));
        }

        IdempotencyService.Claim claim = null;
        if (needsKey) {
            String hash = IdempotencyService.requestHash(objectMapper, request);
            claim = idempotency.claim(idempotencyKey, principal, resolution, sessionId, hash);
            if (claim.replay() != null) {
                return new ActionExecution(claim.replay(), true);
            }
            if (claim.conflict() != null) {
                audit.record(entry.withExecution(claim.conflict().status().name(),
                        claim.conflict().error() == null ? null : claim.conflict().error().message()));
                return ActionExecution.fresh(claim.conflict());
            }
            entry = entry.withInvocation(claim.record().getId());
        }

        ActionResponse response = runInTransaction(request, resolution, principal, sessionId, handler, claim);
        audit.record(entry.withExecution(response.status().name(),
                response.error() == null ? null : response.error().message()));
        if (response.isSuccess()) {
            try {
                sessions.touch(sessionId);
            } catch (Exception e) {
                log.debug("[ActionGateway] 会话活跃时间更新失败 {}: {}", sessionId, e.getMessage());
            }
        }
        return ActionExecution.fresh(response);
    }

    /**
     * 业务执行 + 资源写入 + 终态回填, <b>一个事务</b>。
     *
     * <p>事件投递注册在 {@code afterCommit} 上(见 {@link LapEventPublisher}), 所以"事务回滚了
     * 事件却已送达"这种最难查的 bug 在结构上不可能出现。
     *
     * <p>冲突与异常都<em>不</em>抛出到这个方法之外: 调用方要的是一个响应, 不是一个异常。
     * 但记录方式不同 —— 冲突/异常会让事务回滚, 所以终态只能用<em>独立事务</em>写。
     */
    private ActionResponse runInTransaction(ActionRequest request,
                                            ActionResolution resolution,
                                            ResolvedPrincipal principal,
                                            String sessionId,
                                            ActionHandler handler,
                                            IdempotencyService.Claim claim) {
        String invocationId = claim == null ? null : claim.record().getId();
        try {
            return transaction.execute(status -> {
                ActionHandlerContext ctx = new ActionHandlerContext(
                        resolution.applicationId(),
                        resolution.version(),
                        resolution.manifest(),
                        resolution.spec(),
                        request,
                        principal.toInvocationContext(),
                        resources.scoped(resolution.applicationId(), sessionId),
                        objectMapper);
                ActionOutcome outcome = handler.execute(ctx);
                ActionResponse response = toResponse(outcome, ctx, request.target(), resolution);
                if (invocationId != null) {
                    idempotency.completeInCurrentTransaction(invocationId, response);
                }
                events.publishAfterCommit(resolution.manifest(), ctx.emittedEvents());
                return response;
            });
        } catch (StateConflictException e) {
            if (invocationId != null) {
                idempotency.markFailedInNewTransaction(invocationId, "STATE_CONFLICT", e.getMessage());
            }
            return new ActionResponse(ActionStatus.STATE_CONFLICT, null,
                    conflictView(e), List.of(),
                    ActionError.of("STATE_CONFLICT",
                            "资源 " + e.uri() + " 已被改动(当前版本 " + e.currentVersion()
                                    + "), 请重读后重试"));
        } catch (Exception e) {
            log.warn("[ActionGateway] 动作执行异常 {} target={}: {}",
                    resolution.actionId(), request.target(), e.getMessage(), e);
            if (invocationId != null) {
                idempotency.markFailedInNewTransaction(invocationId, "ACTION_FAILED", e.getMessage());
            }
            return ActionResponse.failure(ActionStatus.FAILED, "ACTION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ═══════════════════════════ 内部 ═══════════════════════════

    /**
     * 会话解析 —— <b>五档, 顺序即优先级, 且永不返回 null。</b>
     *
     * <pre>
     *   1. 调用方显式给的 sessionId        (DH 从事件载荷里拿到的就是它)
     *   2. 目标资源行上的 session_id        (除了"创建"之外的所有动作都走这条)
     *   3. 目标 URI 里 {sessionId} 那一段   (创建类动作唯一的来源)
     *   4. ★ 该 principal 在这个应用下最近的 ACTIVE 会话 ★
     *   5. 都没有 → 现开一个(调用方记为 OWNER)
     * </pre>
     *
     * <p><b>前 3 档与 v1 一字不差; 第 4、5 档是新的, 也是删掉 installation 之后唯一会"静默失效"
     * 的地方。</b> {@code reminder://owner/{userId}} 的模板里没有 {@code {sessionId}} 段
     * (从 R5 起 {@code resource.session_id} 一直是 NULL), 前 3 档全都匹配不上。少了第 4 档,
     * 数字人的每一次提醒调用都会掉到第 5 档 —— {@code application_session} 会被闲聊级的调用
     * 灌满, 而且每一句提醒都落在一个谁也不认识的会话里; 少了第 5 档, "打开应用即用"就不成立,
     * 第一次用提醒会得到一个"会话不存在"。
     *
     * <p><b>为什么第 4 档要卡 {@code ACTIVE}。</b> 因为第 4 档的产物直接就是下一个动作的落脚点。
     * 一个 WAITING 或 PAUSED 的会话被选中, 动作接下来必然被第 6 步拒掉 —— 那不是"解析失败",
     * 那是把一个必然失败的会话当成答案交出去。宁可掉到第 5 档新开一个, 也不要交出一个用不了的。
     *
     * <p>选了"最近的"而不是"最早的"或"任意的": 同一个人可能在同一个应用里开着好几个会话
     * (自娱一局、等人一局), 没有显式上下文时, 最近动过的那个最接近他脑子里的"我正在用的那个"。
     *
     * <p><b>前两档被区别对待, 判据是"这是谁的断言"。</b>第 1、3 档是调用方<em>断言</em>了一个
     * 会话("在这局里落子"), 断言落空就该得到错误 —— 悄悄换一局给他才是真正会出事的行为。
     * 第 2 档是<em>平台的记账</em>(资源行上的 {@code session_id}), 用户从没说过"我要用 7 天前
     * 那个会话"。记账过时了(会话被回收器收掉)就重新解一次; 不这样做的话, 像提醒收件箱这种
     * URI 里没有 {@code sessionId} 段的资源会在闲置一周后<em>永久</em>报 {@code SESSION_ENDED},
     * 而用户完全不知道自己做错了什么。
     *
     * @throws SessionException 第 1、3 档被断言但那个会话不可用(不存在/已结束/不属于这个应用),
     *         或第 5 档建不出来(应用未发布)
     */
    private ApplicationSessionRecord resolveSession(String target,
                                                    ApplicationManifest manifest,
                                                    ResolvedPrincipal principal) {
        String declared = declaredSessionId(target, manifest, principal);
        if (declared != null) {
            return sessions.requireUsable(declared, manifest.applicationId());
        }
        String anchored = anchoredSessionId(target);
        if (anchored != null) {
            Optional<ApplicationSessionRecord> usable =
                    sessions.findUsable(anchored, manifest.applicationId());
            if (usable.isPresent()) {
                return usable.get();
            }
            // 记账过时了 —— 不是错误, 是"该重新解一次"。下一次写入会把资源行重新挂到新会话上
            // (见 ResourceStore.reanchorIfStale), 于是事件路由也跟着回到能找得到人的那个会话。
            log.debug("[ActionGateway] 资源 {} 记的会话 {} 已不可用, 重新解析", target, anchored);
        }
        return sessions.findLive(manifest.applicationId(), principal)
                .map(live -> sessions.requireUsable(live.getId(), manifest.applicationId()))
                .orElseGet(() -> sessions.ensureSession(manifest.applicationId(), principal));
    }

    /**
     * 会话解析的前 3 档 —— 只看调用方和 URI 说了什么, <b>不查库里的"最近会话"</b>。
     *
     * <p>单独抽出来是因为 {@link #pendingActions} 需要这 3 档却<em>不能</em>要第 4、5 档:
     * 它是一条读路径, "这个人还没开过会话"不该在这里顺手建一个。
     *
     * @return 候选 sessionId, 或者 null 表示"URI 和调用方都没说"
     */
    private String explicitSessionId(String target, ApplicationManifest manifest,
                                     ResolvedPrincipal principal) {
        String declared = declaredSessionId(target, manifest, principal);
        return declared != null ? declared : anchoredSessionId(target);
    }

    /** 第 1、3 档: 调用方或 URI <em>指定</em>了会话 —— 这是断言, 落空即错误。 */
    private String declaredSessionId(String target, ApplicationManifest manifest,
                                     ResolvedPrincipal principal) {
        if (StringUtils.hasText(principal.sessionId())) {
            return principal.sessionId();
        }
        return resolver.sessionIdIn(manifest, target).orElse(null);
    }

    /** 第 2 档: 资源行上记着的会话 —— 这是平台的记账, 过时了就该重新解。 */
    private String anchoredSessionId(String target) {
        ResourceView existing = resources.find(target).orElse(null);
        return existing != null && StringUtils.hasText(existing.sessionId()) ? existing.sessionId() : null;
    }

    private ActionResponse toResponse(ActionOutcome outcome,
                                      ActionHandlerContext ctx,
                                      String target,
                                      ActionResolution resolution) {
        ResourceView resource = read(target).orElse(null);
        if (outcome.status() == ActionStatus.SUCCESS) {
            return ActionResponse.success(outcome.result(), resource, ctx.emittedEvents());
        }
        return new ActionResponse(outcome.status(), outcome.result(), resource,
                ctx.emittedEvents(),
                ActionError.of(outcome.errorCode() == null ? "FAILED" : outcome.errorCode(),
                        outcome.errorMessage()));
    }

    private ResourceView conflictView(StateConflictException e) {
        JsonNode state = null;
        if (e.currentStateJson() != null) {
            try {
                state = objectMapper.readTree(e.currentStateJson());
            } catch (Exception ignored) {
                state = null;
            }
        }
        return new ResourceView(e.uri(), e.resourceType(), e.applicationId(), e.sessionId(),
                state, e.currentVersion(), null, null);
    }

    private ActionResponse reject(String actionId, ResolvedPrincipal principal, String target,
                                  ActionStatus status, String code, String message) {
        if (actionId != null) {
            audit.record(ActionAuditRecorder.AuditEntry.of(actionId, null, target)
                    .withPrincipal(principal.typeName(), principal.principalId(),
                            principal.companionId(), principal.userId())
                    .withExecution(status.name(), message)
                    .withCorrelation(principal.correlationId()));
        }
        return ActionResponse.failure(status, code, message);
    }

    private ActionAuditRecorder.AuditEntry auditEntry(ActionRequest request,
                                                      ActionResolution resolution,
                                                      ResolvedPrincipal principal) {
        return ActionAuditRecorder.AuditEntry
                .of(resolution.actionId(), resolution.applicationId(), request.target())
                .withPrincipal(principal.typeName(), principal.principalId(),
                        principal.companionId(), principal.userId())
                .withCorrelation(principal.correlationId());
    }

    /**
     * 进程内调用的确定性幂等键: 同一次因果事件重放 → 同一个键 → 被幂等层短路。
     *
     * <p><b>{@code target} 必须参与其中, 不能只在没有 correlationId 时才用它。</b>一个
     * correlationId 描述的是"这一次因果", 而一次因果完全可能落在两个不同的资源上 —— 数字人
     * 收到一条 {@code reminder.due} 后既可能改这条提醒、也可能同时看它的收件箱。若键里只有
     * correlationId + actionId, 第二次调用会被当成第一次的重放, <em>安静地返回另一个资源的结果</em>。
     * 那种错误不会报错, 只会让调用方拿到一份不属于它的状态。
     *
     * <p>截断时补一个哈希尾巴: 直接 {@code substring} 会把 {@code target}(它恰好在末尾)整个切掉,
     * 于是键又退化成"只有 correlationId + actionId"。
     */
    static String deriveIdempotencyKey(ActionRequest request, ResolvedPrincipal principal) {
        String correlation = principal.correlationId();
        String seed = StringUtils.hasText(correlation)
                ? correlation + "@" + request.target()
                : request.target();
        String key = "inproc:" + principal.typeName() + ":" + principal.principalId()
                + ":" + request.action() + ":" + seed;
        return key.length() <= 200
                ? key
                : key.substring(0, 160) + ":" + Integer.toHexString(key.hashCode());
    }

    private ResourceView withHint(ResourceView view, String uri) {
        return resolver.ownerOf(uri)
                .flatMap(manifest -> manifest.resources().stream()
                        .filter(r -> com.luxera.companion.application.manifest.UriTemplate
                                .matches(r.uriTemplate(), uri))
                        .map(ApplicationManifest.ResourceDecl::agentHint)
                        .findFirst())
                .map(hint -> new ResourceView(view.uri(), view.resourceType(), view.applicationId(),
                        view.sessionId(), view.state(), view.version(), view.updatedAt(), hint))
                .orElse(view);
    }

    private static ApplicationView toView(ApplicationManifest manifest) {
        return new ApplicationView(
                manifest.applicationId(),
                manifest.version(),
                manifest.identity().name(),
                manifest.identity().description(),
                manifest.identity().category(),
                manifest.capabilities().stream()
                        .map(ApplicationManifest.CapabilityDecl::id).toList());
    }

    /** manifest 的声明 + agentHint 一起给出去 —— 这就是 Agent 看到的全部。 */
    static ActionSpec toSpec(ApplicationManifest manifest, ApplicationManifest.ActionDecl action) {
        return new ActionSpec(
                action.id(),
                manifest.applicationId(),
                action.capability(),
                action.description(),
                action.permission(),
                action.risk(),
                action.attention(),
                action.inputSchema(),
                action.agentHint());
    }
}
