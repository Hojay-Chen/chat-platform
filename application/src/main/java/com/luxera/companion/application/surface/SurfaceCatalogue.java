package com.luxera.companion.application.surface;

import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * LAP v2 §17/§67/§68: <b>客户端该拿到的那份 ui 计划</b> —— 声明的优先, 没声明就是平台默认。
 *
 * <p>这一层存在的理由只有一条: <em>"作者声明了什么"与"客户端该渲染什么"是两件事。</em>
 * manifest 里 {@code ui} 整段可以不写(一条提醒数据没有自己的界面, 但它仍然该能在平台里被打开),
 * 而客户端不能拿一个 {@code null} 去决定渲染什么 —— 于是"没写"必须在这里被翻译成一份明确的计划。
 *
 * <p>把默认值放在这里而不是放回解析器里塞一个假的 {@code UiDecl}, 是因为两者在版本对比时
 * 含义完全不同: 解析器里塞了默认值, "作者从没写过 ui"和"作者写了平台默认值"就成了同一份
 * manifest, 而哪天默认值变了, 前者该跟着变、后者不该。默认值是<em>投影的</em>性质, 不是
 * manifest 的性质。
 *
 * <h2>entry 是一个模板, 只有两个变量</h2>
 * <p>{@code {applicationId}} 与 {@code {sessionId}}。客户端做且只做变量替换 ——
 * 这就是 §68 说的"只定义 surface type / entry / minimum client version"在代码里的样子。
 * REMOTE 应用的 entry 是一个绝对地址, 里面没有变量; 会话上下文怎么传给它(查询串、postMessage)
 * 是那个应用与宿主客户端之间的事, 不是平台的事。
 */
@Service
public class SurfaceCatalogue {

    private final ManifestRegistry manifests;

    public SurfaceCatalogue(ManifestRegistry manifests) {
        this.manifests = manifests;
    }

    /** 某个应用当前那一版的 ui 计划; 应用没注册就是空(调用方该给 404)。 */
    public Optional<ApplicationManifest.UiDecl> planOf(String applicationId) {
        return manifests.published(applicationId).map(SurfaceCatalogue::effective);
    }

    /**
     * 把一份 manifest 折叠成一份 ui 计划。<b>public static</b>: 它是一条纯函数,
     * 谁需要都可以直接拿一份 manifest 算, 不必先有一个 bean。
     */
    public static ApplicationManifest.UiDecl effective(ApplicationManifest manifest) {
        return manifest.ui() != null ? manifest.ui() : defaultUi(manifest.applicationId());
    }

    /**
     * 平台默认 —— <b>一个由平台自己渲染的全页应用</b>。
     *
     * <p>只给一个 {@code FULL_PAGE} surface, 不给五个: 默认值的职责是"让这个应用能被打开",
     * 不是"替作者猜他想要哪几种容器"。多给的那几种不花作者一分钱, 却会让"这个应用支持被嵌进
     * 聊天"变成一句没有人做过的承诺。
     *
     * <p>{@code minClientVersion} 留空: 平台没法替一个作者声称他的应用要求哪个客户端版本。
     */
    public static ApplicationManifest.UiDecl defaultUi(String applicationId) {
        String base = "/applications/" + applicationId;
        return new ApplicationManifest.UiDecl(
                ApplicationManifest.UiMode.EMBEDDED,
                base,
                null,
                List.of(new ApplicationManifest.SurfaceDecl(
                        ApplicationManifest.SurfaceType.FULL_PAGE,
                        "/applications/{applicationId}/sessions/{sessionId}")));
    }
}
