package com.luxera.companion.application.web;

import com.luxera.companion.application.action.ActionGateway;
import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.lifecycle.ApplicationCatalogue;
import com.luxera.companion.application.lifecycle.Availability;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.resource.ResourceStore;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.application.surface.SurfaceCatalogue;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.ResourceView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LAP v1: <b>发现链</b> —— 能力 → 应用 → 动作, 外加资源读取。
 *
 * <p>这条链就是"不要把 50000 个 action 塞给 LLM"的全部实现: 每一级都比上一级窄一个数量级。
 * 能力有几十个, 某个能力下的应用有个位数, 某个应用的动作有个位数。Agent 永远只看到当前这一步
 * 该看的那一层。
 *
 * <p><b>全是只读接口, 所以全部不需要幂等键</b> —— 这也正是"READ 不产生 invocation"那条规则
 * 在传输层的体现: 刷新一次应用列表不该在 {@code action_invocation} 里留下任何痕迹。
 */
@RestController
@RequestMapping("/api/v1")
public class LapDiscoveryController {

    private final ActionGateway gateway;
    private final ResourceStore resources;
    private final ManifestRegistry manifests;
    private final ApplicationCatalogue catalogue;
    private final SurfaceCatalogue surfaces;

    public LapDiscoveryController(ActionGateway gateway,
                                  ResourceStore resources,
                                  ManifestRegistry manifests,
                                  ApplicationCatalogue catalogue,
                                  SurfaceCatalogue surfaces) {
        this.gateway = gateway;
        this.resources = resources;
        this.manifests = manifests;
        this.catalogue = catalogue;
        this.surfaces = surfaces;
    }

    /** 全部能力 —— 发现链的第一级, 也是 Agent 做"要不要用应用"判断时看到的东西。 */
    @GetMapping("/capabilities")
    public List<CapabilityView> capabilities() {
        return gateway.capabilities();
    }

    /** 某个能力下有哪些已发布应用。同一能力多候选是常态(井字棋与五子棋同属 game.play)。 */
    @GetMapping("/capabilities/{capabilityId}/applications")
    public List<ApplicationView> applicationsOfCapability(@PathVariable String capabilityId) {
        return gateway.applicationsFor(capabilityId);
    }

    @GetMapping("/applications")
    public List<ApplicationView> applications() {
        return gateway.applications();
    }

    /**
     * 一个应用的详情 —— §97 的 {@code GET /applications/{id}}, 应用详情页与 SurfaceHost
     * 所需的一切。
     *
     * <p>比列表多三样东西, 而这三样恰恰是"打开"这个动作需要的:
     * <ul>
     *   <li>{@code status} —— 十态原值。给开发者后台与审核队列看, 那里需要知道"卡在哪一步"。</li>
     *   <li>{@code availability} —— 五态投影(§4.1 那张表)。给"能不能打开"这个问题用,
     *       三列各自是一个布尔, 客户端不必自己 interpret 十个状态。</li>
     *   <li>{@code ui} —— 可以被放进哪几种容器、从哪个入口进(§68)。</li>
     * </ul>
     *
     * <p><b>刻意不按 {@code availability.inMarket} 过滤成 404。</b> 一个被挂起的应用,
     * 详情页该照常打得开并说一句"已下架" —— 那比一个 404 有用得多, 而 404 还会让人以为
     * 是自己把应用 id 打错了。只有 manifest 根本没注册过才是 404。
     */
    @GetMapping("/applications/{applicationId}")
    public ApplicationDetail application(@PathVariable String applicationId) {
        ApplicationManifest manifest = manifests.published(applicationId).orElseThrow(() ->
                new SessionException("UNKNOWN_APPLICATION", "没有已发布的应用 " + applicationId));
        ApplicationStatus status = catalogue.statusOf(applicationId);
        return new ApplicationDetail(
                manifest.applicationId(),
                manifest.version(),
                manifest.identity().name(),
                manifest.identity().description(),
                manifest.identity().category(),
                manifest.capabilities().stream().map(ApplicationManifest.CapabilityDecl::id).toList(),
                manifest.actions().size(),
                status == null ? null : status.name(),
                AvailabilityView.of(catalogue.availabilityOf(applicationId)),
                UiView.of(SurfaceCatalogue.effective(manifest)));
    }

    /** §97 的应用详情。字段名与 {@code ApplicationView} 对齐, 只多不少。 */
    public record ApplicationDetail(String applicationId, String version, String name,
                                    String description, String category,
                                    List<String> capabilities, int actionCount,
                                    String status, AvailabilityView availability, UiView ui) {}

    /**
     * §4.1 那张表在 HTTP 上的样子。<b>三列全部显式给出</b>, 而不是只给一个五态名 ——
     * 客户端要回答的是"能不能开新会话", 让它自己去维护一份"哪些状态算在架"的映射,
     * 就是让 §4.1 那张表在每一个客户端里各抄一份。
     */
    public record AvailabilityView(String state, boolean inMarket,
                                   boolean allowsNewSession, boolean allowsExistingSession) {

        static AvailabilityView of(Availability availability) {
            return new AvailabilityView(availability.name(), availability.inMarket(),
                    availability.allowsNewSession(), availability.allowsExistingSession());
        }
    }

    /**
     * {@code ui} 计划。{@code surfaces[].entry} 是模板, 变量只有 {@code {applicationId}}
     * 与 {@code {sessionId}} —— 客户端做且只做替换(§68/§69)。
     */
    public record UiView(String type, String entry, String minClientVersion,
                         List<SurfaceView> surfaces) {

        static UiView of(ApplicationManifest.UiDecl ui) {
            return new UiView(ui.type().name(), ui.entry(), ui.minClientVersion(),
                    ui.surfaces().stream().map(SurfaceView::of).toList());
        }
    }

    /** 一个 surface: 容器类型 + 该容器的入口模板。 */
    public record SurfaceView(String type, String entry) {
        static SurfaceView of(ApplicationManifest.SurfaceDecl decl) {
            return new SurfaceView(decl.type().name(), decl.entry());
        }
    }

    /**
     * 某个应用的已发布动作。{@code agentHint} 一并返回 ——
     * 那是应用作者写给 LLM 的"怎么做", 也是策略文本离开数字人的落点。
     */
    @GetMapping("/applications/{applicationId}/actions")
    public List<ActionSpec> actions(@PathVariable String applicationId) {
        return gateway.actionsOf(applicationId);
    }

    /**
     * 资源读取。三种查询方式: 按 URI(单个)、按会话、按应用。
     *
     * <p>没有参数时返回空列表而不是全部 —— "把所有资源列出来"不是任何一个调用方需要的能力,
     * 而它一旦存在就会被用上。
     */
    @GetMapping("/resources")
    public ResponseEntity<List<ResourceView>> resources(
            @RequestParam(required = false) String uri,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String applicationId) {

        if (uri != null && !uri.isBlank()) {
            return gateway.read(uri)
                    .map(v -> ResponseEntity.ok(List.of(v)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }
        // 列表路径也过一遍 gateway.read: agentHint 是资源模板上的声明, 列表与单读给出的
        // 视图必须是同一形状, 否则 Agent 会在两条读路径上看到两种世界。
        if (sessionId != null && !sessionId.isBlank()) {
            return ResponseEntity.ok(withHints(resources.bySession(sessionId)));
        }
        if (applicationId != null && !applicationId.isBlank()) {
            return ResponseEntity.ok(withHints(resources.byApplication(applicationId)));
        }
        return ResponseEntity.ok(List.of());
    }

    private List<ResourceView> withHints(List<ResourceView> views) {
        return views.stream()
                .map(v -> gateway.read(v.uri()).orElse(v))
                .toList();
    }
}
