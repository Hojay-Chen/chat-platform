package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v1 §Session: <b>空闲会话回收器只结束, 从不删除。</b>
 *
 * <p>这条区别不是措辞上的: 一个被结束的会话仍然解释得通 —— 它还记得是谁开的、开的哪一版、
 * 当时有谁在场 —— 而一条被删掉的会话会让它名下所有 {@code action_invocation} 变成
 * 查不到上下文的孤儿。回收的目的是把"还开着"这件事收敛掉, 不是抹掉历史。
 *
 * <p>阈值与 {@code ActionInvocationReaperJob} 差着三个数量级(60 秒 vs 7 天), 因为两件事问的
 * 是不同的问题: "这次调用还活着吗"与"这个人还在玩吗"。这里断言的是后者 —— 一个一小时前
 * 动过的会话必须活下来, 否则回收器会变成"数字人玩到一半被踢出去"。
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class SessionReaperTest {

    private static final String APP = "com.luxera.tictactoe";
    private static final long SEVEN_DAYS = 168;

    @Autowired
    ApplicationSessionService sessions;

    @Autowired
    ApplicationSessionRepository repository;

    @Autowired
    SessionReaperJob job;

    @Test
    void aSessionQuietForLongerThanTheThresholdIsEndedButStillReadable() {
        ApplicationSessionRecord session = openSession();
        backdate(session, SEVEN_DAYS + 24);

        assertEquals(1, sessions.reapIdle(SEVEN_DAYS));

        ApplicationSessionRecord ended = repository.findById(session.getId()).orElseThrow();
        assertEquals(ApplicationSessionRecord.STATUS_ENDED, ended.getStatus());
        assertEquals(session.getOwnerPrincipalId(), ended.getOwnerPrincipalId(),
                "结束不是删除: 谁开的、开的哪一版, 都还查得到");
        assertTrue(ended.getCreatedAt() != null);
    }

    /** 一小时前动过的会话必须活下来 —— 否则回收器就是"玩到一半被踢"。 */
    @Test
    void aRecentlyActiveSessionSurvives() {
        ApplicationSessionRecord session = openSession();
        backdate(session, 1);

        assertEquals(0, sessions.reapIdle(SEVEN_DAYS));

        assertEquals(ApplicationSessionRecord.STATUS_ACTIVE,
                repository.findById(session.getId()).orElseThrow().getStatus());
    }

    /** 已经被结束的会话不该被重复计数 —— 回收是幂等的, 统计才有意义。 */
    @Test
    void anAlreadyEndedSessionIsNotCountedAgain() {
        ApplicationSessionRecord session = openSession();
        backdate(session, SEVEN_DAYS + 24);

        assertEquals(1, sessions.reapIdle(SEVEN_DAYS));
        assertEquals(0, sessions.reapIdle(SEVEN_DAYS));
    }

    /** 阈值为 0 或负数 = 关闭回收, 而不是"回收所有会话" —— 后者是最坏的读法。 */
    @Test
    void aNonPositiveThresholdDisablesReaping() {
        ApplicationSessionRecord session = openSession();
        backdate(session, SEVEN_DAYS + 24);

        assertEquals(0, sessions.reapIdle(0));
        assertEquals(0, sessions.reapIdle(-1));

        assertEquals(ApplicationSessionRecord.STATUS_ACTIVE,
                repository.findById(session.getId()).orElseThrow().getStatus());
    }

    /** 定时任务包着同一段逻辑; 它的阈值来自配置, 而配置只能通过这个入口被问到。 */
    @Test
    void theJobExposesItsConfiguredThreshold() {
        assertEquals(SEVEN_DAYS, job.idleHours());

        ApplicationSessionRecord session = openSession();
        backdate(session, SEVEN_DAYS + 1);
        job.reap();

        assertEquals(ApplicationSessionRecord.STATUS_ENDED,
                repository.findById(session.getId()).orElseThrow().getStatus());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private ApplicationSessionRecord openSession() {
        ResolvedPrincipal human = new ResolvedPrincipal(PrincipalType.HUMAN,
                "user-" + UUID.randomUUID(), null, "user-" + UUID.randomUUID(), null,
                "corr-" + UUID.randomUUID(), ResolvedPrincipal.SOURCE_JWT);
        return sessions.launch(APP, human);
    }

    /** 把 lastActiveAt 往前拨 —— 等 7 天是测不了的, 而"这行看起来很久没动了"是可以造的。 */
    private void backdate(ApplicationSessionRecord session, long hoursAgo) {
        session.setLastActiveAt(LocalDateTime.now().minusHours(hoursAgo));
        repository.save(session);
    }
}
