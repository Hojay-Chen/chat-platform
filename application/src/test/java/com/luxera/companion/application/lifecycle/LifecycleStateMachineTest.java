package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationRecord;
import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v1 §Lifecycle: 状态机是<em>真的会生效</em>, 而不只是一个字段。
 *
 * <p>这一条断言是整个类里最要紧的: 一个从不被读的状态字段与一个不存在的状态字段没有任何区别。
 * 所以这里不只断言"transition 把 status 改了", 还断言<em>改了之后发现链上就查不到这个应用了</em>
 * —— 后者才是"挂起"这件事对使用者意味着什么。
 *
 * <p>测试整体包在一个会回滚的事务里, 所以四个种子应用的状态在用例之间不会互相污染, 也不会
 * 污染数据库。这一点不是洁癖: 一个把 {@code com.luxera.tictactoe} 留在 SUSPENDED 的测试,
 * 会让下一个跑在同一个库上的测试类失败, 而失败原因看起来会像是那个类的 bug。
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class LifecycleStateMachineTest {

    private static final String APP = "com.luxera.tictactoe";

    @Autowired
    ApplicationLifecycleService lifecycle;

    @Autowired
    ApplicationCatalogue catalogue;

    @Autowired
    ApplicationRepository applications;

    @Autowired
    ApplicationVersionRepository versions;

    // ─────────────────────────── 正常路径 ───────────────────────────

    @Test
    void suspendingTakesTheApplicationOffTheDiscoveryChainAndPublishingPutsItBack() {
        assertTrue(catalogue.isDiscoverable(APP), "前置: 种子应用启动时被同步为 PUBLISHED");
        assertTrue(catalogue.discoverable().stream()
                .anyMatch(m -> APP.equals(m.applicationId())));

        lifecycle.transition(APP, ApplicationStatus.SUSPENDED, platform());

        assertFalse(catalogue.isDiscoverable(APP), "挂起之后不在发现链上");
        assertTrue(catalogue.discoverable(APP).isEmpty(), "按 id 也查不到 —— 两条路必须同一个答案");
        assertTrue(catalogue.discoverable().stream()
                        .noneMatch(m -> APP.equals(m.applicationId())),
                "能力/应用列表里也不该再出现它");

        lifecycle.transition(APP, ApplicationStatus.PUBLISHED, platform());

        assertTrue(catalogue.isDiscoverable(APP), "回到 PUBLISHED 就该重新出现");
        assertTrue(catalogue.discoverable(APP).isPresent());
    }

    /**
     * 应用的状态与它的版本行必须一起动。否则会出现"应用已停用、版本仍在架上"这种谁也不知道
     * 该信哪一份的状态 —— 而发现链读的是版本行。
     */
    @Test
    void theVersionRowsMoveWithTheApplication() {
        lifecycle.transition(APP, ApplicationStatus.SUSPENDED, platform());

        List<ApplicationVersionRecord> rows = versions.findByApplicationIdOrderByCreatedAtDesc(APP);
        assertFalse(rows.isEmpty(), "前置: 种子应用有版本行");
        for (ApplicationVersionRecord row : rows) {
            assertFalse(ApplicationStatus.PUBLISHED.name().equals(row.getStatus()),
                    "挂起一个应用必须把它的版本一起撤下: " + row.getVersion());
        }

        lifecycle.transition(APP, ApplicationStatus.PUBLISHED, platform());

        ApplicationVersionRecord latest = versions
                .findByApplicationIdAndVersion(APP, "1.0.0").orElseThrow();
        assertEquals(ApplicationStatus.PUBLISHED.name(), latest.getStatus(),
                "latestVersion 指的那一版回到架上");
    }

    /** 重发一次 PATCH 不该报错 —— 否则调用方为了重试要先读一次, 而读与写之间有竞态。 */
    @Test
    void movingToTheStateItIsAlreadyInIsIdempotent() {
        ApplicationRecord record = lifecycle.transition(APP, ApplicationStatus.PUBLISHED, platform());

        assertEquals(ApplicationStatus.PUBLISHED.name(), record.getStatus());
    }

    // ─────────────────────────── 拒绝的路径 ───────────────────────────

    /** 从 PUBLISHED 只能挂起或废弃, 不能倒退回去接着开发 —— 那会让已发布版本重新可变。 */
    @Test
    void skippingAheadIsRejectedAsAStateConflict() {
        SessionException e = assertThrows(SessionException.class,
                () -> lifecycle.transition(APP, ApplicationStatus.DEVELOPING, platform()));

        assertEquals("ILLEGAL_TRANSITION", e.code());
        assertEquals(ActionStatus.STATE_CONFLICT, e.status(), "请求没写错, 是当前状态不允许");
        assertTrue(e.getMessage().contains("SUSPENDED"), "错误信息要说得出合法后继: " + e.getMessage());
    }

    /** {@code DEPRECATED} 是终态: 没有任何后继。 */
    @Test
    void deprecatedIsTerminal() {
        lifecycle.transition(APP, ApplicationStatus.DEPRECATED, platform());

        assertTrue(ApplicationStatus.legalSuccessorsOf(ApplicationStatus.DEPRECATED).isEmpty());
        for (ApplicationStatus target : ApplicationStatus.values()) {
            if (target == ApplicationStatus.DEPRECATED) {
                continue;   // 同状态是幂等的, 见 movingToTheStateItIsAlreadyInIsIdempotent
            }
            assertThrows(SessionException.class,
                    () -> lifecycle.transition(APP, target, platform()),
                    "DEPRECATED 之后不该还能走到 " + target);
        }
    }

    /**
     * 上架/下架不是使用者能决定的事。真人拿 JWT 也能到达这个端点, 但会被 403 挡下 ——
     * 而不是被"反正他也会点对"放过去。
     */
    @Test
    void aHumanCannotDriveTheLifecycle() {
        SessionException e = assertThrows(SessionException.class,
                () -> lifecycle.transition(APP, ApplicationStatus.SUSPENDED, human()));

        assertEquals("LIFECYCLE_FORBIDDEN", e.code());
        assertEquals(ActionStatus.DENIED, e.status());
        assertEquals(ApplicationStatus.PUBLISHED,
                lifecycle.statusOf(APP), "被拒绝的请求不能留下任何痕迹");
    }

    /** 一个不存在的应用与一个非法目标状态是两件事, 不该共用一个错误码。 */
    @Test
    void anUnknownApplicationIsNotFoundRatherThanForbidden() {
        SessionException e = assertThrows(SessionException.class, () -> lifecycle.transition(
                "com.luxera.nope", ApplicationStatus.SUSPENDED, platform()));

        assertEquals("UNKNOWN_APPLICATION", e.code());
        assertEquals(ActionStatus.NOT_FOUND, e.status());
    }

    @Test
    void aMissingTargetIsAnInvalidArgument() {
        SessionException e = assertThrows(SessionException.class,
                () -> lifecycle.transition(APP, null, platform()));

        assertEquals("INVALID_TRANSITION", e.code());
        assertEquals(ActionStatus.INVALID_ARGUMENT, e.status());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private static ResolvedPrincipal platform() {
        return new ResolvedPrincipal(PrincipalType.SYSTEM, "lap-platform", null, null, null,
                "corr-" + UUID.randomUUID(), ResolvedPrincipal.SOURCE_INTERNAL);
    }

    private static ResolvedPrincipal human() {
        return new ResolvedPrincipal(PrincipalType.HUMAN, "user-" + UUID.randomUUID(), null,
                "user-" + UUID.randomUUID(), null, "corr-" + UUID.randomUUID(),
                ResolvedPrincipal.SOURCE_JWT);
    }
}
