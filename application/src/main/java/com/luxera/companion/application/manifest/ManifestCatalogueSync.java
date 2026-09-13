package com.luxera.companion.application.manifest;

import com.luxera.companion.application.domain.ApplicationCapabilityRecord;
import com.luxera.companion.application.domain.ApplicationRecord;
import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.domain.CapabilityRecord;
import com.luxera.companion.application.repository.ApplicationCapabilityRepository;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.repository.CapabilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 把已注册的 manifest 落成数据库行: {@code application / application_version / capability /
 * application_capability} 四张表。
 *
 * <p>为什么 manifest 运行时已经在内存里了, 还要落库:
 * <ul>
 *   <li>{@code action_invocation} 要指向一个<em>版本行</em>, 否则"这次调用跑的是哪一版"无从解释;</li>
 *   <li>生命周期状态机要有一个可以持久化的状态字段;</li>
 *   <li>历史版本要查得到 —— 内存注册表只有当前发布的那些。</li>
 * </ul>
 *
 * <p><b>关于"发布后不可变"与内置应用的关系</b>: 不可变是<em>开发者 API</em> 的性质 ——
 * 通过 HTTP 改写已发布版本的 manifest 必须被拒({@code VersionImmutabilityTest})。
 * 内置参考应用是随二进制发布的: 换了构建、manifest 变了, 启动同步就应当让新内容生效,
 * 否则改一行 JSON 要手工删库才能生效。所以这里比对 {@code manifest_hash}, 不一致时更新并
 * <b>大声告警</b> —— 让"构建换了内容"这件事在日志里看得见, 而不是悄悄发生。
 */
@Slf4j
@Service
public class ManifestCatalogueSync {

    private final ApplicationRepository applications;
    private final ApplicationVersionRepository versions;
    private final CapabilityRepository capabilities;
    private final ApplicationCapabilityRepository applicationCapabilities;

    public ManifestCatalogueSync(ApplicationRepository applications,
                                 ApplicationVersionRepository versions,
                                 CapabilityRepository capabilities,
                                 ApplicationCapabilityRepository applicationCapabilities) {
        this.applications = applications;
        this.versions = versions;
        this.capabilities = capabilities;
        this.applicationCapabilities = applicationCapabilities;
    }

    @Transactional
    public ApplicationVersionRecord sync(ApplicationManifest manifest, String rawJson) {
        String appId = manifest.applicationId();
        String version = manifest.version();
        String hash = sha256(rawJson);

        syncCapabilities(manifest);

        ApplicationRecord application = applications.findById(appId).orElseGet(() -> {
            ApplicationRecord fresh = new ApplicationRecord();
            fresh.setId(appId);
            return fresh;
        });
        application.setName(manifest.identity().name());
        application.setCategory(manifest.identity().category());
        // 只在"还没被任何人推过状态机"时自动上架。否则运营把某个应用挂起(SUSPENDED)之后,
        // 下一次重启会被启动同步推回 PUBLISHED —— 一个被启动流程悄悄撤销的运营决定,
        // 而日志里只会看到一条"应用已注册"。状态字段一旦有一个未经请求的改写者, 它就不再是事实。
        if (ApplicationStatus.DRAFT.name().equals(application.getStatus())) {
            application.setStatus(ApplicationStatus.PUBLISHED.name());
        }
        application.setLatestVersion(version);
        applications.save(application);

        ApplicationVersionRecord versionRow = versions.findByApplicationIdAndVersion(appId, version)
                .orElseGet(() -> {
                    ApplicationVersionRecord fresh = new ApplicationVersionRecord();
                    fresh.setApplicationId(appId);
                    fresh.setVersion(version);
                    return fresh;
                });
        if (versionRow.getManifestHash() != null && !hash.equals(versionRow.getManifestHash())) {
            log.warn("[Catalogue] 内置应用 {} v{} 的 manifest 随构建变了 ({} -> {}), "
                            + "按新内容更新 —— 内置应用的不可变性由开发者 API 保证, 不是启动同步",
                    appId, version, versionRow.getManifestHash(), hash);
        }
        versionRow.setManifestJson(rawJson);
        versionRow.setManifestHash(hash);
        versionRow.setRuntimeType(manifest.runtime().type().name());
        // 与上面同一条理由: 首次同步才上架。已经被人推过的版本行(DRAFT 之外的任何状态)
        // 由生命周期状态机掌管 —— 一个被挂起的版本不该在重启时自己回到架上。
        if (ApplicationStatus.DRAFT.name().equals(versionRow.getStatus())) {
            versionRow.setStatus(ApplicationStatus.PUBLISHED.name());
        }
        if (versionRow.getPublishedAt() == null) versionRow.setPublishedAt(LocalDateTime.now());
        versions.save(versionRow);

        syncApplicationCapabilities(versionRow.getId(), manifest);
        return versionRow;
    }

    private void syncCapabilities(ApplicationManifest manifest) {
        int order = 0;
        for (ApplicationManifest.CapabilityDecl decl : manifest.capabilities()) {
            CapabilityRecord row = capabilities.findById(decl.id()).orElseGet(() -> {
                CapabilityRecord fresh = new CapabilityRecord();
                fresh.setId(decl.id());
                return fresh;
            });
            // 标题/描述以最先声明它的应用为准, 但保留已有值 —— 两个应用共享 game.play 时,
            // 后注册的不该把目录文案改掉。
            if (row.getTitle() == null) row.setTitle(decl.title() == null ? decl.id() : decl.title());
            if (row.getDescription() == null) row.setDescription(decl.description());
            if (row.getCategory() == null) row.setCategory(decl.category());
            if (row.getSortOrder() == null) row.setSortOrder(order++);
            capabilities.save(row);
        }
    }

    private void syncApplicationCapabilities(String versionId, ApplicationManifest manifest) {
        List<ApplicationCapabilityRecord> existing = applicationCapabilities.findByApplicationVersionId(versionId);
        List<String> wanted = manifest.capabilities().stream()
                .map(ApplicationManifest.CapabilityDecl::id).toList();
        boolean alreadyCorrect = existing.size() == wanted.size()
                && existing.stream().allMatch(r -> wanted.contains(r.getCapabilityId()));
        if (alreadyCorrect) return;

        applicationCapabilities.deleteAll(existing);
        for (String capabilityId : wanted) {
            ApplicationCapabilityRecord link = new ApplicationCapabilityRecord();
            link.setApplicationVersionId(versionId);
            link.setCapabilityId(capabilityId);
            applicationCapabilities.save(link);
        }
    }

    /** manifest 的规范 hash。写入路径({@code ApplicationVersionService})也用它, 所以是公开的。 */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用: " + e.getMessage(), e);
        }
    }
}
