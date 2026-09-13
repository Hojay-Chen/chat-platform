package com.luxera.companion.application.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.action.ActionHandler;
import com.luxera.companion.application.action.ActionHandlerContext;
import com.luxera.companion.application.action.ActionHandlerRegistry;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.action.ResourceAccess;
import com.luxera.companion.application.lifecycle.ApplicationCatalogue;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestCatalogueSync;
import com.luxera.companion.application.manifest.ManifestParser;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.manifest.ManifestValidator;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LAP v2 R14: <b>远端五子棋的 manifest 是随平台二进制发出去的, 而它的应用本体在
 * 平台进程之外</b> —— 这条测试守的是两者之间那份契约的两半。
 *
 * <p>应用本体是 {@code remote-apps/gomoku/app.py}, 一个纯标准库的 Python 服务。它不参与
 * Java 侧的任何构建, 于是"manifest 写的动作集合"与"Python 那边注册的动作集合"之间
 * <em>没有任何编译期检查</em> —— 这条测试就是那个检查:
 * <ol>
 *   <li>manifest 能被平台按 REMOTE 注册, 每个动作都挂上转发 handler(形状);</li>
 *   <li>注册之后与内置应用出现在<b>同一条</b>发现链上, 同能力并存、靠 URI scheme 消歧
 *       (原则 2/7 的远端版);</li>
 *   <li>签名两侧逐字节一致 —— 这里用<b>独立实现</b>的 HMAC(不调
 *       {@code RemoteSignature}, 否则"两侧一致"就成了一句同义反复)算出
 *       Python/TS SDK 与平台都会算出的同一个签名, 再由平台的 verify 收下;</li>
 *   <li>manifest 里永远没有密钥 —— {@code authRef} 只是名字, 这条是 grep 级别的断言。</li>
 * </ol>
 *
 * <p>与 {@link RemoteApplicationRegistrarTest} 的分工: 那条管"注册器这个机制", 用的是
 * 测试夹具 {@code com.luxera.probe}; 这条管"我们真的发出去的这一份", 用的是
 * {@code com.luxera.remote-gomoku}。机制对了而内容错了, 上线照样是一场 404。
 */
class RemoteRegistrationTest {

    /** 与 remote-apps/gomoku 启动时 LAP_SERVICE_SECRET 的验收值一致(check-remote-app.sh 亦然)。 */
    private static final String SECRET = "check-remote-gomoku-secret";
    private static final String LOCATION =
            "applications/remote-gomoku/1.0.0/application-manifest.json";
    private static final String APP = "com.luxera.remote-gomoku";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ManifestRegistry manifests = new ManifestRegistry();
    private final ActionHandlerRegistry handlers = new ActionHandlerRegistry();
    private final ApplicationCatalogue catalogue = new ApplicationCatalogue(manifests, emptyLedger());

    private RemoteApplicationRegistrar registrar() {
        return new RemoteApplicationRegistrar(
                new ManifestParser(objectMapper),
                new ManifestValidator(),
                manifests,
                handlers,
                mock(ManifestCatalogueSync.class),
                new RemoteApplicationInvoker(objectMapper,
                        new MockEnvironment().withProperty("app.lap.remote.auth.remote-gomoku", SECRET),
                        3000),
                new DefaultResourceLoader(),
                "");
    }

    private static ApplicationRepository emptyLedger() {
        ApplicationRepository repository = mock(ApplicationRepository.class);
        when(repository.findById(anyString())).thenReturn(java.util.Optional.empty());
        return repository;
    }

    // ─────────────────────────── 形状 ───────────────────────────

    /**
     * 随二进制发出去的那份 manifest 必须是可注册的 —— 每次改 manifest 的人都会在
     * 这里第一时间知道有没有把 Python 侧甩在身后。
     */
    @Test
    void theShippedManifestRegistersAsARemoteApplication() {
        ApplicationManifest manifest = registrar().register(LOCATION);

        assertEquals(APP, manifest.applicationId());
        assertEquals("1.0.0", manifest.version());
        for (ApplicationManifest.ActionDecl action : manifest.actions()) {
            ActionHandler handler = handlers.find(APP, "1.0.0", action.id()).orElseThrow(() ->
                    new AssertionError("动作 " + action.id() + " 没挂上转发 handler"));
            assertInstanceOf(RemoteActionHandler.class, handler);
        }
    }

    /**
     * 它与内置五子棋在<b>同一条发现链</b>上并存 —— 同能力(game.play)、不同 id、不同
     * URI scheme(gomoku:// vs gomoku-remote://)。"候选应用 ≥ 2"在 check-lap.sh 断言 3
     * 里数的是内置的; 这条补上远端的那一个, 两个加起来才是"能力相同的应用可以并存"的全貌。
     */
    @Test
    void itJoinsTheSameDiscoveryChainAsTheBuiltinGomokuByItsOwnUriScheme() {
        ApplicationManifest manifest = registrar().register(LOCATION);

        assertTrue(catalogue.isDiscoverable(APP));
        assertTrue(catalogue.discoverableFor("game.play").stream()
                        .anyMatch(m -> APP.equals(m.applicationId())),
                "同能力的远端实现必须出现在候选里, 靠 URI scheme 消歧而不是被谁挤掉");
        assertEquals("gomoku-remote://match/{sessionId}",
                manifest.resources().get(0).uriTemplate(),
                "URI scheme 与内置五子棋不同 —— 这是发现链上两个同能力应用唯一的区分");
    }

    // ─────────────────────────── 契约 ───────────────────────────

    /**
     * 签名两侧逐字一致 —— 平台算的签名, 远端(Python/TS SDK 同一个算法)也认。
     *
     * <p>这里的 HMAC 是<b>独立写出来的</b>(照着协议文档: {@code sha256=hex(hmac(secret,
     * timestamp + "." + body))}), 不是调 {@code RemoteSignature.sign} —— 用被测实现
     * 生成再用被测实现校验, "两侧一致"就永远成立, 也永远什么都没证明。
     */
    @Test
    void theSignatureAgreesWithAnIndependentImplementationOfTheSameProtocol() throws Exception {
        String timestamp = "1760000000";
        String body = "{\"action\":\"game.make_move\",\"input\":{\"position\":112}}";
        String digest = HexFormat.of().formatHex(hmac((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));

        assertTrue(RemoteSignature.verify(SECRET, timestamp, body, "sha256=" + digest),
                "远端按协议文档算出的签名, 平台必须认 —— 两侧用的是同一份协议, 不是同一个类");
        assertFalse(RemoteSignature.verify(SECRET, timestamp, body, "sha256=" + digest + "00"),
                "多一个字节的伪造也要被常量时间比较挡下");
    }

    /** manifest 里永远不出现密钥 —— authRef 是名字; 名字到密钥那一步在平台配置里, 且只在那里。 */
    @Test
    void theManifestCarriesANameNotASecret() throws Exception {
        String json = new String(
                getClass().getClassLoader().getResourceAsStream(LOCATION).readAllBytes(),
                StandardCharsets.UTF_8);

        assertFalse(json.contains(SECRET), "密钥绝不进 manifest —— 它会进数据库、进日志、进导出包");
        assertTrue(json.contains("\"authRef\": \"remote-gomoku\""),
                "manifest 里出现的是名字, 平台配置按名字找到密钥");
    }

    /**
     * 没起 Python 服务时, 平台对它的调用应当是一次<b>有尊严的失败</b>: REMOTE_UNAVAILABLE,
     * 而不是把异常抛到网关脸上 —— 这条在 {@link RemoteApplicationRegistrarTest} 已对探针
     * 证明过, 这里对真实发出去的这一份再证一次: 断言的是 handler 注册表里真实挂上的那个。
     */
    @Test
    void aCallWithoutThePythonSideAnswersWithAnOutcomeNotAnException() {
        ApplicationManifest manifest = registrar().register(LOCATION);
        ActionHandler handler = handlers.find(APP, "1.0.0", "game.state").orElseThrow();

        ActionOutcome outcome = handler.execute(new ActionHandlerContext(
                manifest.applicationId(), manifest.version(), manifest,
                manifest.action("game.state").orElseThrow(),
                new ActionRequest("game.state", "gomoku-remote://match/s-1", null, null),
                new InvocationContext(PrincipalType.HUMAN, "user-1", null, "user-1",
                        null, "corr-remote"),
                null, objectMapper));

        assertEquals(ActionStatus.FAILED, outcome.status());
        assertEquals("REMOTE_UNAVAILABLE", outcome.errorCode());
    }

    private byte[] hmac(byte[] material) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(material);
    }

    // ─────────────────── R14: 远端状态投影成平台可读的一行 ───────────────────

    /**
     * 远端应用的状态在平台进程之外, 平台递不过去一个写句柄 —— 于是"远端下完了棋"与
     * "平台读得到那盘棋"之间会裂开。这条钉住补上的那一半: 远端写成功后, 平台把它返回的
     * {@code state} 投影进 resource 行, 好让<b>所有参与者共享同一个 Resource</b>
     * (原则 7)对远端应用同样成立。
     */
    @Test
    void aSuccessfulRemoteWriteIsMirroredIntoTheSharedResourceRow() {
        ApplicationManifest manifest = registrar().register(LOCATION);
        RemoteApplicationInvoker invoker = mock(RemoteApplicationInvoker.class);
        ObjectNode state = objectMapper.createObjectNode().put("turn", "O").put("moves", 1);
        when(invoker.invoke(any(), any()))
                .thenReturn(ActionOutcome.success(objectMapper.createObjectNode().set("state", state)));

        ResourceAccess access = mock(ResourceAccess.class);
        new RemoteActionHandler(manifest, invoker).execute(context(manifest, "game.make_move", access));

        verify(access).write(eq("gomoku-remote://match/s-1"), eq("gomoku.game"), eq(state), any());
    }

    /**
     * 投影只发生在写动作上: 读一次棋盘不该让资源版本 +1, 远端说不的时候也不该在平台这一侧
     * 留下一行"看起来成功过"的状态 —— 那会让下一个读的人以为这步棋生效了。
     */
    @Test
    void aReadOrAFailedRemoteCallNeverTouchesTheResourceRow() {
        ApplicationManifest manifest = registrar().register(LOCATION);
        RemoteApplicationInvoker invoker = mock(RemoteApplicationInvoker.class);
        ObjectNode state = objectMapper.createObjectNode().put("turn", "X");
        when(invoker.invoke(any(), any()))
                .thenReturn(ActionOutcome.success(objectMapper.createObjectNode().set("state", state)));

        ResourceAccess access = mock(ResourceAccess.class);
        RemoteActionHandler handler = new RemoteActionHandler(manifest, invoker);
        handler.execute(context(manifest, "game.state", access));

        when(invoker.invoke(any(), any())).thenReturn(ActionOutcome.fail("REMOTE_TIMEOUT", "远端没应"));
        handler.execute(context(manifest, "game.make_move", access));

        verify(access, never()).write(any(), any(), any(), any());
    }

    private ActionHandlerContext context(ApplicationManifest manifest, String actionId, ResourceAccess access) {
        return new ActionHandlerContext(
                manifest.applicationId(), manifest.version(), manifest,
                manifest.action(actionId).orElseThrow(),
                new ActionRequest(actionId, "gomoku-remote://match/s-1", null, null),
                new InvocationContext(PrincipalType.HUMAN, "user-1", null, "user-1",
                        null, "corr-remote"),
                access, objectMapper);
    }

    /** 顺带钉死协议本身的常量 —— 任何一侧改了前缀或头名, 三个 SDK + 平台要一起改。 */
    @Test
    void theProtocolConstantsAreWhatTheSdksExpect() {
        assertEquals("X-Lap-Signature", RemoteSignature.HEADER_SIGNATURE,
                "Python SDK: HEADER_SIGNATURE; TypeScript SDK: SIGNATURE_HEADER");
        assertEquals("X-Lap-Timestamp", RemoteSignature.HEADER_TIMESTAMP);
        assertEquals("sha256=", RemoteSignature.SCHEME);
    }
}
