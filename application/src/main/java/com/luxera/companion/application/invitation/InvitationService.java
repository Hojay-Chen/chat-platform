package com.luxera.companion.application.invitation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionInvitationRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.manifest.ManifestCatalogueSync;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.SessionInvitationRepository;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.application.session.SessionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * LAP v2: <b>邀请链接</b> —— 铸造、校验、消费、撤销。
 *
 * <p>它住在 "session" 与 "参与"之间, 回答的是那两件东西都回答不了的问题:"<em>别人</em>怎么进来"
 * (`ParticipantService.join` 只回答"<em>我自己</em>怎么进来")。邀请是 v2 唯一一条"替别人打开
 * 一道门"的路 —— 别的路径要么靠已认证身份, 要么已经被明确拒绝(请求体里不许带 {@code principalId})。
 *
 * <h2>Capability Token: 这段密码只有一次, 且只见一次</h2>
 * <p>铸造时生成 22 字节的 {@link SecureRandom} 随机串并 base64 成 token, 库里只留 SHA-256
 * {@code token_hash}。明文<em>只在 mint 的返回里出现这一次</em>—— 之后本类只能拿它哈希反查,
 * 永远验不出明文。这一条不是加密, 是"凭据只存指纹": 即便整张表泄露, 持票人也一个都不暴露。
 *
 * <h2>四件事: mint / consume / preview / revoke</h2>
 * <ul>
 *   <li><b>mint</b> —— 主人(只有主人)给会话铸一张票, 冻住 role / maxUses / expiresAt。
 *       明文 token 在铸造回调里交给调用方<em>一次</em> —— 那是"分享链接"的原料。</li>
 *   <li><b>consume</b> —— 持票人兑票: 认票(死因说得清: INVITATION_REVOKED / EXPIRED /
 *       CONSUMED), 然后 {@code ParticipantService} 以 {@code viaInvitation=true} 加入。</li>
 *   <li><b>preview / revoke</b> —— 分享页看票还活着没有; 主人收回一张还没用掉的票。</li>
 * </ul>
 *
 * <h2>定向邀请不是另一条放进来的路</h2>
 * <p>{@code targetType + targetId} 只是铸造时记在票上的一个<em>约定</em>: "这张票是给某某的"
 * (如邀请某位数字人, §59/§60)。平台把 {@code APPLICATION_INVITATION} 事件发给它, 但它
 * 要进会话还是得凭 token 走 {@code consume} 这同一扇门。不在门内开第二条路, 是因为那样
 * 就会有人拿"我知道 companionId"当成"我有权替它加入" —— 两者从来不是一回事。
 */
@Slf4j
@Service
public class InvitationService {

    /** 平台级邀请事件 —— 由邀请服务发射, 不经 manifest 的 triggersAgent 闸门(见计划修正 2)。 */
    public static final String EVENT_APPLICATION_INVITATION = "APPLICATION_INVITATION";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionInvitationRepository invitations;
    private final ParticipantService participants;
    private final ApplicationSessionService sessions;
    private final InvitationStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    public InvitationService(SessionInvitationRepository invitations,
                             ParticipantService participants,
                             ApplicationSessionService sessions,
                             InvitationStateMachine stateMachine,
                             ObjectMapper objectMapper) {
        this.invitations = invitations;
        this.participants = participants;
        this.sessions = sessions;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────── 铸造 ───────────────────────────

    /**
     * 铸造一张票。
     *
     * <p>{@code targetType}/{@code targetId} 非空时是一张<b>定向票</b>("给某个 principal 的"),
     * 同时把 {@code APPLICATION_INVITATION} 事件发出去(见 {@link #invitationEvent});
     * 为空时是一张<b>分享票</b>("拿到链接的任何人"), 不发事件 —— 事件说"有人邀请<em>你</em>",
     * 而"谁会点开这个链接"铸造时不知道。
     *
     * <p>{@code onMint} 是铸造回调: 明文 token 诞生的那一刻由调用方处置(拼分享链接、落审计)。
     * 它拿到的 {@code Minted.token} 是这段明文在宇宙间唯一一次暴露 —— 本方法返回值里不再有它。
     */
    @Transactional
    public SessionInvitationRecord mint(String sessionId, ResolvedPrincipal actor, String role,
                                        String joinPolicy, Integer maxUses, String targetType,
                                        String targetId, LocalDateTime expiresAt,
                                        Consumer<String> onToken) {
        ApplicationSessionRecord session = requireOwned(sessionId, actor);
        SessionInvitationRecord invitation = new SessionInvitationRecord();
        invitation.setSessionId(sessionId);
        invitation.setCreatedByType(actor.typeName());
        invitation.setCreatedById(actor.principalId());
        invitation.setRole(role == null || role.isBlank()
                ? SessionParticipantRecord.ROLE_MEMBER : role);
        invitation.setJoinPolicy(joinPolicy);
        invitation.setMaxUses(maxUses);
        invitation.setTargetType(targetType);
        invitation.setTargetId(targetId);
        invitation.setExpiresAt(expiresAt);
        invitation.setStatus(SessionInvitationRecord.STATUS_CREATED);

        String token = mintToken();
        invitation.setTokenHash(ManifestCatalogueSync.sha256(token));
        invitations.save(invitation);

        if (onToken != null) {
            onToken.accept(token);
        }
        log.info("[Invitation] 铸造 {} 邀请 {} (session={}, role={})",
                StringUtils.hasText(targetType) ? targetType : "SESSION",
                invitation.getId(), sessionId, invitation.getRole());
        return invitation;
    }

    /** 只有会话主人能铸票。别人拿不到票, 也就分享不了这个会话 —— 加入策略的入口在主人手里。 */
    private ApplicationSessionRecord requireOwned(String sessionId, ResolvedPrincipal actor) {
        ApplicationSessionRecord session = sessions.require(sessionId);
        SessionParticipantRecord me = participants.find(sessionId, actor.type(), actor.principalId())
                .orElseThrow(() -> new SessionException("NOT_A_PARTICIPANT",
                        "铸票的人不在会话 " + sessionId + " 里"));
        if (!me.owner()) {
            throw new SessionException("NOT_SESSION_OWNER", "只有会话的主人能发邀请");
        }
        return session;
    }

    // ─────────────────────────── 认票 / 消费 ───────────────────────────

    /**
     * 兑一张票。三步, <b>顺序不可换</b>: 认票 → 以邀请的 role 加入({@code viaInvitation=true},
     * 这是绕过 {@code INVITE_ONLY} 的唯一路径) → 记账({@code usedCount+1}, 次数用尽即终局)。
     *
     * <p>加入成功<em>之后</em>才记账: 加入被拒(会话满了/关闸了)的票不该被烧掉 ——
     * 调用方拿着同一张票, 等到会话腾出位置, 还该进得来。
     */
    @Transactional
    public SessionParticipantRecord consume(String token, ResolvedPrincipal principal) {
        assertTokenShape(token);
        SessionInvitationRecord invitation = invitations.findByTokenHash(ManifestCatalogueSync.sha256(token))
                .orElseThrow(() -> new SessionException("UNKNOWN_INVITATION", "邀请链接无效或已被删除"));
        receive(invitation);

        // 先记下这个人是不是已经在场: join 幂等 —— 同一个人兑同一张票, 第二次不烧名额,
        // 否则一张 5 人票会被同一个人反复兑到只剩 4 个"真名额"。
        boolean alreadyIn = participants.find(invitation.getSessionId(),
                principal.type(), principal.principalId())
                .map(SessionParticipantRecord::active)
                .orElse(false);

        SessionParticipantRecord row = participants.join(invitation.getSessionId(), principal,
                invitation.getRole(), true);

        if (!alreadyIn) {
            invitation.setUsedCount(invitation.getUsedCount() + 1);
        }
        if (invitation.getMaxUses() != null && invitation.getUsedCount() >= invitation.getMaxUses()) {
            stateMachine.transition(invitation, SessionInvitationRecord.STATUS_CONSUMED);
        }
        invitations.save(invitation);
        log.info("[Invitation] {} 兑票 {} → 加入会话 {} (role={})",
                principal.principalId(), invitation.getId(), invitation.getSessionId(), row.getRole());
        return row;
    }

    /**
     * 认票 —— <b>不落任何状态</b>, 只回答"这张票现在还能不能兑"。
     *
     * <p>与 {@link #consume} 分开, 是因为"能兑"与"兑了"是两件时间点不同的事: 分享页在点进去
     * 之前就要知道票还活着(否则渲染一个注定 409 的按钮), 而那个判断不该有任何副作用。
     */
    public Optional<SessionException> refusal(SessionInvitationRecord invitation) {
        if (invitation == null) {
            return Optional.of(new SessionException("UNKNOWN_INVITATION", "邀请链接无效或已被删除"));
        }
        if (invitation.revoked()) {
            return Optional.of(new SessionException("INVITATION_REVOKED", "这张邀请已被撤回"));
        }
        if (SessionInvitationRecord.STATUS_CONSUMED.equals(invitation.getStatus())) {
            return Optional.of(new SessionException("INVITATION_CONSUMED", "这张邀请已用掉"));
        }
        if (invitation.getMaxUses() != null && invitation.getUsedCount() >= invitation.getMaxUses()) {
            // 防御分支: 正常路径里次数一用尽, consume 当场就把状态转成 CONSUMED, 轮不到这里。
            // 走到这里说明出现了一张"次数已尽却还挂 CREATED"的畸形票(如 mint 时 maxUses=0)。
            return Optional.of(new SessionException("INVITATION_CONSUMED", "这张邀请的次数已用尽"));
        }
        if (invitation.expiredAtClock()) {
            return Optional.of(new SessionException("INVITATION_EXPIRED", "这张邀请已过期"));
        }
        return Optional.empty();
    }

    /** 认不过就抛。死因按序: 撤回 → 已用(单次票) → 用尽 → 过期。 */
    private void receive(SessionInvitationRecord invitation) {
        // 撤回最优先: 一张还没用掉的票, 主人一句话就作废 —— 那是主人的权柄, 不看钟也不看次数。
        // 与 ParticipantService.requireAdmission 的 CLOSED 判据一致: 关闸超过一切。
        if (invitation.revoked()) {
            throw new SessionException("INVITATION_REVOKED", "这张邀请已被撤回");
        }
        if (SessionInvitationRecord.STATUS_CONSUMED.equals(invitation.getStatus())) {
            throw new SessionException("INVITATION_CONSUMED", "这张邀请已用掉");
        }
        if (invitation.getMaxUses() != null && invitation.getUsedCount() >= invitation.getMaxUses()) {
            // 防御分支(同 refusal): 次数用尽的死法是"使用"的尽头 —— CONSUMED。
            throw new SessionException("INVITATION_CONSUMED", "这张邀请的次数已用尽");
        }
        if (invitation.expiredAtClock()) {
            throw new SessionException("INVITATION_EXPIRED", "这张邀请已过期");
        }
    }

    /**
     * token 形状检查: 必须长得像我们铸出来的(base64url 无填充, 22 字节 → 30 字符)。
     * <b>先查形状再查表</b> —— 拿一段明显不是 token 的东西去凑哈希查表, 只会白白查一次索引。
     */
    private void assertTokenShape(String token) {
        if (!StringUtils.hasText(token) || !token.matches("[A-Za-z0-9_-]{30}")) {
            throw new SessionException("UNKNOWN_INVITATION", "邀请链接无效或已被删除");
        }
    }

    // ─────────────────────────── 预览 / 撤销 ───────────────────────────

    /** 分享页看票: 会话、角色、还有没有余量、到点没有。只对有权的参与者开放。 */
    @Transactional(readOnly = true)
    public Preview preview(String invitationId) {
        SessionInvitationRecord invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new SessionException("UNKNOWN_INVITATION", "邀请不存在"));
        return new Preview(invitation.getId(), invitation.getSessionId(), invitation.getRole(),
                invitation.getMaxUses(), invitation.getUsedCount(), invitation.getStatus(),
                invitation.getExpiresAt(), refusal(invitation).map(SessionException::code).orElse(null));
    }

    /** 会话里所有票 —— 主人的管理面用。 */
    @Transactional(readOnly = true)
    public List<SessionInvitationRecord> ofSession(String sessionId) {
        return invitations.findBySessionId(sessionId);
    }

    /** 撤回一张还没死掉的票。只有主人能撤回。 */
    @Transactional
    public void revoke(String invitationId, ResolvedPrincipal actor) {
        SessionInvitationRecord invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new SessionException("UNKNOWN_INVITATION", "邀请不存在"));
        requireOwned(invitation.getSessionId(), actor);
        invitations.save(stateMachine.transition(invitation, SessionInvitationRecord.STATUS_REVOKED));
        log.info("[Invitation] {} 撤回邀请 {}", actor.principalId(), invitation.getId());
    }

    // ─────────────────────────── 定向邀请事件 ───────────────────────────

    /**
     * 给 {@code targetType} 为某个 principal 的定向票铸好后, 调用方外发
     * {@code APPLICATION_INVITATION} 事件。事件载荷在这里造, 走平台通道 ——
     * 事件的"给谁"已经写在 {@code data.companionId} 上去了, {@code LapEventPublisher} 拿到
     * 的是"直接投给这个数字人", 不管它在哪个会话、是不是被 manifest 的 triggersAgent 点过名。
     *
     * <p>{@code target} 用 {@code session://invitation/...} 封套: 它既不是资源 URI(没有
     * {@code resource} 行), 也不属于任何 manifest 的资源模板 —— 但 {@code AgentRouteResolver}
     * 按封套解出会话后能继续走"名单上的人在不在这局里"那条老路, 不必为邀请单开一套路由。
     *
     * <h2>为什么明文 token 在这封信里</h2>
     * <p>收件人是一个数字人, 它没有浏览器可以点开 {@code /join/{token}}。要给它的东西因此只能是
     * <em>链接里的那段 token 本身</em> —— 邀请事件就是它的那条链接, 只是投递方式不同。
     *
     * <p><b>这不是给定向邀请开后门。</b> 数字人拿到 token 之后仍然要兑票
     * ({@code ApplicationRuntimePort.joinByInvitation} → {@link #consume}), 走的与真人点链接
     * 逐字相同的那一扇门; 上面那段"不在门内开第二条路"的规矩一个字都没松。这一段讲的是
     * <em>投递</em>, 不是<em>放行</em>。
     *
     * <p>因此它也继承了 token 的性质: 只在这里出现, 库里只有哈希。数字人那侧读到它之后
     * 不写日志、不写账本、不进提示词 —— 一条凭据被记进"记忆"里就不再是凭据了。
     */
    public ApplicationEvent invitationEvent(SessionInvitationRecord invitation,
                                            String applicationId, String targetId, String token) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("invitationId", invitation.getId());
        data.put("sessionId", invitation.getSessionId());
        data.put("role", invitation.getRole());
        data.put("token", token);
        // 平台此刻把"这是给谁的"钉死在信封的 data.companionId 上 —— 路由不再查名单,
        // 投递面拿到的已经是"直接给这个数字人"。
        data.put("companionId", targetId);
        // 这一次触发货真价实: "有邀请来了" 值得把数字人叫起来看一眼(不是每一次都要)。
        // 见 §82 决定 2: 邀请事件由平台发射, 不经 manifest 的 triggersAgent 闸门。
        data.put("agentTrigger", true);
        return new ApplicationEvent(
                "invitation://" + invitation.getId() + "#minted",
                EVENT_APPLICATION_INVITATION,
                applicationId,
                "session://invitation/" + invitation.getSessionId() + "/" + invitation.getId(),
                Instant.now(),
                data);
    }

    // ─────────────────────────── 形状 ───────────────────────────

    public record Preview(String invitationId, String sessionId, String role,
                          Integer maxUses, int usedCount, String status,
                          LocalDateTime expiresAt, String refusal) {}

    /** 铸一段随机的 22 字节明文, base64url 无填充 → 30 个字符。 */
    private static String mintToken() {
        byte[] bytes = new byte[22];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}