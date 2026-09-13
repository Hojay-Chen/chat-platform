package com.luxera.companion.application.web;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionInvitationRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.event.LapEventPublisher;
import com.luxera.companion.application.invitation.InvitationService;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ApplicationEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * LAP v2: <b>邀请</b> —— 把别人请进这一场的唯一方式。
 *
 * <p>三组端点, 各自回答一个问题:
 *
 * <pre>
 *   POST   /sessions/{id}/invitations            我(主人)要发邀请(分享票 / 定向票)
 *   GET    /sessions/{id}/invitations            这个会话发过哪些票(管理面)
 *   DELETE /invitations/{invitationId}          收回一张票
 *   POST   /join/{token}                        (公开)持票人兑票进会话
 * </pre>
 *
 * <h2>路径里的 token 不是数据库 id</h2>
 * <p>§14 说链接只表达"加入这一场", 不暴露 sessionId / userId / permission。{@code /join/{token}}
 * 路径里那 30 个字符是 Capability Token 的明文, 库里只有它的 SHA-256 —— 拿到链接的人能进,
 * 但看不到这张票编号几号、谁铸的、给谁。
 *
 * <h2>定向邀请的 targetType/targetId 在请求体里, 而身份仍然在凭据里</h2>
 * <p>§59 的请求体带着 {@code targetType + targetId}, 而 §36 的"身份从 Context 获得"管的是
 * <em>调用方自己</em>。两者不矛盾: 这里 target 说的是"<em>被邀请的</em>那一位"是谁, 调用方
 * ("我要邀请某人")仍由 Authorization 头唯一决定。仍然只有主人能发定向票 —— 这是 {@code mint}
 * 的判据, 不是请求体里的字段。
 */
@RestController
@RequestMapping("/api/v1")
public class LapInvitationController {

    private final InvitationService invitations;
    private final ApplicationSessionService sessions;
    private final ParticipantService participants;
    private final LapEventPublisher events;
    private final PrincipalResolvers principals;

    public LapInvitationController(InvitationService invitations,
                                   ApplicationSessionService sessions,
                                   ParticipantService participants,
                                   LapEventPublisher events,
                                   PrincipalResolvers principals) {
        this.invitations = invitations;
        this.sessions = sessions;
        this.participants = participants;
        this.events = events;
        this.principals = principals;
    }

    /**
     * 铸一张票。{@code role}/{@code maxUses}/{@code expiresAt} 都可选; {@code targetType} +
     * {@code targetId} 给了就是定向票(顺手发 {@code APPLICATION_INVITATION}), 没给就是分享票。
     */
    public record MintRequest(String role, Integer maxUses, String expiresAt,
                              String targetType, String targetId) {}

    /**
     * 铸造的响应。<b>token 在这里、且只在这里出现</b> —— 它是这个接口存在的理由(主人要把
     * 链接发给朋友), 也是 R10 的那条验收断言("token 明文不进库")的反面: 它必须出现在
     * <em>响应里一次</em>, 否则分享链接根本拼不出来。其余字段全是票的公开属性。
     */
    public record MintResponse(String invitationId, String token, String joinUrl,
                               String sessionId, String role, Integer maxUses,
                               int usedCount, String status, String expiresAt,
                               String targetType, String targetId) {

        static MintResponse of(SessionInvitationRecord row, String token, String joinUrl) {
            return new MintResponse(row.getId(), token, joinUrl, row.getSessionId(), row.getRole(),
                    row.getMaxUses(), row.getUsedCount(), row.getStatus(),
                    row.getExpiresAt() == null ? null : row.getExpiresAt().toString(),
                    row.getTargetType(), row.getTargetId());
        }
    }

    public record InvitationResponse(String invitationId, String sessionId, String role,
                                     Integer maxUses, int usedCount, String status,
                                     String expiresAt, String targetType, String targetId) {

        static InvitationResponse of(SessionInvitationRecord row) {
            return new InvitationResponse(row.getId(), row.getSessionId(), row.getRole(),
                    row.getMaxUses(), row.getUsedCount(), row.getStatus(),
                    row.getExpiresAt() == null ? null : row.getExpiresAt().toString(),
                    row.getTargetType(), row.getTargetId());
        }
    }

    // ─────────────────────────── 铸造 ───────────────────────────

    /**
     * 发一张邀请。只有会话的主人。
     *
     * <p>{@code joinUrl} 由平台拼 —— 调用方不该自己拼: 有一天链接换域名、加签名, 全世界只有
     * 这一个地方要改。token 是它唯一的变量部分。
     */
    @PostMapping("/sessions/{sessionId}/invitations")
    public MintResponse mint(@PathVariable String sessionId,
                             @RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                             @RequestBody(required = false) MintRequest request) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        MintRequest req = request == null ? new MintRequest(null, null, null, null, null) : request;

        String[] holder = new String[1];
        SessionInvitationRecord invitation = invitations.mint(sessionId, principal, req.role(),
                null, req.maxUses(), req.targetType(), req.targetId(), expires(req.expiresAt()),
                token -> holder[0] = token);

        if (req.targetType() != null && !req.targetId().isBlank()) {
            // 定向票: 事件只说"有人邀请你了", 不说"你必须来"(§60)。决定权在数字人那边 ——
            // 它 accept/reject/ignore 里的 accept 才是真正的"兑票"。
            ApplicationSessionRecord session = sessions.require(sessionId);
            String applicationId = session.getApplicationId();
            ApplicationEvent event = invitations.invitationEvent(invitation, applicationId,
                    req.targetId(), holder[0]);
            events.publishPlatform(List.of(event));
        }

        String joinUrl = "/join/" + holder[0];
        return MintResponse.of(invitation, holder[0], joinUrl);
    }

    // ─────────────────────────── 读 / 撤销 ───────────────────────────

    /** 这个会话发过的票。只有主人看得到 —— 票的余量是一种管理信息, 不是公开目录。 */
    @GetMapping("/sessions/{sessionId}/invitations")
    public List<InvitationResponse> ofSession(@PathVariable String sessionId,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        SessionParticipantRecord me = participants.find(sessionId, principal.type(), principal.principalId())
                .orElseThrow(() -> new SessionException("NOT_A_PARTICIPANT",
                        "调用方不在会话 " + sessionId + " 里"));
        if (!me.owner()) {
            throw new SessionException("NOT_SESSION_OWNER", "只有会话的主人能看邀请列表");
        }
        return invitations.ofSession(sessionId).stream()
                .map(InvitationResponse::of)
                .toList();
    }

    /** 收回一张票。已用掉的票不用收回(它已经是终态), 但允许主人对终态票无操作地收回。 */
    @DeleteMapping("/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@PathVariable String invitationId,
                                       @RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        invitations.revoke(invitationId, principal);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────── 公开加入 ───────────────────────────

    /**
     * 兑票进会话 —— <b>公开端点, 需要登录但不需要已经在会话里</b>。
     *
     * <p>到这里的调用方是"拿着链接的任何人": 他有平台账号(凭据解析得出身份), 但在兑票之前
     * 不在这局里。所有准入判断都在 {@code InvitationService.consume} 内: 票认得过 →
     * 以票上的 role、{@code viaInvitation=true} 加入。于是 {@code INVITE_ONLY} 这道默认闸
     * 的钥匙只有一种形状 —— 一张有效的票。
     */
    @PostMapping("/join/{token}")
    public JoinResponse join(@PathVariable String token,
                             @RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        SessionParticipantRecord row = invitations.consume(token, principal);
        return JoinResponse.of(row, participants.capabilitiesOf(row));
    }

    /** 兑票的结果: 你在这一场里的位置, 以及你立刻能做的事(与会话响应里的同名语义一致)。 */
    public record JoinResponse(String participantId, String sessionId,
                               String principalType, String principalId, String role, String status,
                               List<String> capabilities) {

        static JoinResponse of(SessionParticipantRecord row, List<String> capabilities) {
            return new JoinResponse(row.getId(), row.getSessionId(),
                    row.getPrincipalType(), row.getPrincipalId(), row.getRole(), row.getStatus(),
                    capabilities);
        }
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private LocalDateTime expires(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(text);
    }

    private ResolvedPrincipal principal(String authorization, String correlationId) {
        return principals.resolveHeader(authorization,
                correlationId == null || correlationId.isBlank()
                        ? UUID.randomUUID().toString()
                        : correlationId);
    }
}
