package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationRecord;
import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.domain.DeveloperRecord;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.application.repository.DeveloperRepository;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v2 R14: <b>开发者 API 那一半 —— 上架之前, 得先有"东西可推"。</b>
 *
 * <p>R8 给了推状态机与写 manifest 两个入口, 但一个第三方从零开始要走的是五步:
 * 建开发者 → 认领应用 id → 写 manifest → 提审 → 发布。这里钉的是前三步与它们各自的拒绝分支;
 * 后两步的端到端(发现链上真的出现/消失)由 {@code check-lap.sh} 断言 16 守着, 不在这里重复。
 *
 * <p>这几条断言的分量都在<b>拒绝分支</b>上 —— 正常路径谁都能写对, 而:
 * <ul>
 *   <li>{@code APPLICATION_TAKEN} 守的是"id 即身份"那件事: 世界上只有一个
 *       {@code com.luxera.tictactoe}, 它的 id 是发现链、权限判定、审计行共同依赖的锚点。
 *       认领要是能被第二次认领覆盖, 第二个人就能把第一个人的应用整个偷走 ——
 *       不用碰任何权限模型, 只要 POST 一次。</li>
 *   <li>{@code NOT_YOUR_APPLICATION} 守的是同级的隔离: 两个都持服务密钥的开发者,
 *       各自只能动自己的那几行。"平台里所有开发者共享一个命名空间"不是共享, 是漏。</li>
 *   <li>{@code DEVELOPER_SUSPENDED} 守的是"挂起是收回钥匙": 被挂起的开发者不能
 *       再建<em>新</em>应用, 但它名下既有的行一条不动 —— "谁上架了什么"是要能回答的历史问题。</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class DeveloperApiTest {

    private static final String OWNER_A = "owner-a";
    private static final String OWNER_B = "owner-b";

    @Autowired
    DeveloperService developers;

    @Autowired
    DeveloperRepository developerRows;

    @Autowired
    ApplicationRepository applicationRows;

    // ─────────────────────────── 建开发者 ───────────────────────────

    /**
     * "查无此人"是 404, 不是 400 —— 这条是 E2E 抲出来的: {@code SessionException} 那张表
     * 按 code 推状态, R14 的几个新码没进表就落到了 {@code default}(400), 而控制器当时的
     * 注释写的是 404。差别不是数字: 400 在对调用方说"你的载荷写错了", 于是门户会去改请求体,
     * 而不是去换一个 developerId。
     */
    @Test
    void anUnknownDeveloperIsNotFoundNotABadRequest() {
        SessionException e = assertThrows(SessionException.class,
                () -> developers.applicationsOf("no-such-developer"));

        assertEquals("UNKNOWN_DEVELOPER", e.code());
        assertEquals(ActionStatus.NOT_FOUND, e.status(),
                "未知开发者该 404: 请求本身没问题, 是这个人不存在");
    }

    @Test
    void creatingADeveloperIsIdempotentForTheSameOwnerAndName() {
        DeveloperRecord first = developers.createDeveloper(OWNER_A, "小林工作室");
        DeveloperRecord again = developers.createDeveloper(OWNER_A, "小林工作室");

        assertEquals(first.getId(), again.getId(),
                "门户的'再点一次注册'不该得到第二个身份 —— 幂等, 不是 500, 也不是新行");
    }

    @Test
    void onePersonMayHoldSeveralDeveloperIdentities() {
        developers.createDeveloper(OWNER_A, "小林工作室");
        DeveloperRecord personal = developers.createDeveloper(OWNER_A, "小林(个人)");

        assertEquals(2, developers.developersOf(OWNER_A).size(),
                "ownerUserId 单独成列的理由就是: 公司一个、个人一个, 两个都是他的");
        assertEquals("小林(个人)", personal.getName());
    }

    @Test
    void aDeveloperWithoutANameIsRefused() {
        SessionException e = assertThrows(SessionException.class,
                () -> developers.createDeveloper(OWNER_A, "  "));

        assertEquals("INVALID_ARGUMENT", e.code());
        assertEquals(ActionStatus.INVALID_ARGUMENT, e.status());
    }

    // ─────────────────────────── 认领应用 ───────────────────────────

    @Test
    void claimingAnApplicationIdStartsItAsADraftWithoutAVersion() {
        DeveloperRecord who = developers.createDeveloper(OWNER_A, "小林工作室");
        ApplicationRecord row = developers.createApplication(who.getId(),
                "com.example.paper-plane", "纸飞机", "game");

        assertEquals("com.example.paper-plane", row.getId(),
                "id 是开发者认领的那个(反向域名), 不是平台生成的 UUID —— 它要进 URI 与 LLM 上下文");
        assertEquals("DRAFT", row.getStatus(), "一个还没有 manifest 的应用什么都不是, DRAFT 就是那个'什么都不是'");
        assertEquals(who.getId(), row.getDeveloperId());
        assertTrue(developers.applicationsOf(who.getId()).stream()
                .anyMatch(app -> "com.example.paper-plane".equals(app.getId())));
    }

    @Test
    void aClaimedIdBelongsToItsFirstClaimant() {
        DeveloperRecord a = developers.createDeveloper(OWNER_A, "小林工作室");
        DeveloperRecord b = developers.createDeveloper(OWNER_B, "远方游戏");
        developers.createApplication(a.getId(), "com.example.paper-plane", "纸飞机", "game");

        SessionException e = assertThrows(SessionException.class,
                () -> developers.createApplication(b.getId(), "com.example.paper-plane", "我的纸飞机", "game"));

        assertEquals("APPLICATION_TAKEN", e.code(),
                "第二次认领必须被拒 —— 放过去的话, 第二个人不用碰任何权限模型就偷走了第一个人的应用");
        assertEquals(ActionStatus.STATE_CONFLICT, e.status(), "409: 请求没写错, 是这个 id 已有主人");
    }

    @Test
    void aSuspendedDeveloperCannotClaimAnythingNew() {
        DeveloperRecord who = developers.createDeveloper(OWNER_A, "小林工作室");
        developers.suspend(who.getId());

        SessionException e = assertThrows(SessionException.class,
                () -> developers.createApplication(who.getId(), "com.example.next", "下一个", "game"));

        assertEquals("DEVELOPER_SUSPENDED", e.code(),
                "挂起是收回钥匙: 不能再建新应用, 但名下既有的行不动(下一条正是它)");
    }

    @Test
    void suspensionKeepsWhatWasAlreadyClaimed() {
        DeveloperRecord who = developers.createDeveloper(OWNER_A, "小林工作室");
        developers.createApplication(who.getId(), "com.example.paper-plane", "纸飞机", "game");
        developers.suspend(who.getId());

        List<ApplicationRecord> stillThere = developers.applicationsOf(who.getId());
        assertEquals(1, stillThere.size(),
                "'谁上架了什么'是要能回答的历史问题 —— 挂起开发者不是删数据");
    }

    // ─────────────────────────── 归属隔离 ───────────────────────────

    @Test
    void anotherDeveloperCannotTouchWhatTheyDoNotOwn() {
        DeveloperRecord a = developers.createDeveloper(OWNER_A, "小林工作室");
        DeveloperRecord b = developers.createDeveloper(OWNER_B, "远方游戏");
        developers.createApplication(a.getId(), "com.example.paper-plane", "纸飞机", "game");

        SessionException e = assertThrows(SessionException.class,
                () -> developers.requireOwned("com.example.paper-plane", b.getId()));

        assertEquals("NOT_YOUR_APPLICATION", e.code(),
                "两个都持服务密钥的开发者, 各自只能动自己的那几行");
        assertEquals(ActionStatus.DENIED, e.status());
    }

    @Test
    void thePlatformIsNeverLockedOutOfItsOwnApplications() {
        DeveloperRecord a = developers.createDeveloper(OWNER_A, "小林工作室");
        developers.createApplication(a.getId(), "com.example.paper-plane", "纸飞机", "game");

        ApplicationRecord row = developers.requireOwned("com.example.paper-plane", null);

        assertNotNull(row, "actorDeveloperId == null 表示平台自己: 挂起一个违规应用是运营动作, 不该被归属模型挡住");
    }

    @Test
    void ownershipAnswersBeforeTheRowIsLookedAtAgain() {
        DeveloperRecord a = developers.createDeveloper(OWNER_A, "小林工作室");
        developers.createApplication(a.getId(), "com.example.paper-plane", "纸飞机", "game");

        assertEquals(ApplicationStatus.DRAFT.name(),
                developers.requireOwned("com.example.paper-plane", a.getId()).getStatus());
        assertEquals("UNKNOWN_APPLICATION",
                assertThrows(SessionException.class,
                        () -> developers.requireOwned("com.example.nope", a.getId())).code());
    }

    // ─────────────────────────── 清理帮手 ───────────────────────────

    /**
     * 测试夹具的应用/开发者行落在真实共享库里({@code ddl-auto=update} 的库), 而本类是
     * {@code @Transactional} —— 回滚会把它们一起带走, 所以不需要手工清理。这条注释存在
     * 是因为下一个往这里加测试的人会问同一件事: "这些 com.example.* 的行会留下来吗?" 不会。
     */
    @Test
    void theFixtureDoesNotNeedManualCleanup() {
        // 意在言外: 上面每条用例的事务在测试结束时回滚, com.example.paper-plane 不会
        // 出现在下一个跑 check-lap.sh 的环境里 —— 那边断言 3 的"候选应用 ≥ 2"数的就是真实应用。
        DeveloperRecord who = developers.createDeveloper(OWNER_A, "小林工作室");
        developers.createApplication(who.getId(), "com.example.transient", "一闪而过", "game");
        assertTrue(developers.applicationsOf(who.getId()).stream()
                .anyMatch(app -> "com.example.transient".equals(app.getId())));
    }
}
