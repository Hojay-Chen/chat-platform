package com.luxera.companion.application.web;

import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.application.session.SessionException;
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
 * LAP v2: <b>谁在这一场里</b>。
 *
 * <p>这是整个 v2 里最新的一类端点, 也是把"Agent 只是另一种用户"从一句口号变成一条可验证事实的
 * 地方: 真人在这里加入, Agent 在这里加入, 外部 Agent 也在这里加入 —— <b>同一张表, 同一段代码,
 * 同一个请求体</b>。没有任何一个端点叫 {@code /agents/...}, 也没有任何一个字段叫
 * {@code isAgent}。
 *
 * <h2>请求体里为什么只有 {@code role}</h2>
 * <p>设计方案 §12/§31 的加入请求里带着 {@code principalType} 与 {@code principalId}, 与
 * §36("身份从 Context 获得, 请求里不出现 userId/companionId/agentId")直接冲突, 而且是个越权
 * 入口 —— 任何人都能替别人报名。这里只认 {@code role}; 身份一律取自已认证的凭据。
 *
 * <p><b>替<em>别人</em>加入只有一条路: 邀请链接。</b> 那是能力令牌(Capability Token), 不是
 * "我替你填个 id"—— 前者需要会话主人显式铸造一次, 后者只需要知道对方的 id。两者在安全性上
 * 不是同一个量级的东西, 而这个控制器刻意只提供前者之外的那一半: 自己加入自己。
 *
 * <h2>{@code role} 也不是随便填的</h2>
 * <p>只有开局的人能拿到 {@code OWNER}; 别人自报 {@code OWNER} 会被静默降级成 {@code MEMBER}
 * (见 {@code ParticipantService.normalizeRole})。不是拒绝 —— 拒绝会让"我按同一个模板发的请求,
 * 怎么有的 403 有的 200"变成一件需要读源码才能理解的事; 降级则让结果始终是"你在这一场里的位置
 * 是什么", 一个客户端能直接显示出来的东西。
 */
@RestController
@RequestMapping("/api/v1")
public class LapParticipantController {

    private final ParticipantService participants;
    private final ApplicationSessionService sessions;
    private final PrincipalResolvers principals;

    public LapParticipantController(ParticipantService participants,
                                    ApplicationSessionService sessions,
                                    PrincipalResolvers principals) {
        this.participants = participants;
        this.sessions = sessions;
        this.principals = principals;
    }

    /** 加入请求。<b>只有 role</b> —— 见类注释。省略即"按我在这个会话里的身份给一个默认值"。 */
    public record JoinRequest(String role) {}

    public record ParticipantResponse(String participantId,
                                      String sessionId,
                                      String principalType,
                                      String principalId,
                                      String role,
                                      String status,
                                      String permissionProfile,
                                      List<String> capabilities,
                                      String companionId,
                                      String userId,
                                      String joinedAt,
                                      String leftAt) {

        static ParticipantResponse of(SessionParticipantRecord row, List<String> capabilities) {
            return new ParticipantResponse(row.getId(), row.getSessionId(),
                    row.getPrincipalType(), row.getPrincipalId(),
                    row.getRole(), row.getStatus(), row.effectiveProfile(),
                    capabilities, row.getCompanionId(), row.getUserId(),
                    text(row.getJoinedAt()), text(row.getLeftAt()));
        }

        private static String text(LocalDateTime value) {
            return value == null ? null : value.toString();
        }
    }

    // ─────────────────────────── 加入 ───────────────────────────

    /**
     * 加入一个会话。幂等: 已经在里面就原样返回, 只补齐缺的授权。
     *
     * <p>对 {@code INVITE_ONLY}(默认策略)的会话, 只有开局的人走得通这条路 —— 别人会拿到
     * {@code SESSION_INVITE_ONLY}。这不是"还没实现邀请"的临时状态, 而是默认姿态: 一个会话
     * 默认不该是任何人都能进来的, 开放(或发行邀请链接)是会话主人的显式决定。
     */
    @PostMapping("/sessions/{sessionId}/participants")
    public ParticipantResponse join(@PathVariable String sessionId,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                    @RequestBody(required = false) JoinRequest request) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        SessionParticipantRecord row = participants.join(sessionId, principal,
                request == null ? null : request.role(), false);
        return ParticipantResponse.of(row, participants.capabilitiesOf(row));
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 这一场里有谁。<b>只有在这局里的人看得到</b>。
     *
     * <p>名单里<b>含已经离开的人</b>({@code status=LEFT/REMOVED})—— 每一条都带着 {@code status},
     * 所以调用方读得出来谁在场。刻意不过滤: "他曾经在这一局里"是一局棋的历史的一部分, 而一份
     * 只剩在场者的名单会让"这局中途换过人"变成一个查不到的事实。
     */
    @GetMapping("/sessions/{sessionId}/participants")
    public List<ParticipantResponse> participants(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        sessions.require(sessionId);
        if (participants.find(sessionId, principal.type(), principal.principalId()).isEmpty()) {
            throw new SessionException("NOT_A_PARTICIPANT",
                    principal.typeName() + " " + principal.principalId()
                            + " 不在会话 " + sessionId + " 里");
        }
        return participants.participantsOf(sessionId).stream()
                .map(row -> ParticipantResponse.of(row, participants.capabilitiesOf(row)))
                .toList();
    }

    // ─────────────────────────── 离开 ───────────────────────────

    /**
     * 自己走。会话<b>不会因此结束</b> —— 见 {@code ParticipantService.syncStatus} 里的说明。
     *
     * <p>没有"把别人踢出去"的 REST 端点。{@code ParticipantService.remove} 已经能做了, 但它要
     * 等到 R10 的邀请/管理面一起开 —— 单独开一个 {@code DELETE /participants/{type}/{id}} 会
     * 立刻带来"路径里带着别人的 principalId"的问题, 那正是 {@code principalType} 不该出现在
     * 请求里那条规则要挡住的东西。移除别人要走"对某个已有参与者行做动作"的形式, 不是新造一个
     * 可以填别人 id 的路径。
     */
    @DeleteMapping("/sessions/{sessionId}/participants/me")
    public ResponseEntity<Void> leave(@PathVariable String sessionId,
                                      @RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        participants.leave(sessionId, principal(authorization, correlationId));
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private ResolvedPrincipal principal(String authorization, String correlationId) {
        return principals.resolveHeader(authorization,
                correlationId == null || correlationId.isBlank()
                        ? UUID.randomUUID().toString()
                        : correlationId);
    }
}
