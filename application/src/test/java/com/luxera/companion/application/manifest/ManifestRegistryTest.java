package com.luxera.companion.application.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注册表的两条性质:
 *
 * <ol>
 *   <li><b>发现链看到的是最新版本</b>, 而且"最新"是按版本号比出来的, 不是按注册顺序 ——
 *       启动顺序不该决定谁生效。</li>
 *   <li><b>按动作反查返回列表。</b> {@code game.make_move} 同时属于井字棋与五子棋,
 *       把它们合并成一个才是 bug(会让后注册的应用悄悄接管前一个的动作)。</li>
 * </ol>
 */
class ManifestRegistryTest {

    private final ManifestRegistry registry = new ManifestRegistry();

    @Test
    void publishedIsTheHighestVersionNotTheLastRegistered() {
        registry.register(manifest("com.luxera.demo", "1.10.0"));
        registry.register(manifest("com.luxera.demo", "1.9.0"));   // 后注册但更旧

        assertEquals("1.10.0", registry.published("com.luxera.demo").orElseThrow().version(),
                "版本要按号比, 1.10.0 高于 1.9.0");
    }

    @Test
    void historicalVersionsStayAddressable() {
        registry.register(manifest("com.luxera.demo", "1.0.0"));
        registry.register(manifest("com.luxera.demo", "1.0.1"));

        assertEquals("1.0.0", registry.find("com.luxera.demo", "1.0.0").orElseThrow().version(),
                "历史版本仍可解释 —— action_invocation 指向的旧版本不能查不到");
        assertEquals(2, registry.allVersions().size());
        assertEquals(1, registry.applications().size(), "applications() 只给每个应用的最新版本");
    }

    @Test
    void twoApplicationsCanShareAnActionIdWithoutCollision() {
        registry.register(manifest("com.luxera.tictactoe", "1.0.0"));
        registry.register(manifest("com.luxera.gomoku", "1.0.0"));

        List<ApplicationManifest> owners = registry.byActionId("game.make_move");
        assertEquals(2, owners.size(), "两个应用都可以声明 game.make_move —— 这正是键里要带 applicationId 的原因");
    }

    @Test
    void capabilityCatalogueIsTheUnionOfPublishedApplications() {
        registry.register(manifest("com.luxera.tictactoe", "1.0.0"));
        registry.register(manifest("com.luxera.gomoku", "1.0.0"));

        assertEquals(1, registry.capabilityCatalogue().size());
        assertTrue(registry.capabilityCatalogue().containsKey("game.play"));
        assertEquals(2, registry.byCapability("game.play").size());
    }

    @Test
    void duplicateRegistrationOfTheSameVersionIsRejected() {
        registry.register(manifest("com.luxera.demo", "1.0.0"));
        assertThrows(IllegalStateException.class, () -> registry.register(manifest("com.luxera.demo", "1.0.0")));
    }

    @Test
    void versionComparisonHandlesDifferentSegmentCounts() {
        assertTrue(ManifestRegistry.compareVersions("1.0", "1.0.1") < 0);
        assertTrue(ManifestRegistry.compareVersions("2.0.0", "1.99.99") > 0);
        assertEquals(0, ManifestRegistry.compareVersions("1.0.0", "1.0.0"));
    }

    private static ApplicationManifest manifest(String appId, String version) {
        return new ApplicationManifest(
                new ApplicationManifest.Identity(appId, "演示", version, null, "game"),
                List.of(new ApplicationManifest.CapabilityDecl("game.play", "对弈", null, "game")),
                List.of(new ApplicationManifest.ActionDecl("game.make_move", "game.play", "落子",
                        null, PermissionLevel.WRITE, RiskLevel.LOW,
                        com.luxera.companion.contracts.application.AttentionPolicy.AWARE,
                        new ObjectMapper().createObjectNode(), null)),
                List.of(new ApplicationManifest.ResourceDecl("game.session",
                        "game://session/{sessionId}", ApplicationManifest.Backing.RESOURCE_STORE, null)),
                List.of(),
                List.of(new ApplicationManifest.PermissionDecl("game.play",
                        PermissionLevel.WRITE, RiskLevel.LOW)),
                ApplicationManifest.RuntimeDecl.nativeRuntime(), null);
    }
}
