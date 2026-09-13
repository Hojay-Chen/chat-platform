package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v1 §Manifest: <b>发布之后 manifest 不可变</b> —— 而这条保证只有在"写入口只有一个"时
 * 才成立。
 *
 * <p>所以这里不去构造一个人为的已发布版本, 而是直接对着<em>随二进制发出去的那个</em>
 * {@code com.luxera.tictactoe@1.0.0} 写: 它启动时就被同步成 PUBLISHED, 正是现实中要挡住的那一行。
 * 一个只为测试造出来的已发布行证明不了这件事 —— 真实的行是启动同步写的, 而启动同步
 * ({@code ManifestCatalogueSync}) 走的是另一条豁免路径, 两者必须都真实存在。
 *
 * <p>校验顺序也是断言的一部分: <b>先看状态, 再解析 JSON</b>。否则一个针对已发布版本的写请求
 * 会先把 manifest 解析一遍, 于是"被拒绝"和"你的 JSON 有问题"这两种完全不同的结论会按内容
 * 随机出现 —— 而调用方需要知道的只有第一件事。
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class VersionImmutabilityTest {

    private static final String APP = "com.luxera.tictactoe";
    private static final String VERSION = "1.0.0";

    @Autowired
    ApplicationVersionService versionService;

    // ─────────────────────────── 拒绝 ───────────────────────────

    @Test
    void aPublishedVersionRefusesANewManifest() {
        SessionException e = assertThrows(SessionException.class, () -> versionService.saveManifest(
                APP, VERSION, manifestOf(APP, VERSION, "被改过的描述")));

        assertEquals("VERSION_IMMUTABLE", e.code());
        assertEquals(ActionStatus.STATE_CONFLICT, e.status(),
                "409 而不是 400: 请求没写错, 是这一版不该再改了");
        assertTrue(e.getMessage().contains("要改就发新版本"),
                "错误信息要指路, 而不是只说不行: " + e.getMessage());
    }

    /** 状态检查排在解析之前: 一个连 JSON 都不是的载荷, 在已发布版本上也必须是 VERSION_IMMUTABLE。 */
    @Test
    void theStateCheckComesBeforeTheParse() {
        SessionException e = assertThrows(SessionException.class,
                () -> versionService.saveManifest(APP, VERSION, "{ 这不是 JSON"));

        assertEquals("VERSION_IMMUTABLE", e.code());
    }

    /**
     * 冻结的是"这一版"。发一版新的仍然可以写 —— 不可变性不是"这个应用不能再改",
     * 而是"历史版本永远可解释"。
     */
    @Test
    void aNewVersionIsStillWritableAfterTheOldOneIsFrozen() {
        String next = "1.0.1-" + System.nanoTime();
        ApplicationVersionRecord saved = versionService.saveManifest(APP, next,
                manifestOf(APP, next, "下一版的描述"));

        assertEquals(ApplicationStatus.DRAFT.name(), saved.getStatus(),
                "新版本从 DRAFT 开始, 还没上架");
        assertNotEquals(null, saved.getManifestHash());
    }

    // ─────────────────────────── 其它拒绝路径 ───────────────────────────

    @Test
    void anUnknownApplicationIsRejectedBeforeAnythingElse() {
        SessionException e = assertThrows(SessionException.class, () -> versionService.saveManifest(
                "com.luxera.nope", "1.0.0", manifestOf("com.luxera.nope", "1.0.0", "x")));

        assertEquals("UNKNOWN_APPLICATION", e.code());
        assertEquals(ActionStatus.NOT_FOUND, e.status());
    }

    /** 路径里的 id 与 manifest 里写的不一致 —— 两份身份必须有一个说了算, 而这里选择直接拒绝。 */
    @Test
    void thePathAndTheManifestMustAgreeOnIdentity() {
        String next = "1.0.2-" + System.nanoTime();
        SessionException e = assertThrows(SessionException.class, () -> versionService.saveManifest(
                APP, next, manifestOf("com.luxera.gomoku", next, "张冠李戴")));

        assertEquals("MANIFEST_IDENTITY_MISMATCH", e.code());
        assertEquals(ActionStatus.INVALID_ARGUMENT, e.status());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 一份形状合法、只有 description 不同的 manifest —— 它过得了解析与校验, 只过不了状态检查。 */
    private static String manifestOf(String applicationId, String version, String description) {
        return """
                {
                  "identity": {
                    "id": "%s",
                    "name": "版本不可变测试",
                    "version": "%s",
                    "description": "%s",
                    "category": "test"
                  },
                  "capabilities": [
                    { "id": "test.immutability", "title": "不可变性", "description": "只有测试会装它" }
                  ],
                  "actions": [
                    {
                      "id": "test.noop",
                      "capability": "test.immutability",
                      "title": "什么都不做",
                      "description": "只为让这份 manifest 通过校验",
                      "permission": "READ",
                      "risk": "NONE",
                      "attention": "NONE"
                    }
                  ],
                  "resources": [],
                  "events": [],
                  "permissions": [
                    { "capability": "test.immutability", "level": "READ", "riskCeiling": "NONE" }
                  ],
                  "runtime": { "type": "NATIVE" }
                }
                """.formatted(applicationId, version, description);
    }
}
