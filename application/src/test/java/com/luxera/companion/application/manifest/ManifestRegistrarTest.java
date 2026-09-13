package com.luxera.companion.application.manifest;

import com.luxera.companion.application.action.ActionHandlerKey;
import com.luxera.companion.application.action.ActionHandlerRegistry;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.action.PendingActionRegistry;
import com.luxera.companion.application.builtin.tictactoe.TicTacToeApplication;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.repository.CapabilityRepository;
import com.luxera.companion.application.spi.LapApplicationModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布期的那道闸: <b>manifest 里声明的每一个动作, 都必须能解析到这个版本自己的处理器。</b>
 *
 * <p>为什么值得单独一个类: 这条校验是"应用能不能被调用"与"应用会不会跑错代码"之间的分界。
 * 少了它, 一个写错 action id 的应用会安安稳稳地发布成功, 然后在用户点下去的那一刻抛 500 ——
 * 而发布期它本可以立刻报出来。
 *
 * <p>三件事必须成立, 缺一不可:
 *
 * <ol>
 *   <li><b>缺处理器 → 拒绝发布</b>, 且拒绝得干净: 注册表里不留半成品, 能力目录也不该多出一行。</li>
 *   <li><b>处理器键必须含版本</b> —— 只按 {@code (应用, 动作)} 找的话, 1.0.0 的 manifest 会
 *       匹配上 2.0.0 的代码, 这是"新版应用跑旧版逻辑"的经典写法。</li>
 *   <li><b>两个应用可以拥有同名动作而不互相覆盖</b>。井字棋与五子棋都会有 {@code game.make_move};
 *       用 {@code Map<String, ActionHandler>} 就会让后注册的悄悄吃掉先注册的。夹具应用在测试里
 *       扮演的就是第二个对局应用这个角色(真的五子棋在 R5 落地, 形状与它一致)。</li>
 * </ol>
 *
 * <p>发布是要往注册表里写东西的, 而注册表是单例 —— 于是每个用例都自己造一套
 * ({@link #freshRegistries()}), 而不是共用 Spring 那一套。这样用例之间互不影响, 也不必为了
 * 隔离去反复重建整个上下文。启动期那一份仍然用真实 Bean, 由
 * {@link #theBuiltInTicTacToeApplicationIsRegisteredAtStartup()} 单独验证。
 */
@ActiveProfiles("test")
@SpringBootTest
class ManifestRegistrarTest {

    private static final String TICTACTOE = "com.luxera.tictactoe";
    private static final String FIXTURE = "com.luxera.fixture.board";
    private static final String HANDLERLESS = "com.luxera.fixture.handlerless";
    private static final String WRONG_VERSION = "com.luxera.fixture.wrongversion";
    private static final String V1 = "1.0.0";

    @Autowired
    ManifestParser parser;

    @Autowired
    ManifestValidator validator;

    @Autowired
    ManifestCatalogueSync catalogue;

    /** 启动期那一份, 只读地用来验证内置应用确实注册好了。 */
    @Autowired
    ManifestRegistry startupRegistry;

    @Autowired
    ActionHandlerRegistry startupHandlers;

    @Autowired
    ApplicationRepository applications;

    @Autowired
    ApplicationVersionRepository versions;

    @Autowired
    CapabilityRepository capabilities;

    /** 内置应用也是普通模块。每个用例的"干净世界"里都先装上它, 才像真实的样子。 */
    @Autowired
    TicTacToeApplication ticTacToe;

    private ManifestRegistry registry;
    private ActionHandlerRegistry handlers;
    private ManifestRegistrar registrar;

    @BeforeEach
    void freshRegistries() {
        registry = new ManifestRegistry();
        handlers = new ActionHandlerRegistry();
        registrar = new ManifestRegistrar(parser, validator, registry, handlers,
                new PendingActionRegistry(), catalogue, new DefaultResourceLoader(), List.of());
        registrar.register(ticTacToe);
    }

    // ─────────────────────────── 启动时的内置应用 ───────────────────────────

    @Test
    void theBuiltInTicTacToeApplicationIsRegisteredAtStartup() {
        ApplicationManifest manifest = startupRegistry.published(TICTACTOE)
                .orElseThrow(() -> new AssertionError("内置应用应当在启动时注册好"));

        assertEquals(4, manifest.actions().size());
        for (ApplicationManifest.ActionDecl action : manifest.actions()) {
            assertTrue(startupHandlers.contains(TICTACTOE, V1, action.id()),
                    "manifest 声明了 " + action.id() + ", 就必须有对应的处理器");
        }
    }

    // ─────────────────────────── 发布期的处理器校验 ───────────────────────────

    @Test
    void everyDeclaredActionResolvesToAHandlerForItsOwnVersion() {
        ApplicationManifest manifest = registrar.register(new FixtureModule(true));

        assertEquals(FIXTURE, manifest.applicationId());
        for (ApplicationManifest.ActionDecl action : manifest.actions()) {
            assertTrue(handlers.contains(FIXTURE, V1, action.id()),
                    "每个声明的动作都该有自己的处理器: " + action.id());
        }
    }

    /**
     * 发布失败必须是<b>原子</b>的: 不能出现"注册表里已经有这个应用了, 但它有个动作没人实现"。
     * 那种半成品比彻底失败更难查。
     */
    @Test
    void aManifestDeclaringAnActionWithoutAHandlerRefusesToPublishAndLeavesNothingBehind() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registrar.register(new HandlerlessModule()));

        assertTrue(e.getMessage().contains("ACTION_HANDLER_MISSING"),
                "报错要点明是缺处理器: " + e.getMessage());
        assertTrue(e.getMessage().contains("board.explode"),
                "还要说清是哪个动作: " + e.getMessage());

        assertTrue(registry.find(HANDLERLESS, V1).isEmpty(), "发布失败的应用不该留在注册表里");
        assertTrue(capabilities.findById("board.weird").isEmpty(),
                "校验没过的应用不该顺带把能力写进目录 —— 那会让它出现在别人都看得见的发现链里");
    }

    /** 处理器键里的<b>版本</b>维度: 挂在 2.0.0 上的实现不满足 1.0.0 的 manifest。 */
    @Test
    void aHandlerRegisteredUnderAnotherVersionDoesNotSatisfyTheManifest() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registrar.register(new WrongVersionModule()));

        assertTrue(e.getMessage().contains("ACTION_HANDLER_MISSING"), e.getMessage());
        assertTrue(handlers.contains(WRONG_VERSION, "2.0.0", "board.place"),
                "夹具确实把处理器注册上了 —— 只是注册在了另一个版本下面");
        assertFalse(handlers.contains(WRONG_VERSION, V1, "board.place"));
    }

    // ─────────────────────────── 版本不可重复注册 ───────────────────────────

    /**
     * 同一个 {@code (应用, 版本)} 只能发布一次。处理器键已经保证"同一把键不会有两个实现",
     * 这里保证的是"同一份 manifest 不会有两个副本" —— 后者会让发现链拿到哪一份变成偶然。
     */
    @Test
    void registeringTheSameApplicationVersionTwiceIsRefused() {
        registrar.register(new FixtureModule(true));

        // 第二个模块走同一份 manifest, 但不再重复注册处理器(那会先撞上处理器重复注册的报错),
        // 于是这里测到的正是注册表自己的那一道。
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registrar.register(new FixtureModule(false)));

        assertTrue(e.getMessage().contains("manifest 重复注册"), e.getMessage());
    }

    // ─────────────────────────── 同名动作并存 ───────────────────────────

    /**
     * 井字棋与第二个对局应用都有 {@code game.make_move} —— 两份实现必须并存。
     * 这条断言就是 {@code ActionHandlerKey(applicationId, version, actionId)} 这个设计的保险丝:
     * 换回 {@code Map<String, ActionHandler>} 时它会立刻红。
     */
    @Test
    void twoApplicationsCanOwnTheSameActionIdWithoutOverwritingEachOther() {
        registrar.register(new FixtureModule(true));

        var mine = handlers.find(TICTACTOE, V1, "game.make_move").orElseThrow();
        var theirs = handlers.find(FIXTURE, V1, "game.make_move").orElseThrow();

        assertNotSame(mine, theirs, "同名动作各跑各的实现, 谁也没被覆盖");
        assertEquals(2, registry.byActionId("game.make_move").stream()
                        .filter(m -> List.of(TICTACTOE, FIXTURE).contains(m.applicationId()))
                        .count(),
                "同一个动作 id 下应当有两个应用");
    }

    // ─────────────────────────── 目录同步 ───────────────────────────

    /** 注册成功同时意味着目录里有据可查 —— 版本行、manifest 指纹、运行时类型都要落库。 */
    @Test
    void registeringAnApplicationAlsoSyncsTheCatalogue() {
        registrar.register(new FixtureModule(true));

        assertTrue(applications.findById(FIXTURE).isPresent(), "应用行应当出现");

        var version = versions.findByApplicationIdAndVersion(FIXTURE, V1)
                .orElseThrow(() -> new AssertionError("版本行应当出现"));
        assertNotNull(version.getManifestHash(), "指纹要落库 —— 不可变性靠它比对");
        assertEquals("NATIVE", version.getRuntimeType());
        assertNotNull(version.getPublishedAt());
        assertTrue(version.getManifestJson().contains("board://match/{matchId}"),
                "存的是原文, 不是解析后的对象");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /**
     * 扮演"第二个对局应用"。{@code registerHandlers=false} 的那一份用来测重复注册:
     * 它走同一份 manifest, 但不碰处理器注册表, 于是能一路走到注册表自己那道闸。
     */
    private static final class FixtureModule implements LapApplicationModule {

        private final boolean registerHandlers;

        private FixtureModule(boolean registerHandlers) {
            this.registerHandlers = registerHandlers;
        }

        @Override
        public String manifestLocation() {
            return "manifests/fixture-board.json";
        }

        @Override
        public void registerHandlers(ActionHandlerRegistry registry) {
            if (!registerHandlers) return;
            registry.register(ActionHandlerKey.of(FIXTURE, V1, "game.state"),
                    ctx -> ActionOutcome.success(null));
            registry.register(ActionHandlerKey.of(FIXTURE, V1, "game.make_move"),
                    ctx -> ActionOutcome.success(null));
        }
    }

    /** 声明了一个动作, 但一个处理器都不注册。 */
    private static final class HandlerlessModule implements LapApplicationModule {

        @Override
        public String manifestLocation() {
            return "manifests/handlerless.json";
        }

        @Override
        public void registerHandlers(ActionHandlerRegistry registry) {
            // 故意留空 —— 这正是被测的那件事
        }
    }

    /** manifest 写 1.0.0, 处理器挂在 2.0.0 上。 */
    private static final class WrongVersionModule implements LapApplicationModule {

        @Override
        public String manifestLocation() {
            return "manifests/wrong-version.json";
        }

        @Override
        public void registerHandlers(ActionHandlerRegistry registry) {
            registry.register(ActionHandlerKey.of(WRONG_VERSION, "2.0.0", "board.place"),
                    ctx -> ActionOutcome.success(null));
        }
    }
}
