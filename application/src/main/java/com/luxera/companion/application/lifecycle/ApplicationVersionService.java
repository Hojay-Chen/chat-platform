package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationRecord;
import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestCatalogueSync;
import com.luxera.companion.application.manifest.ManifestParser;
import com.luxera.companion.application.manifest.ManifestValidator;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LAP v1: <b>manifest 的写入口, 也是"发布后不可变"的兑现处。</b>
 *
 * <p>方案要求 {@code manifest_json} / {@code manifest_hash} / {@code runtime_type} 一旦
 * {@code PUBLISHED} 就冻结, 任何写入口先查状态。本类就是<em>那个</em>写入口 ——
 * 把它做成唯一一个, 这条不变量才可能成立: 两处写 manifest 的代码里有一处忘了查状态,
 * 整体的保证就降级为"看运气"。
 *
 * <p><b>它与 {@code ManifestCatalogueSync} 的分工必须说清楚, 否则看起来像两份重复的实现:</b>
 * <ul>
 *   <li>{@code ManifestCatalogueSync} —— <b>启动同步</b>, 把随构建发布的内置应用落到数据库。
 *       它面对的是"这个二进制里有什么", 所以它<em>不</em>受不可变性约束
 *       (改了 manifest 就得重启才能生效这件事本身已经很贵了, 不该再加一道删库)。</li>
 *   <li>本类 —— <b>运行时写入</b>, 面对的是"某个开发者通过 API 要改这一版"。
 *       这正是不可变性要防的那条路。</li>
 * </ul>
 *
 * <p><b>它只写账本, 不碰内存注册表。</b> 注册表回答的是"这个 JVM 里跑着哪些应用", 那是部署的
 * 语义 —— 一个 HTTP 请求不该改变它。要让新 manifest 生效, 路径是发新版本再经发布流程
 * ({@code APPROVED → PUBLISHED}); 把那一步留给开发者门户, 而不是在这里偷偷 register 一个
 * 没有 handler 的 manifest 进去({@code ManifestRegistrar} 会以
 * {@code ACTION_HANDLER_MISSING} 拒绝, 那是对的 —— 一个点了没反应的动作不该上架)。
 */
@Slf4j
@Service
public class ApplicationVersionService {

    private final ApplicationRepository applications;
    private final ApplicationVersionRepository versions;
    private final ManifestParser parser;
    private final ManifestValidator validator;
    private final ApplicationLifecycleService lifecycle;

    public ApplicationVersionService(ApplicationRepository applications,
                                     ApplicationVersionRepository versions,
                                     ManifestParser parser,
                                     ManifestValidator validator,
                                     ApplicationLifecycleService lifecycle) {
        this.applications = applications;
        this.versions = versions;
        this.parser = parser;
        this.validator = validator;
        this.lifecycle = lifecycle;
    }

    /**
     * 写入(或首次落库)一个版本的 manifest。只允许落在可写状态上。
     *
     * <p>校验顺序是刻意的: <b>先看状态, 再解析</b>。反过来的话, 一个针对已发布版本的写请求
     * 会先把 manifest 解析一遍 —— 于是"被拒绝"和"你的 JSON 有问题"这两种完全不同的结论
     * 会按内容随机出现, 而调用方需要知道的只有第一件事。
     *
     * @throws SessionException {@code UNKNOWN_APPLICATION} / {@code VERSION_IMMUTABLE} /
     *                          {@code MANIFEST_IDENTITY_MISMATCH} / manifest 校验失败
     */
    @Transactional
    public ApplicationVersionRecord saveManifest(String applicationId, String version, String rawJson) {
        if (applicationId == null || version == null) {
            throw new SessionException("INVALID_ARGUMENT", "缺少应用 id 或版本号",
                    ActionStatus.INVALID_ARGUMENT);
        }
        if (!lifecycle.exists(applicationId)) {
            throw new SessionException("UNKNOWN_APPLICATION", "应用不存在: " + applicationId);
        }
        ApplicationVersionRecord row = versions.findByApplicationIdAndVersion(applicationId, version)
                .orElse(null);
        if (row != null && !row.statusEnum().isMutable()) {
            throw new SessionException("VERSION_IMMUTABLE",
                    "版本 " + applicationId + "@" + version + " 已是 " + row.getStatus()
                            + ", manifest 不可再改 —— 要改就发新版本",
                    ActionStatus.STATE_CONFLICT);
        }

        ApplicationManifest manifest = parser.parse(rawJson);
        validator.validate(manifest);
        if (!applicationId.equals(manifest.applicationId()) || !version.equals(manifest.version())) {
            throw new SessionException("MANIFEST_IDENTITY_MISMATCH",
                    "路径里的 " + applicationId + "@" + version + " 与 manifest 里的 "
                            + manifest.applicationId() + "@" + manifest.version() + " 不一致",
                    ActionStatus.INVALID_ARGUMENT);
        }

        if (row == null) {
            row = new ApplicationVersionRecord();
            row.setApplicationId(applicationId);
            row.setVersion(version);
            row.setStatus(ApplicationStatus.DRAFT.name());
        }
        row.setManifestJson(rawJson);
        row.setManifestHash(ManifestCatalogueSync.sha256(rawJson));
        row.setRuntimeType(manifest.runtime().type().name());
        ApplicationVersionRecord saved = versions.save(row);
        log.info("[VersionService] 写入 {}@{} 的 manifest (状态 {})",
                applicationId, version, saved.getStatus());
        return saved;
    }

    /** 版本行(含状态与 hash) —— 发布流程与测试都要读它。 */
    public ApplicationVersionRecord require(String applicationId, String version) {
        return versions.findByApplicationIdAndVersion(applicationId, version).orElseThrow(() ->
                new SessionException("UNKNOWN_VERSION",
                        "版本不存在: " + applicationId + "@" + version));
    }

    /** 某个应用的全部版本, 新的在前。开发者 API 列版本用。 */
    public java.util.List<ApplicationVersionRecord> versionsOf(String applicationId) {
        return versions.findByApplicationIdOrderByCreatedAtDesc(applicationId);
    }

    /** 应用行本身; 找不到就是未知应用。 */
    public ApplicationRecord requireApplication(String applicationId) {
        return applications.findById(applicationId).orElseThrow(() ->
                new SessionException("UNKNOWN_APPLICATION", "应用不存在: " + applicationId));
    }
}
