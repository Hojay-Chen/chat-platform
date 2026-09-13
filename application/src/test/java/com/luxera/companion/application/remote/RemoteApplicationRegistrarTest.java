package com.luxera.companion.application.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.action.ActionHandler;
import com.luxera.companion.application.action.ActionHandlerContext;
import com.luxera.companion.application.action.ActionHandlerRegistry;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.lifecycle.ApplicationCatalogue;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestCatalogueSync;
import com.luxera.companion.application.manifest.ManifestException;
import com.luxera.companion.application.manifest.ManifestParser;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.manifest.ManifestValidator;
import com.luxera.companion.application.manifest.RuntimeType;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LAP v1 §Adapter-Transport: <b>加一种运行时 = 加一个 handler</b> —— 这句话在 REMOTE 上要成立。
 *
 * <p>内置应用与远端应用在宿主侧唯一的实质差别是: 后者的 handler 由<em>平台</em>提供, 不需要应用
 * 作者提交任何代码, 只需要一个能收 HTTP 的地址。所以"每个动作都要有 handler"那条发布前校验
 * 在这里自动成立 —— 注册器为 manifest 里的<em>每一个</em> action 挂一个转发 handler, 而不是只挂
 * 一个"远端应用"再靠动作名分派。
 *
 * <p><b>这套东西是手工装配的, 不是从容器里拿的</b>(注册器自己的注释里就说了这是它 public 的理由)。
 * 注册会往内存注册表和动作处理器注册表里写东西, 而这两样都不参与事务回滚: 一个把夹具留在
 * 共享上下文里的测试, 会让后面每一个跑在同一个 Spring 上下文里的测试都多看到两个不属于它的
 * 动作。每个用例自己造一套, 污染就无从发生, 顺带也快得多。
 *
 * <p>落库那一半({@code ManifestCatalogueSync})用 mock。它有自己的测试, 而注册器根本不看它的
 * 返回值 —— 在这里把它真跑一遍, 只会让这个类顺带依赖四个仓储的桩, 却证明不了任何与注册有关的
 * 事。而"注册之后出现在发现链上"那一条要的是 {@code ApplicationCatalogue}: 它的语义是
 * "账本里查不到就当在架"(见该类注释), 所以一个永远返回空的仓储正好表达"这个应用还没被同步过,
 * 但在架" —— 断言因此仍然落在真实的发现链实现上。
 */
class RemoteApplicationRegistrarTest {

    private static final String LOCATION = "applications/remote-probe/1.0.0/application-manifest.json";
    private static final String APP = "com.luxera.probe";

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
                        new MockEnvironment().withProperty("app.lap.remote.auth.probe", "s3cr3t"), 3000),
                new DefaultResourceLoader(),
                "");   // 与生产默认值一致: 一个远端应用都不配
    }

    private static ApplicationRepository emptyLedger() {
        ApplicationRepository repository = mock(ApplicationRepository.class);
        when(repository.findById(anyString())).thenReturn(java.util.Optional.empty());
        return repository;
    }

    /** 配置默认是空的 —— 一个默认指向某台服务器的地址, 会让机器在启动时才暴露出来。 */
    @Test
    void nothingIsRegisteredUnlessSomethingIsConfigured() {
        assertTrue(registrar().manifestLocations().isEmpty());
        assertTrue(manifests.published(APP).isEmpty());
        assertTrue(handlers.keys().isEmpty());
    }

    @Test
    void everyActionInTheManifestGetsItsOwnForwardingHandler() {
        ApplicationManifest manifest = registrar().register(LOCATION);

        assertEquals(APP, manifest.applicationId());
        assertEquals(RuntimeType.REMOTE, manifest.runtime().type());
        assertEquals(2, manifest.actions().size());

        for (ApplicationManifest.ActionDecl action : manifest.actions()) {
            ActionHandler handler = handlers.find(APP, "1.0.0", action.id()).orElseThrow(() ->
                    new AssertionError("动作 " + action.id() + " 没有 handler —— 点了没反应"));
            assertInstanceOf(RemoteActionHandler.class, handler);
            assertEquals(APP, ((RemoteActionHandler) handler).manifest().applicationId());
        }
    }

    /** 远端应用的 handler 只挂在它自己那一版上 —— 相邻版本各挂各的, 不互相顶替。 */
    @Test
    void theHandlersAreKeyedByVersionAsWellAsByAction() {
        registrar().register(LOCATION);

        assertTrue(handlers.find(APP, "1.0.0", "probe.ping").isPresent());
        assertTrue(handlers.find(APP, "2.0.0", "probe.ping").isEmpty(),
                "另一个版本没有注册过 —— 键里必须带版本");
    }

    /** 注册之后它就该出现在发现链上 —— 与内置应用走的是同一条路, 不是一条平行的。 */
    @Test
    void aRegisteredRemoteApplicationShowsUpInDiscoveryLikeAnyOther() {
        registrar().register(LOCATION);

        assertTrue(catalogue.isDiscoverable(APP));
        assertTrue(catalogue.discoverable().stream()
                .anyMatch(m -> APP.equals(m.applicationId())));
        assertTrue(catalogue.discoverableFor("probe.diagnostics").stream()
                .anyMatch(m -> APP.equals(m.applicationId())));
    }

    /**
     * 配置指了一个 NATIVE manifest 是部署错误, 而不是"那就当内置的处理吧" ——
     * 内置应用的 handler 只可能来自代码, 平台这边凭空造不出来。
     */
    @Test
    void pointingTheRemoteConfigAtANativeManifestIsADeploymentError() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> registrar().register("applications/tictactoe/1.0.0/application-manifest.json"));

        assertEquals("REMOTE_MANIFEST_REQUIRED", e.code());
        assertTrue(handlers.keys().isEmpty(), "拒绝要发生在挂 handler 之前, 否则注册表会留一半");
    }

    @Test
    void aMissingManifestFileIsReportedByNameNotAsANullPointer() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> registrar().register("applications/nope/1.0.0/application-manifest.json"));

        assertEquals("MANIFEST_NOT_FOUND", e.code());
        assertTrue(e.getMessage().contains("applications/nope"), e.getMessage());
    }

    /**
     * 一个注册好了的转发 handler 必须是<em>能跑的</em>: 网关调它, 它调 invoker, 拿回一个
     * {@link ActionOutcome}。这里配了密钥、夹具的地址指向 {@code 127.0.0.1:9}(discard 端口),
     * 于是拿回的是"连不上"而不是一次崩溃 —— 平台侧任何一条路径都不该把异常抛到网关脸上。
     */
    @Test
    void aRegisteredForwardingHandlerAnswersWithAnOutcomeRatherThanBlowingUp() {
        ApplicationManifest manifest = registrar().register(LOCATION);
        ActionHandler handler = handlers.find(APP, "1.0.0", "probe.ping").orElseThrow();

        ActionOutcome outcome = handler.execute(context(manifest));

        assertEquals(ActionStatus.FAILED, outcome.status());
        assertEquals("REMOTE_UNAVAILABLE", outcome.errorCode());
    }

    private ActionHandlerContext context(ApplicationManifest manifest) {
        return new ActionHandlerContext(manifest.applicationId(), manifest.version(), manifest,
                manifest.action("probe.ping").orElseThrow(),
                new ActionRequest("probe.ping", "probe://probe/1", null, null),
                new InvocationContext(PrincipalType.AGENT, "dh-probe", "dh-probe", "user-1",
                        null, "corr-probe"),
                null, objectMapper);
    }
}
