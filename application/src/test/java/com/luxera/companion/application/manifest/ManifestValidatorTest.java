package com.luxera.companion.application.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * manifest 是这套平台的<em>契约本身</em> —— 发现链、权限、幂等、事件全从它派生。校验器漏一条,
 * 后果不是"数据脏了", 而是某个应用在运行时以错误的方式被调用。
 *
 * <p>这个类把八个小节逐条钉住, 重点是<em>错误码</em>而不只是"抛异常": 调用方(开发者 API、
 * 构建脚本)要靠码来决定怎么办, 只报"manifest 不合法"等于没报。
 *
 * <p>三条断言值得单独指出, 因为它们对应的是设计稿里最容易做反的地方:
 *
 * <ul>
 *   <li><b>{@code HOSTED} 有自己的错误码, 不是被静默接受。</b>"第一阶段不做 JVM sandbox /
 *       WASM"如果只写在文档里, 第一个上传代码的应用就会让它变成谎言。</li>
 *   <li><b>顶层出现未知小节一律拒绝, 包括 {@code agentEndpoint} 这类按消费者分的字段。</b>
 *       静默忽略未知字段的话, 一个作者写了 {@code humanEndpoint} 会得到"发布成功但从不生效",
 *       这是最难查的一类问题。</li>
 *   <li><b>{@code ui} 段里连一个不认识的键都拒绝(§69)。</b> 顶层多一个 section 是作者声明了
 *       一件平台做不到的事; {@code ui} 里多一个 {@code theme} / {@code layout} 是<em>平台
 *       正在变成 UI 框架</em>的开始。后面这条不会有编译错误, 只会有越来越长的参数清单。</li>
 * </ul>
 */
class ManifestValidatorTest {

    private final ManifestValidator validator = new ManifestValidator();
    private final ManifestParser parser = new ManifestParser(new ObjectMapper());

    // ─────────────────────────── 正常路径 ───────────────────────────

    @Test
    void aWellFormedManifestPasses() {
        assertDoesNotThrow(() -> validator.validate(valid()));
    }

    /** 八个 section 的名字与顺序就是契约 —— 多一个少一个都要在这里显形。 */
    @Test
    void theEightSectionsAreExactlyTheOnesTheDesignNames() {
        assertEquals(List.of("identity", "capabilities", "actions", "resources",
                "events", "permissions", "runtime", "ui"), ApplicationManifest.SECTIONS);
    }

    // ─────────────────────────── identity ───────────────────────────

    @Test
    void identityIsRequired() {
        assertCode("MANIFEST_INVALID", () -> validator.validate(withIdentity(null)));
    }

    @Test
    void identityIdMustLookLikeAReverseDomainName() {
        assertCode("INVALID_APPLICATION_ID",
                () -> validator.validate(withIdentity(
                        new ApplicationManifest.Identity("tictactoe", "井字棋", "1.0.0", null, null))));
    }

    @Test
    void identityVersionIsRequired() {
        assertCode("MANIFEST_INVALID",
                () -> validator.validate(withIdentity(
                        new ApplicationManifest.Identity("com.luxera.x", "x", "  ", null, null))));
    }

    // ─────────────────────────── capabilities ───────────────────────────

    /** 发现链的第一级是能力; 一个没有能力的应用无处可被找到。 */
    @Test
    void anApplicationWithoutCapabilitiesIsRejected() {
        assertCode("MANIFEST_INVALID", () -> validator.validate(withCapabilities(List.of())));
    }

    @Test
    void duplicateCapabilitiesAreRejected() {
        assertCode("DUPLICATE_CAPABILITY", () -> validator.validate(withCapabilities(List.of(
                capability("game.play"), capability("game.play")))));
    }

    // ─────────────────────────── actions ───────────────────────────

    @Test
    void anApplicationWithoutActionsIsRejected() {
        assertCode("MANIFEST_INVALID", () -> validator.validate(withActions(List.of())));
    }

    @Test
    void duplicateActionIdsAreRejected() {
        assertCode("DUPLICATE_ACTION", () -> validator.validate(withActions(List.of(
                action("game.state", PermissionLevel.READ, RiskLevel.NONE, null),
                action("game.state", PermissionLevel.READ, RiskLevel.NONE, null)))));
    }

    @Test
    void anActionPointingAtAnUndeclaredCapabilityIsRejected() {
        assertCode("UNKNOWN_CAPABILITY", () -> validator.validate(withActions(List.of(
                new ApplicationManifest.ActionDecl("game.make_move", "game.teleport", "瞬移", null,
                        PermissionLevel.EXECUTE, RiskLevel.LOW, AttentionPolicy.AWARE, schema(), null)))));
    }

    /**
     * 写动作必须给出对象型 {@code inputSchema} —— 它是 LLM 唯一能看到"输入长什么样"的地方,
     * 也是 MCP {@code tools/list} 唯一能转述的东西。缺了它, 动作就没法被安全地调用。
     */
    @Test
    void aWriteActionWithoutAnObjectInputSchemaIsRejected() {
        assertCode("INPUT_SCHEMA_REQUIRED", () -> validator.validate(withActions(List.of(
                action("game.make_move", PermissionLevel.EXECUTE, RiskLevel.LOW, null)))));
    }

    @Test
    void aReadActionMayOmitTheInputSchema() {
        assertDoesNotThrow(() -> validator.validate(withActions(List.of(
                action("game.state", PermissionLevel.READ, RiskLevel.NONE, null)))));
    }

    @Test
    void anActionWithoutAPermissionLevelIsRejected() {
        assertCode("MANIFEST_INVALID", () -> validator.validate(withActions(List.of(
                action("game.state", null, RiskLevel.NONE, null)))));
    }

    // ─────────────────────────── resources ───────────────────────────

    /** 网关靠 {@code uriTemplate} 判断一个 target 属于哪个应用 —— 没有它, 动作解析不了。 */
    @Test
    void aResourceWithoutAUriTemplateIsRejected() {
        assertCode("RESOURCE_URI_TEMPLATE_REQUIRED", () -> validator.validate(withResources(List.of(
                new ApplicationManifest.ResourceDecl("tictactoe.game", null,
                        ApplicationManifest.Backing.RESOURCE_STORE, null)))));
    }

    // ─────────────────────────── events ───────────────────────────

    @Test
    void duplicateEventTypesAreRejected() {
        assertCode("DUPLICATE_EVENT", () -> validator.validate(withEvents(List.of(
                new ApplicationManifest.EventDecl("game.move", "落子", true, "{uri}#MOVE-{moves}"),
                new ApplicationManifest.EventDecl("game.move", "落子再来一次", false, null)))));
    }

    /**
     * 会唤起数字人的事件必须给出确定性 id 模板。随机 id 会让数字人侧的去重失效 ——
     * 同一步棋被响应两次, 在用户看来就是"数字人自己跟自己下"。
     */
    @Test
    void anAgentTriggeringEventWithoutAnIdTemplateIsRejected() {
        assertCode("EVENT_ID_TEMPLATE_REQUIRED", () -> validator.validate(withEvents(List.of(
                new ApplicationManifest.EventDecl("game.move", "有人落子", true, null)))));
    }

    @Test
    void aNonAgentTriggeringEventNeedsNoIdTemplate() {
        assertDoesNotThrow(() -> validator.validate(withEvents(List.of(
                new ApplicationManifest.EventDecl("game.start", "开局", false, null)))));
    }

    // ─────────────────────────── permissions ───────────────────────────

    /** "没声明"不等于"默认放行" —— 应用必须逐个能力表态。 */
    @Test
    void aCapabilityWithoutAPermissionDeclarationIsRejected() {
        assertCode("PERMISSION_DECL_MISSING", () -> validator.validate(withPermissions(List.of())));
    }

    @Test
    void aPermissionDeclarationForAnUndeclaredCapabilityIsRejected() {
        assertCode("PERMISSION_DECL_UNKNOWN_CAPABILITY", () -> validator.validate(withPermissions(List.of(
                new ApplicationManifest.PermissionDecl("game.watch", PermissionLevel.READ, RiskLevel.NONE)))));
    }

    @Test
    void aPermissionDeclarationWithoutARiskCeilingIsRejected() {
        assertCode("MANIFEST_INVALID", () -> validator.validate(withPermissions(List.of(
                new ApplicationManifest.PermissionDecl("game.play", PermissionLevel.EXECUTE, null)))));
    }

    // ─────────────────────────── runtime ───────────────────────────

    @Test
    void runtimeTypeIsRequired() {
        assertCode("MANIFEST_INVALID", () -> validator.validate(
                withRuntime(new ApplicationManifest.RuntimeDecl(null, null))));
    }

    /** 平台不替应用跑代码。这条以错误码的形式钉住, 而不是只在文档里写一句。 */
    @Test
    void hostedRuntimeIsRejectedWithItsOwnCode() {
        ManifestException e = assertCode("RUNTIME_TYPE_NOT_SUPPORTED", () -> validator.validate(
                withRuntime(new ApplicationManifest.RuntimeDecl(RuntimeType.HOSTED, null))));
        assertTrue(e.getMessage().contains("HOSTED"), "报错要说清是哪个类型被拒: " + e.getMessage());
    }

    @Test
    void aRemoteRuntimeWithoutABaseUrlIsRejected() {
        assertCode("REMOTE_ENDPOINT_REQUIRED", () -> validator.validate(
                withRuntime(new ApplicationManifest.RuntimeDecl(RuntimeType.REMOTE, null))));
    }

    /** REMOTE 应用有且只有一个<b>规范</b> endpoint —— 不是按消费者各来一个。 */
    @Test
    void aRemoteRuntimeWithOneCanonicalEndpointPasses() {
        assertDoesNotThrow(() -> validator.validate(withRuntime(new ApplicationManifest.RuntimeDecl(
                RuntimeType.REMOTE,
                new ApplicationManifest.RemoteDecl("https://apps.example.com/gomoku", "gomoku-key")))));
    }

    // ─────────────────────────── 解析期拒绝 ───────────────────────────

    @Test
    void parsingRejectsUnknownSections() {
        assertCode("UNKNOWN_SECTION", () -> parser.parse("""
                {"identity":{"id":"com.luxera.x","name":"x","version":"1.0.0"},
                 "capabilities":[],"actions":[],"resources":[],"events":[],"permissions":[],
                 "runtime":{"type":"NATIVE"},
                 "somethingElse":{}}"""));
    }

    /**
     * 按消费者分的 endpoint 字段是最该被拒的一种未知 section: 它看起来"能用",
     * 于是最容易被加进来, 然后某天被当成真的生效了。
     */
    @Test
    void parsingRejectsPerConsumerEndpointFields() {
        ManifestException e = assertCode("UNKNOWN_SECTION", () -> parser.parse("""
                {"identity":{"id":"com.luxera.x","name":"x","version":"1.0.0"},
                 "agentEndpoint":"/agent","humanEndpoint":"/human",
                 "capabilities":[],"actions":[],"resources":[],"events":[],"permissions":[],
                 "runtime":{"type":"NATIVE"}}"""));
        assertTrue(e.getMessage().contains("agentEndpoint"), "报错要指名道姓: " + e.getMessage());
    }

    @Test
    void parsingRejectsAnUnknownEnumValue() {
        assertCode("MANIFEST_PARSE_ERROR", () -> parser.parse("""
                {"identity":{"id":"com.luxera.x","name":"x","version":"1.0.0"},
                 "capabilities":[],
                 "actions":[{"id":"x.y","capability":"game.play","permission":"SOMETIMES",
                             "risk":"LOW","inputSchema":{"type":"object"}}],
                 "resources":[],"events":[],"permissions":[{"capability":"game.play","level":"READ",
                 "riskCeiling":"NONE"}],"runtime":{"type":"NATIVE"}}"""));
    }

    /** 内置应用自己得先过这道关 —— 否则校验器只是"对别人严格"。 */
    @Test
    void theBuiltInTicTacToeManifestPasses() throws Exception {
        String json;
        try (var in = getClass().getResourceAsStream(
                "/applications/tictactoe/1.0.0/application-manifest.json")) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ApplicationManifest manifest = parser.parse(json);
        assertDoesNotThrow(() -> validator.validate(manifest));
        assertEquals("com.luxera.tictactoe", manifest.applicationId());
        assertEquals(4, manifest.actions().size());
    }

    /** 参考应用是"平台自己怎么用这套声明"的唯一示范, 五子棋与井字棋都得过。 */
    @Test
    void theBuiltInGomokuManifestPasses() throws Exception {
        String json;
        try (var in = getClass().getResourceAsStream(
                "/applications/gomoku/1.0.0/application-manifest.json")) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ApplicationManifest manifest = parser.parse(json);
        assertDoesNotThrow(() -> validator.validate(manifest));
        assertEquals("com.luxera.gomoku", manifest.applicationId());
    }

    // ─────────────────────────── ui (§17/§18/§67/§68/§69) ───────────────────────────

    /**
     * {@code ui} 整段可以不写 —— 一条没有自己界面的应用仍然是合法应用。
     *
     * <p>与"写了一半"的区别是全部: 不写是<em>一个决定</em>(用平台默认的全页应用),
     * 写了一半是<em>一个错误</em>(客户端拿到没有入口的声明, 只能各自发明兜底值)。
     */
    @Test
    void uiMayBeAbsentEntirely() {
        assertDoesNotThrow(() -> validator.validate(withUi(null)));
    }

    @Test
    void uiWithoutATypeIsRejected() {
        assertCode("UI_TYPE_REQUIRED", () -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(null, "/applications/x", null, List.of()))));
    }

    /** 说了"谁渲染"就得说"从哪儿进" —— NATIVE 例外, 它的界面不在网页里。 */
    @Test
    void uiWithoutAnEntryIsRejectedUnlessItIsNative() {
        assertCode("UI_ENTRY_REQUIRED", () -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.EMBEDDED, null, null,
                        List.of()))));
        assertDoesNotThrow(() -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.NATIVE, null, null,
                        List.of()))));
    }

    /**
     * REMOTE 的 entry 必须是绝对地址。这是本地最看不出来的一条: {@code /embed} 这样的相对路径
     * 会被 iframe 解释成"平台自己的某个页面", 于是第三方应用打开是平台首页, 而两边都不报错。
     */
    @Test
    void remoteUiEntryMustBeAnAbsoluteUrl() {
        assertCode("REMOTE_UI_ENTRY_NOT_ABSOLUTE", () -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.REMOTE, "/embed", null,
                        List.of()))));
        assertDoesNotThrow(() -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.REMOTE,
                        "https://app.example.com/embed", null, List.of()))));
    }

    /** NATIVE 应用声明 web surface 是自相矛盾: 它的界面根本不在网页里。 */
    @Test
    void aNativeApplicationCannotDeclareWebSurfaces() {
        assertCode("UI_SURFACE_MODE_CONFLICT", () -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.NATIVE, null, null,
                        List.of(new ApplicationManifest.SurfaceDecl(
                                ApplicationManifest.SurfaceType.FULL_PAGE, "/app"))))));
    }

    /** 同一种容器声明两遍 —— 两个入口等于没有入口, 客户端不知道该用哪个。 */
    @Test
    void duplicateSurfaceTypesAreRejected() {
        assertCode("DUPLICATE_SURFACE", () -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.EMBEDDED, "/app", null,
                        List.of(surface(ApplicationManifest.SurfaceType.FULL_PAGE, "/a"),
                                surface(ApplicationManifest.SurfaceType.FULL_PAGE, "/b"))))));
    }

    @Test
    void aSurfaceNeedsBothTypeAndEntry() {
        assertCode("SURFACE_TYPE_REQUIRED", () -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.EMBEDDED, "/app", null,
                        List.of(surface(null, "/a"))))));
        assertCode("SURFACE_ENTRY_REQUIRED", () -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.EMBEDDED, "/app", null,
                        List.of(surface(ApplicationManifest.SurfaceType.PANEL, null))))));
    }

    /** {@code minClientVersion} 是一个版本号, 不是一句"最新版"。 */
    @Test
    void minClientVersionMustLookLikeAVersion() {
        assertCode("INVALID_CLIENT_VERSION", () -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.EMBEDDED, "/app",
                        "latest", List.of()))));
        assertDoesNotThrow(() -> validator.validate(withUi(
                new ApplicationManifest.UiDecl(ApplicationManifest.UiMode.EMBEDDED, "/app",
                        "2.1", List.of()))));
    }

    /**
     * §69 是这一整段里最要紧的一条: <b>平台不认 {@code layout} / {@code theme} / {@code button}。</b>
     *
     * <p>它挡的不是一次错误, 而是一条路 —— {@code ui} 段一旦开始接受"平台认得的 UI 参数",
     * 那份清单就只会变长, 直到平台变成另一个 Flutter。所以认不出来的一律拒绝,
     * 连"忽略掉"都不允许: 忽略等于让作者以为它生效了。
     */
    @Test
    void parsingRejectsUiKeysThePlatformDoesNotUnderstand() {
        ManifestException e = assertCode("UI_UNSUPPORTED_KEY", () -> parser.parse("""
                {"identity":{"id":"com.luxera.x","name":"x","version":"1.0.0"},
                 "capabilities":[],"actions":[],"resources":[],"events":[],"permissions":[],
                 "runtime":{"type":"NATIVE"},
                 "ui":{"type":"EMBEDDED","entry":"/app","theme":"dark","layout":"grid"}}"""));
        assertTrue(e.getMessage().contains("theme"), "报错要指名道姓: " + e.getMessage());
    }

    /** surface 里也一样: 只有 {@code type} 与 {@code entry} 两件事。 */
    @Test
    void parsingRejectsSurfaceKeysThePlatformDoesNotUnderstand() {
        assertCode("UI_UNSUPPORTED_KEY", () -> parser.parse("""
                {"identity":{"id":"com.luxera.x","name":"x","version":"1.0.0"},
                 "capabilities":[],"actions":[],"resources":[],"events":[],"permissions":[],
                 "runtime":{"type":"NATIVE"},
                 "ui":{"type":"EMBEDDED","entry":"/app",
                       "surfaces":[{"type":"PANEL","entry":"/p","width":320}]}}"""));
    }

    /** 五个 surface type 与 §67 一字不差 —— 加第六种要改的是设计, 不是这里。 */
    @Test
    void theFiveSurfaceTypesAreExactlyTheOnesTheDesignNames() {
        assertEquals(List.of("FULL_PAGE", "EMBEDDED", "MODAL", "PANEL", "INLINE"),
                java.util.Arrays.stream(ApplicationManifest.SurfaceType.values())
                        .map(Enum::name).toList());
        assertEquals(List.of("EMBEDDED", "REMOTE", "NATIVE"),
                java.util.Arrays.stream(ApplicationManifest.UiMode.values())
                        .map(Enum::name).toList());
    }

    /** 一整份带 ui 的 manifest 走完整条解析 + 校验, 五个 surface 一个不少地留下来。 */
    @Test
    void aFullUiSectionSurvivesParsingAndValidation() {
        ApplicationManifest manifest = parser.parse("""
                {"identity":{"id":"com.luxera.x","name":"x","version":"1.0.0"},
                 "capabilities":[{"id":"game.play"}],
                 "actions":[{"id":"game.state","capability":"game.play","permission":"READ",
                             "risk":"NONE"}],
                 "resources":[{"type":"x.y","uriTemplate":"x://s/{sessionId}"}],
                 "events":[],"permissions":[{"capability":"game.play","level":"READ",
                 "riskCeiling":"NONE"}],"runtime":{"type":"NATIVE"},
                 "ui":{"type":"EMBEDDED","entry":"/applications/{applicationId}",
                       "minClientVersion":"1.0.0",
                       "surfaces":[{"type":"FULL_PAGE","entry":"/a"},{"type":"EMBEDDED","entry":"/b"},
                                   {"type":"MODAL","entry":"/c"},{"type":"PANEL","entry":"/d"},
                                   {"type":"INLINE","entry":"/e"}]}}""");
        assertDoesNotThrow(() -> validator.validate(manifest));
        assertEquals(5, manifest.ui().surfaces().size());
        assertEquals(ApplicationManifest.SurfaceType.INLINE,
                manifest.ui().surfaces().get(4).type());
        assertEquals("1.0.0", manifest.ui().minClientVersion());
    }

    /** 内置井字棋用的是真 ui 段(不是默认值), 五个 surface 全在。 */
    @Test
    void theBuiltInTicTacToeDeclaresAllFiveSurfaces() throws Exception {
        String json;
        try (var in = getClass().getResourceAsStream(
                "/applications/tictactoe/1.0.0/application-manifest.json")) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ApplicationManifest manifest = parser.parse(json);
        assertTrue(manifest.uiDeclaration().isPresent(), "井字棋应当自己声明 ui, 而不是吃默认值");
        assertEquals(5, manifest.ui().surfaces().size());
        assertEquals(ApplicationManifest.UiMode.EMBEDDED, manifest.ui().type());
    }

    /** 提醒没有自己的界面声明 —— 它走平台默认的那条路, 这本身要有一条断言守着。 */
    @Test
    void theBuiltInReminderHasNoUiSectionAndThatIsLegal() throws Exception {
        String json;
        try (var in = getClass().getResourceAsStream(
                "/applications/reminder/1.0.0/application-manifest.json")) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ApplicationManifest manifest = parser.parse(json);
        assertTrue(manifest.uiDeclaration().isEmpty());
        assertDoesNotThrow(() -> validator.validate(manifest));
    }

    private static ApplicationManifest.SurfaceDecl surface(ApplicationManifest.SurfaceType type,
                                                           String entry) {
        return new ApplicationManifest.SurfaceDecl(type, entry);
    }

    private ApplicationManifest withUi(ApplicationManifest.UiDecl ui) {
        ApplicationManifest b = valid();
        return new ApplicationManifest(b.identity(), b.capabilities(), b.actions(), b.resources(),
                b.events(), b.permissions(), b.runtime(), ui);
    }

    // ─────────────────────────── 基线 manifest 与夹具 ───────────────────────────

    private ApplicationManifest valid() {
        return new ApplicationManifest(
                new ApplicationManifest.Identity("com.luxera.fixture", "夹具应用", "1.0.0", "只用来喂校验器", "test"),
                List.of(capability("game.play")),
                List.of(action("game.state", PermissionLevel.READ, RiskLevel.NONE, null),
                        action("game.make_move", PermissionLevel.EXECUTE, RiskLevel.LOW, schema())),
                List.of(new ApplicationManifest.ResourceDecl("tictactoe.game", "game://session/{sessionId}",
                        ApplicationManifest.Backing.RESOURCE_STORE, "读法说明")),
                List.of(new ApplicationManifest.EventDecl("game.move", "有人落子", true, "{uri}#MOVE-{moves}")),
                List.of(new ApplicationManifest.PermissionDecl("game.play", PermissionLevel.EXECUTE,
                        RiskLevel.LOW)),
                ApplicationManifest.RuntimeDecl.nativeRuntime(), null);
    }

    private ApplicationManifest withIdentity(ApplicationManifest.Identity identity) {
        ApplicationManifest b = valid();
        return new ApplicationManifest(identity, b.capabilities(), b.actions(), b.resources(),
                b.events(), b.permissions(), b.runtime(), b.ui());
    }

    private ApplicationManifest withCapabilities(List<ApplicationManifest.CapabilityDecl> capabilities) {
        ApplicationManifest b = valid();
        return new ApplicationManifest(b.identity(), capabilities, b.actions(), b.resources(),
                b.events(), b.permissions(), b.runtime(), b.ui());
    }

    private ApplicationManifest withActions(List<ApplicationManifest.ActionDecl> actions) {
        ApplicationManifest b = valid();
        return new ApplicationManifest(b.identity(), b.capabilities(), actions, b.resources(),
                b.events(), b.permissions(), b.runtime(), b.ui());
    }

    private ApplicationManifest withResources(List<ApplicationManifest.ResourceDecl> resources) {
        ApplicationManifest b = valid();
        return new ApplicationManifest(b.identity(), b.capabilities(), b.actions(), resources,
                b.events(), b.permissions(), b.runtime(), b.ui());
    }

    private ApplicationManifest withEvents(List<ApplicationManifest.EventDecl> events) {
        ApplicationManifest b = valid();
        return new ApplicationManifest(b.identity(), b.capabilities(), b.actions(), b.resources(),
                events, b.permissions(), b.runtime(), b.ui());
    }

    private ApplicationManifest withPermissions(List<ApplicationManifest.PermissionDecl> permissions) {
        ApplicationManifest b = valid();
        return new ApplicationManifest(b.identity(), b.capabilities(), b.actions(), b.resources(),
                b.events(), permissions, b.runtime(), b.ui());
    }

    private ApplicationManifest withRuntime(ApplicationManifest.RuntimeDecl runtime) {
        ApplicationManifest b = valid();
        return new ApplicationManifest(b.identity(), b.capabilities(), b.actions(), b.resources(),
                b.events(), b.permissions(), runtime, b.ui());
    }

    private static ApplicationManifest.CapabilityDecl capability(String id) {
        return new ApplicationManifest.CapabilityDecl(id, "标题", "描述", "game");
    }

    private static ApplicationManifest.ActionDecl action(String id, PermissionLevel permission,
                                                         RiskLevel risk, ObjectNode schema) {
        return new ApplicationManifest.ActionDecl(id, "game.play", "标题", "描述",
                permission, risk, AttentionPolicy.AWARE, schema, "给模型看的提示");
    }

    private static ObjectNode schema() {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("type", "object");
        return node;
    }

    private static ManifestException assertCode(String expectedCode, Runnable body) {
        ManifestException e = assertThrows(ManifestException.class, body::run);
        assertEquals(expectedCode, e.code(), "错误码是调用方唯一能据以行动的东西");
        return e;
    }
}
