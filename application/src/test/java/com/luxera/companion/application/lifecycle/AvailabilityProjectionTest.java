package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v2 §4.1: <b>那张表逐行钉住</b> —— 十态 → 五态 → 三列布尔。
 *
 * <p>这个类存在的理由只有一个, 而且它不是"覆盖率": §4.1 的表是<em>整个应用生命周期对使用者
 * 的全部含义</em>。十个状态听起来精细, 但使用者问的永远只有三个问题 —— 市场里看得见吗、
 * 能开新的吗、我手上那局还能用吗。表错一行的表现不是测试红, 而是<em>一个被下架的应用
 * 还能被人开一局新的</em>, 或者<em>一局下到一半的棋因为运营点了一下"挂起"而当场作废</em>。
 * 两者都不会抛异常, 只会有人事后觉得"怎么好像哪里不对"。
 *
 * <p>所以这里逐行断言, 而<b>不是</b>断言"PUBLISHED 是 true、其他是 false": 那种写法的信息量
 * 是零 —— 它对 {@code SUSPENDED} 和 {@code DRAFT} 说了同一句话, 而这两行的<em>区别正是
 * 这张表唯一有价值的地方</em>。
 *
 * <p>外加两条守卫:
 * <ul>
 *   <li>投影是<b>全覆盖</b>的 —— 十态里每一个都落在某一行上, 且每一行都真的有状态落进去。
 *       将来加第十一个状态时, 落到哪一行必须是一个决定, 不能是"编译器让我加了个 default"。</li>
 *   <li>投影是<b>真的接在准入上</b>的 —— {@code ApplicationSessionService.launch} 拿它当闸门。
 *       一个没人读的投影与一个写错的投影, 表现完全一样。</li>
 * </ul>
 *
 * <p>整个类包在一个事务里, 状态改动一律回滚 —— 它直接操纵种子应用的状态, 留在库里会污染
 * 后面所有类的启动前提(见 {@code LifecycleStateMachineTest} 的同一条理由)。
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class AvailabilityProjectionTest {

    private static final String APP = "com.luxera.tictactoe";

    @Autowired
    ApplicationCatalogue catalogue;

    @Autowired
    ApplicationLifecycleService lifecycle;

    @Autowired
    ApplicationSessionService sessions;

    // ─────────────────────────── §4.1 那张表, 一行一行 ───────────────────────────

    /** {@code PUBLISHED} 是唯一的"三列全 yes" —— 在架上、能开新的、已有的也能用。 */
    @Test
    void publishedIsTheOnlyRowWhereEverythingIsAllowed() {
        assertRow(ApplicationStatus.PUBLISHED, Availability.PUBLISHED, true, true, true);
    }

    /**
     * {@code SUSPENDED}: <b>下架 ≠ 作废</b>。
     *
     * <p>这是整张表里最要紧的一行。一局下到一半的棋因为应用被挂起而当场不可用, 是平台在
     * 惩罚用户承担运营的后果 —— 而用户什么都没做错。所以"禁止新会话"与"已有会话照常"必须是
     * 两个分开的答案, 不能只有一个开关。
     */
    @Test
    void suspendedStopsNewSessionsButLetsExistingOnesFinish() {
        assertRow(ApplicationStatus.SUSPENDED, Availability.SUSPENDED, false, false, true);
    }

    /**
     * {@code DEPRECATED} 与 {@code SUSPENDED} 在三列上<b>完全相同</b>。
     *
     * <p>方案 §4.1 给 DEPRECATED 的"是否允许已有 Session"写的是"视策略"。而"视策略"不是一条
     * 可实现的规定 —— 它会变成"没人知道的行为"。计划里把它明确成与 SUSPENDED 同: 不再维护,
     * 但场上那局让人下完。这条断言就是那句"明确"的落点。
     */
    @Test
    void deprecatedBehavesExactlyLikeSuspended() {
        assertRow(ApplicationStatus.DEPRECATED, Availability.DEPRECATED, false, false, true);
        assertEquals(Availability.SUSPENDED.inMarket(), Availability.DEPRECATED.inMarket());
        assertEquals(Availability.SUSPENDED.allowsNewSession(),
                Availability.DEPRECATED.allowsNewSession());
        assertEquals(Availability.SUSPENDED.allowsExistingSession(),
                Availability.DEPRECATED.allowsExistingSession());
    }

    /** 草稿、开发中、自测中: 作者手里, 什么都还没有。 */
    @Test
    void authorSideStatesAreInvisibleAndUnusable() {
        for (ApplicationStatus s : List.of(ApplicationStatus.DRAFT, ApplicationStatus.DEVELOPING,
                ApplicationStatus.TESTING)) {
            assertRow(s, Availability.DRAFT, false, false, false);
        }
    }

    /**
     * 被驳回的回到作者手里 —— 与 DRAFT 同一行。
     *
     * <p>它的<em>名字</em>丢了(投影之后说得出"不可用", 说不出"因为被驳回"), 这是投影的代价,
     * 也是为什么开发者后台读的是十态原值而不是这一层。
     */
    @Test
    void rejectedFallsBackToTheAuthorsDesk() {
        assertRow(ApplicationStatus.REJECTED, Availability.DRAFT, false, false, false);
    }

    /** 交出去在流程里的三个状态: 同样不可见不可开 —— "快发布了"对使用者不是可用的理由。 */
    @Test
    void inReviewStatesAreInvisibleAndUnusable() {
        for (ApplicationStatus s : List.of(ApplicationStatus.SUBMITTED, ApplicationStatus.REVIEWING,
                ApplicationStatus.APPROVED)) {
            assertRow(s, Availability.REVIEWING, false, false, false);
        }
    }

    // ─────────────────────────── 投影本身的两条性质 ───────────────────────────

    /**
     * <b>全覆盖</b>: 十个状态一个不漏地落在五行上, 且五行都不是空行。
     *
     * <p>"不是空行"这一半同样要紧 —— 一个永远取不到的枚举值, 会一直看起来像一条已经实现的
     * 规则。将来加第十一个状态时, 这条断言会先红, 逼作者回答"它归哪一行"。
     */
    @Test
    void theProjectionIsTotalAndEveryRowIsReachable() {
        List<ApplicationStatus> all = Arrays.asList(ApplicationStatus.values());
        assertEquals(10, all.size(), "十态是 R8 定下的, 少一个都不是投影而是替换");

        int covered = 0;
        for (Availability a : Availability.values()) {
            List<ApplicationStatus> sources = Availability.sourcesOf(a);
            assertFalse(sources.isEmpty(), a + " 这一行没有任何状态落进来 —— 它是一条空规则");
            covered += sources.size();
        }
        assertEquals(all.size(), covered, "每个状态都该恰好落在某一行上, 不多不少");
    }

    /** 五个投影值的名字与 §4.1 的表头一字不差。 */
    @Test
    void theFiveAvailabilityNamesAreExactlyTheOnesTheDesignNames() {
        assertEquals(List.of("DRAFT", "REVIEWING", "PUBLISHED", "SUSPENDED", "DEPRECATED"),
                Arrays.stream(Availability.values()).map(Enum::name).toList());
    }

    /**
     * 认不出来的状态<b>绝不能</b>被当成"在架"。
     *
     * <p>这是整张表的兜底方向: 一个空状态落成 {@code DRAFT}(什么都没有)只是少了一个应用,
     * 落成 {@code PUBLISHED} 则是一次未经审核的发布。两个方向的代价不对称, 所以判据也不对称。
     */
    @Test
    void anUnknownStatusIsNeverTreatedAsPublished() {
        assertEquals(Availability.DRAFT, Availability.of(null));
        assertFalse(Availability.of(null).inMarket());
        assertFalse(Availability.of(null).allowsNewSession());
        assertFalse(Availability.of(null).allowsExistingSession());
    }

    // ─────────────────────────── 投影接在准入上 ───────────────────────────

    /**
     * 下架之后: <b>开新会话被拒, 已有的那局照常读得出来。</b>
     *
     * <p>这一条把三类断言串成了一条链 —— 表(投影) → 目录(状态查询) → 开会话(准入) ——
     * 中间任何一环脱钩, 它都会红。这正是"一个没人读的投影与一个写错的投影表现完全一样"
     * 那句话的解法。
     */
    @Test
    void suspendingAnApplicationRefusesNewSessionsButKeepsTheExistingOne() {
        ResolvedPrincipal alice = human();
        String sessionId = sessions.launch(APP, alice).getId();

        lifecycle.transition(APP, ApplicationStatus.SUSPENDED, platform());
        assertEquals(Availability.SUSPENDED, catalogue.availabilityOf(APP));
        assertFalse(catalogue.isDiscoverable(APP), "下架的应用不在发现链上");

        // 新会话: 拒。409 而不是 404 —— "被下架了"和"没有这个应用"是两件事。
        SessionException e = assertThrows(SessionException.class, () -> sessions.launch(APP, alice));
        assertEquals("APPLICATION_NOT_AVAILABLE", e.code());
        assertEquals(ActionStatus.STATE_CONFLICT, e.status());

        // 已有的那局: 照常读得出来, 而且它还在 —— 没有被顺手结束掉。
        assertEquals(sessionId, sessions.require(sessionId).getId());
        assertTrue(catalogue.availabilityOf(APP).allowsExistingSession());
    }

    /** 恢复到架上, 同一个应用立刻又能开新会话 —— 挂起是可逆的, 不是一次单向的处决。 */
    @Test
    void publishingAgainReopensTheDoor() {
        ResolvedPrincipal alice = human();
        sessions.launch(APP, alice);

        lifecycle.transition(APP, ApplicationStatus.SUSPENDED, platform());
        assertThrows(SessionException.class, () -> sessions.launch(APP, alice));

        lifecycle.transition(APP, ApplicationStatus.PUBLISHED, platform());
        assertEquals(Availability.PUBLISHED, catalogue.availabilityOf(APP));
        assertTrue(catalogue.isDiscoverable(APP));
        sessions.launch(APP, alice);   // 不抛就是答案
    }

    /**
     * 已经走到终点的应用开不了会话 —— 而且给的是 409 而不是"没有这个应用"。
     *
     * <p>这一条曾经是<em>能开</em>的: v2 之前, {@code launch} 只问"这个 manifest 在本构建里
     * 注册过吗", 于是任何一个注册过但还没上架、甚至已经废弃的应用都能被开出会话来 ——
     * 一道谁都没注意到的后门。
     */
    @Test
    void aDeprecatedApplicationCannotBeLaunched() {
        lifecycle.transition(APP, ApplicationStatus.SUSPENDED, platform());
        lifecycle.transition(APP, ApplicationStatus.DEPRECATED, platform());

        assertEquals(Availability.DEPRECATED, catalogue.availabilityOf(APP));
        SessionException e = assertThrows(SessionException.class,
                () -> sessions.launch(APP, human()));
        assertEquals("APPLICATION_NOT_AVAILABLE", e.code());
    }

    /** 应用详情里那两栏: 十态原值给开发者后台, 五态给"能不能打开"。 */
    @Test
    void theCatalogueExposesBothTheTenStateTruthAndTheFiveStateProjection() {
        lifecycle.transition(APP, ApplicationStatus.SUSPENDED, platform());
        assertEquals(ApplicationStatus.SUSPENDED, catalogue.statusOf(APP), "十态原值一个字都不该含糊");
        assertEquals(Availability.SUSPENDED, catalogue.availabilityOf(APP));

        assertEquals(null, catalogue.statusOf("com.luxera.never-heard-of-it"),
                "没听说过的应用没有状态 —— 不该在这里编一个");
        assertEquals(Availability.PUBLISHED, catalogue.availabilityOf("com.luxera.never-heard-of-it"),
                "但行为上按在架处理: 账本缺行不等于下架(见 ApplicationCatalogue 类注释)");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private static void assertRow(ApplicationStatus status, Availability expected,
                                  boolean inMarket, boolean newSession, boolean existingSession) {
        Availability actual = Availability.of(status);
        assertEquals(expected, actual, status + " 该落在 " + expected + " 这一行");
        assertEquals(inMarket, actual.inMarket(), status + " 的『出现在应用市场』");
        assertEquals(newSession, actual.allowsNewSession(), status + " 的『允许新 Session』");
        assertEquals(existingSession, actual.allowsExistingSession(), status + " 的『允许已有 Session』");
    }

    private static ResolvedPrincipal human() {
        String id = "p-" + UUID.randomUUID();
        return new ResolvedPrincipal(PrincipalType.HUMAN, id, null, id, null,
                UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
    }

    /** 上架/下架是平台自己的权柄(生命周期状态机), 演员必须是 SYSTEM。 */
    private static ResolvedPrincipal platform() {
        return new ResolvedPrincipal(PrincipalType.SYSTEM, "lap-platform", null, null, null,
                "corr-" + UUID.randomUUID(), ResolvedPrincipal.SOURCE_INTERNAL);
    }
}
