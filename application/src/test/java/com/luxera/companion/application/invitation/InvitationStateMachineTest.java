package com.luxera.companion.application.invitation;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionInvitationRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v2: <b>邀请状态机</b> —— 一张票的四态、与"什么死法说什么话"。
 *
 * <p>四态是 §15 照抄的: CREATED → {CONSUMED, EXPIRED, REVOKED}, 三个终态各归各。这张表钉住
 * 两件事: <b>终态不可逆</b>(用掉的票不能再用, 撤回的票不能撤回), 以及<b>死因的优先级</b>
 * (撤回 > 已用 > 次数用尽 > 过期) —— 那个顺序决定了持有链接的人看到的是哪一句拒绝。
 */
@ActiveProfiles("test")
@SpringBootTest
class InvitationStateMachineTest {

    @Autowired
    InvitationStateMachine machine;

    @Autowired
    InvitationService invitations;

    @Autowired
    ApplicationSessionService sessions;

    SessionInvitationRecord sheet(String status) {
        SessionInvitationRecord row = new SessionInvitationRecord();
        row.setId(UUID.randomUUID().toString());
        row.setTokenHash("a".repeat(64));
        row.setStatus(status);
        return row;
    }

    /** 十六种组合里合法转移恰好三条: CREATED → 三个终态中的每一个。 */
    @Test
    void exactlyThreeOfTheSixteenPairsAreLegal() {
        String[] states = {SessionInvitationRecord.STATUS_CREATED,
                SessionInvitationRecord.STATUS_CONSUMED,
                SessionInvitationRecord.STATUS_EXPIRED,
                SessionInvitationRecord.STATUS_REVOKED};
        java.util.List<String> legal = new java.util.ArrayList<>();
        for (String from : states) {
            for (String to : states) {
                if (machine.canTransition(from, to)) {
                    legal.add(from + "→" + to);
                }
            }
        }
        assertEquals(java.util.List.of("CREATED→CONSUMED", "CREATED→EXPIRED", "CREATED→REVOKED"),
                legal);
    }

    /** 三个终态是终点: 一张票只有一种死法。 */
    @Test
    void consumedExpiredAndRevokedAreTerminal() {
        for (String terminal : new String[]{SessionInvitationRecord.STATUS_CONSUMED,
                SessionInvitationRecord.STATUS_EXPIRED,
                SessionInvitationRecord.STATUS_REVOKED}) {
            for (String to : new String[]{SessionInvitationRecord.STATUS_CREATED,
                    SessionInvitationRecord.STATUS_CONSUMED,
                    SessionInvitationRecord.STATUS_EXPIRED,
                    SessionInvitationRecord.STATUS_REVOKED}) {
                assertFalse(machine.canTransition(terminal, to), terminal + " 不该能到 " + to);
            }
        }
    }

    /** 认不出来的状态不是状态。 */
    @Test
    void anUnknownStatusIsNotAState() {
        assertFalse(machine.known("BURNED"));
        assertFalse(machine.known(null));
        assertFalse(machine.canTransition(SessionInvitationRecord.STATUS_CREATED, "BURNED"));
        assertFalse(machine.canTransition("BURNED", SessionInvitationRecord.STATUS_CONSUMED));
    }

    /** 非法转移抛 {@code ILLEGAL_INVITATION_TRANSITION} + STATE_CONFLICT。 */
    @Test
    void anIllegalTransitionIsAStateConflict() {
        SessionException e = assertThrows(SessionException.class,
                () -> machine.require(SessionInvitationRecord.STATUS_CONSUMED,
                        SessionInvitationRecord.STATUS_CREATED));
        assertEquals("ILLEGAL_INVITATION_TRANSITION", e.code());
        assertEquals(com.luxera.companion.contracts.application.ActionStatus.STATE_CONFLICT, e.status());
    }

    /** {@code transition} 幂等: 已经是目标态的票原样返回, 不落库、不炸。 */
    @Test
    void transitioningToTheCurrentStateIsANoOp() {
        SessionInvitationRecord row = sheet(SessionInvitationRecord.STATUS_CREATED);
        assertEquals(row, machine.transition(row, SessionInvitationRecord.STATUS_CREATED));
        assertEquals(SessionInvitationRecord.STATUS_CREATED, row.getStatus());
    }

    /** 铸票的明文是 base64url 无填充: 30 个字符, 全在 url-safe 字符集里, 库里只留 64 字符哈希。 */
    @Test
    void mintedTokensAreUrlSafeAndOnlyTheHashIsStored() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessions.launch("com.luxera.tictactoe", alice);
        final String[] token = new String[1];
        SessionInvitationRecord row = invitations.mint(session.getId(), alice,
                null, null, null, null, null, null, t -> token[0] = t);

        assertNotNull(token[0]);
        assertTrue(token[0].matches("[A-Za-z0-9_-]{30}"), "token 应是 30 个 url-safe 字符: " + token[0]);
        assertEquals(64, row.getTokenHash().length(), "库里的 token_hash 是 64 字符 SHA-256");
        // 明文不进库: 行上的 hash 与 token 之间只能由单向哈希联系起来, 反推不回明文。
        assertFalse(token[0].equals(row.getTokenHash()), "明文不该原样躺在 token_hash 列里");
    }

    private static ResolvedPrincipal human() {
        String id = "p-" + UUID.randomUUID();
        return new ResolvedPrincipal(PrincipalType.HUMAN, id, null, id, null,
                UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
    }
}