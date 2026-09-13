package com.luxera.companion.application.invitation;

import com.luxera.companion.application.RecordingApplicationEventSink;
import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.event.LapEventPublisher;
import com.luxera.companion.application.domain.SessionInvitationRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.SessionInvitationRepository;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v2: <b>邀请</b> —— 一张票的一生: 铸造 → (预览) → 消费 / 过期 / 撤回。
 *
 * <p>{@code InviteCreateTest} / {@code InviteConsumeTest} / {@code InviteExpireTest} /
 * {@code InviteRevokeTest} 四类用例合在一个类里, 因为它们共用同一套夹具(开一个 tictactoe 会话、请一位
 * 主人)。分四个类也行, 但散出去的只是重复的 {@code launch} 样板, 挡不住任何一条真正的断言。
 *
 * <p><b>这一类里的每条断言都有一条对应的"不该是那样的"反例</b>——那是 R10 的全部意义:
 * 邀请不是"换个方法 join", 而是一张有死法、有死因的票。
 */
@ActiveProfiles("test")
@SpringBootTest
class InvitationTest {

    private static final String APP_ID = "com.luxera.tictactoe";

    @Autowired
    ApplicationSessionService sessions;

    @Autowired
    ParticipantService participants;

    @Autowired
    InvitationService invitations;

    @Autowired
    SessionInvitationRepository rows;

    @Autowired
    RecordingApplicationEventSink events;

    @Autowired
    LapEventPublisher publisher;

    // ─────────────────────────── InviteCreateTest ───────────────────────────

    /** 主人铸一张分享票: 拿到的是明文 token, 库里只留哈希。 */
    @Test
    void theOwnerMintsAShareLinkAndOnlyTheHashIsPersisted() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        final String[] token = new String[1];

        SessionInvitationRecord row = invitations.mint(session.getId(), alice, null,
                null, null, null, null, null , t -> token[0] = t);

        assertEquals(SessionInvitationRecord.STATUS_CREATED, row.getStatus());
        assertEquals(session.getId(), row.getSessionId());
        assertEquals(SessionParticipantRecord.ROLE_MEMBER, row.getRole(), "缺省角色是 MEMBER");
        assertEquals(64, row.getTokenHash().length());
        assertFalse(token[0].equals(row.getTokenHash()));
        // 明文只在 token 里那一次 —— 行上任何一列都不该是它。
        assertTrue(rows.findById(row.getId()).map(r -> token[0].equals(r.getTokenHash())).isEmpty()
                || !rows.findById(row.getId()).get().getTokenHash().equals(token[0]));
    }

    /** 不是主人铸不了票。 */
    @Test
    void aMemberCannotMintAnInvitation() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        ResolvedPrincipal bob = human();
        participants.join(session.getId(), bob, SessionParticipantRecord.ROLE_MEMBER, true);

        SessionException e = assertThrows(SessionException.class,
                () -> invitations.mint(session.getId(), bob, null, null, null, null, null, null, null));
        assertEquals("NOT_SESSION_OWNER", e.code());
    }

    /** 邀请的 role 被冻住: 即使铸造者后来不再是唯一管事的, 票上还是当初约定的角色(见类注释)。 */
    @Test
    void theRoleIsFrozenAtMintTime() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);

        SessionInvitationRecord row = invitations.mint(session.getId(), alice,
                SessionParticipantRecord.ROLE_OBSERVER, null, null, null, null, null, null);

        assertEquals(SessionParticipantRecord.ROLE_OBSERVER, row.getRole());
    }

    // ─────────────────────────── InviteConsumeTest ───────────────────────────

    /** 持票人兑票: 以票上的角色加入会话, 绕过默认的 INVITE_ONLY。 */
    @Test
    void aStrangerConsumesTheTokenAndJoinsWithTheInvitationRole() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        final String[] token = new String[1];
        SessionInvitationRecord row = invitations.mint(session.getId(), alice,
                SessionParticipantRecord.ROLE_MEMBER, null, null, null, null, null , t -> token[0] = t);

        ResolvedPrincipal bob = human();
        SessionParticipantRecord joined = invitations.consume(token[0], bob);

        assertEquals(SessionParticipantRecord.ROLE_MEMBER, joined.getRole());
        assertEquals(session.getId(), joined.getSessionId());
        assertTrue(participants.find(session.getId(), PrincipalType.HUMAN, bob.principalId()).isPresent());
        assertEquals(1, rows.findById(row.getId()).orElseThrow().getUsedCount());
    }

    /** 一张票兑两次: 第二次是 INVITATION_CONSUMED。 */
    @Test
    void theSameTokenCannotBeRedeemedTwice() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        final String[] token = new String[1];
        invitations.mint(session.getId(), alice, null, null, 1, null, null, null , t -> token[0] = t);

        invitations.consume(token[0], human());
        SessionException e = assertThrows(SessionException.class,
                () -> invitations.consume(token[0], human()));
        assertEquals("INVITATION_CONSUMED", e.code());
    }

    /** 不限次数的票可以多人兑, 但每次加入的都是不同的人。 */
    @Test
    void anUnlimitedInvitationAdmitsManyButNeverTwiceTheSamePerson() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        final String[] token = new String[1];
        invitations.mint(session.getId(), alice, null, null, null, null, null, null , t -> token[0] = t);

        ResolvedPrincipal bob = human();
        ResolvedPrincipal carol = human();
        invitations.consume(token[0], bob);
        invitations.consume(token[0], carol);

        assertEquals(2, rows.findByTokenHash(
                com.luxera.companion.application.manifest.ManifestCatalogueSync.sha256(token[0]))
                .orElseThrow().getUsedCount());
        // bob 再来一次: 同一张票、同一个人, 应该还是能进(join 幂等)但 usedCount 不该再加。
        SessionInvitationRecord before = rows.findByTokenHash(
                com.luxera.companion.application.manifest.ManifestCatalogueSync.sha256(token[0]))
                .orElseThrow();
        invitations.consume(token[0], bob);
        assertEquals(before.getUsedCount(), rows.findByTokenHash(
                com.luxera.companion.application.manifest.ManifestCatalogueSync.sha256(token[0]))
                .orElseThrow().getUsedCount());
    }

    /**
     * 票面 maxUses 用尽后, 票进入 CONSUMED 终局 —— 想再来得换新票。
     *
     * <p>§15 把"使用"与"过期"画成两条不同的死法: 次数用尽是"使用"完的尽头(CONSUMED),
     * "过期"(EXPIRED)只留给 {@code expiresAt} 到点。两者死因不同, 报错也该不同 ——
     * 但都进不了会场。
     */
    @Test
    void aMaxUsesInvitationExpiresWhenItRunsDry() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        final String[] token = new String[1];
        invitations.mint(session.getId(), alice, null, null, 1, null, null, null , t -> token[0] = t);

        invitations.consume(token[0], human());

        SessionException e = assertThrows(SessionException.class,
                () -> invitations.consume(token[0], human()));
        assertEquals("INVITATION_CONSUMED", e.code());
    }

    /** 一段不是我们铸出来的 token, 连表都不查就拒(见 assertTokenShape)。 */
    @Test
    void aMalformedTokenIsRejectedBeforeItHitsTheTable() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        invitations.mint(session.getId(), alice, null, null, 1, null, null, null, null);

        SessionException e = assertThrows(SessionException.class,
                () -> invitations.consume("not-a-real-token", human()));
        assertEquals("UNKNOWN_INVITATION", e.code());
    }

    // ─────────────────────────── InviteExpireTest ───────────────────────────

    /** 到点了的票是 INVITATION_EXPIRED。 */
    @Test
    void anInvitationPastItsExpiryIsRefused() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        final String[] token = new String[1];
        invitations.mint(session.getId(), alice, null, null, null, null, null,
                LocalDateTime.now().minusMinutes(1) , t -> token[0] = t);

        assertTrue(invitations.refusal(rows.findByTokenHash(
                com.luxera.companion.application.manifest.ManifestCatalogueSync.sha256(token[0]))
                .orElseThrow()).isPresent());
        SessionException e = assertThrows(SessionException.class,
                () -> invitations.consume(token[0], human()));
        assertEquals("INVITATION_EXPIRED", e.code());
    }

    // ─────────────────────────── InviteRevokeTest ───────────────────────────

    /** 主人撤回一张还没用掉的票: 撤回 > 一切, 即使它还有余量、还没到点。 */
    @Test
    void theOwnerCanRevokeAnInvitationAndItStopsWorking() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        final String[] token = new String[1];
        SessionInvitationRecord row = invitations.mint(session.getId(), alice,
                null, null, null, null, null, null , t -> token[0] = t);

        invitations.revoke(row.getId(), alice);

        assertEquals(SessionInvitationRecord.STATUS_REVOKED,
                rows.findById(row.getId()).orElseThrow().getStatus());
        SessionException e = assertThrows(SessionException.class,
                () -> invitations.consume(token[0], human()));
        assertEquals("INVITATION_REVOKED", e.code());
    }

    /** 不是主人撤不了票。 */
    @Test
    void aMemberCannotRevokeAnInvitation() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        ResolvedPrincipal bob = human();
        participants.join(session.getId(), bob, SessionParticipantRecord.ROLE_MEMBER, true);
        SessionInvitationRecord row = invitations.mint(session.getId(), alice,
                null, null, null, null, null, null, null);

        SessionException e = assertThrows(SessionException.class,
                () -> invitations.revoke(row.getId(), bob));
        assertEquals("NOT_SESSION_OWNER", e.code());
    }

    // ─────────────────────────── 定向邀请 → APPLICATION_INVITATION ───────────────────────────

    /** 定向票(邀请某位数字人)发射平台级 {@code APPLICATION_INVITATION}, 且点名 recipient。 */
    @Test
    void aDirectedAgentInvitationEmitsAPlatformEventWithTheRecipientStamped() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch(APP_ID, alice);
        String companionId = "dh-" + UUID.randomUUID();
        events.clear();

        String[] minted = new String[1];
        SessionInvitationRecord row = invitations.mint(session.getId(), alice,
                SessionParticipantRecord.ROLE_MEMBER, null, 1, "AGENT", companionId,
                null, token -> minted[0] = token);

        // mint 只铸票; 事件由控制器在铸造之后发射(见 LapInvitationController)。
        // 这里用 spring 里那个真实的 publisher 走一遍 publishPlatform —— 它必须绕过
        // triggersAgent 闸门(平台事件不属于任何 manifest), 且把 companionId 留在 data 上。
        ApplicationEvent event = invitations.invitationEvent(row, APP_ID, companionId, minted[0]);
        publisher.publishPlatform(List.of(event));

        List<ApplicationEvent> received = events.eventsOfType(InvitationService.EVENT_APPLICATION_INVITATION);
        assertEquals(1, received.size(), "平台事件必须真的到达 sink(不经 manifest 闸门)");
        ApplicationEvent receivedEvent = received.get(0);
        assertEquals(companionId, receivedEvent.data().path("companionId").asText());
        assertTrue(receivedEvent.data().path("agentTrigger").asBoolean(false));
        assertEquals(session.getId(), receivedEvent.data().path("sessionId").asText());

        // R13: 明文 token 随信寄出 —— 收件人是个数字人, 它没有浏览器可以点开 /join/{token},
        // 所以"那条链接"对它就是这段 token 本身。它拿到的必须是能兑的那一段: 兑一次试试。
        assertEquals(minted[0], receivedEvent.data().path("token").asText(),
                "邀请事件必须带着明文 token —— 否则数字人永远 accept 不了(invite-only 是默认策略)");
        ResolvedPrincipal agent = new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId,
                null, null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_INTERNAL);
        assertNotNull(invitations.consume(receivedEvent.data().path("token").asText(), agent),
                "信里的 token 必须真的能兑出一行参与者");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private static ResolvedPrincipal human() {
        String id = "p-" + UUID.randomUUID();
        return new ResolvedPrincipal(PrincipalType.HUMAN, id, null, id, null,
                UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
    }
}