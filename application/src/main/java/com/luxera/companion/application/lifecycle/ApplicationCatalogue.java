package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationRecord;
import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.repository.ApplicationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * LAP v1: <b>发现链上看到的那些应用</b> —— 两份真相的交点。
 *
 * <p>平台里"有哪些应用"本来是两件事, 而且它们刻意是两件事:
 * <ul>
 *   <li>{@link ManifestRegistry} —— <b>这个 JVM 里注册了哪些 manifest</b>(随构建/部署而来);</li>
 *   <li>{@code application} 表 —— <b>生命周期状态</b>(随运营与开发者流程而来)。</li>
 * </ul>
 *
 * <p>发现链要的恰恰是两者的<em>交</em>: 一个应用既要在本构建里注册过, 又要处在
 * {@code PUBLISHED} 上。分散到各个调用方去各查一次的话, 一定会有一处忘了查 ——
 * 于是"下架的应用在 MCP 里还看得见、在 REST 里看不见"这类偏差就有了生长的空间。
 * 所以这里只答一次, 谁问都是同一个答案。
 *
 * <p><b>账本里没有这个应用时, 答案是"在架"。</b> 这看起来像放水, 但它挡住的是更坏的一种故障:
 * 一个本构建新引入的应用还没被启动同步写进数据库(或测试夹具根本没建账本行), 若按"查不到就是
 * 下架"处理, 它会在所有人眼皮底下凭空消失, 而日志里什么也没有。宁可多上一个, 不要静默少一个。
 *
 * <p>R11 起, "在架"这个判断题扩成了 §4.1 的整张表 —— 见 {@link #availabilityOf}。发现链问的是
 * 表里的第一列, 开会话问的是第二列, 用旧会话问的是第三列, 而三列<em>同出一处</em>。
 */
@Slf4j
@Service
public class ApplicationCatalogue {

    private final ManifestRegistry manifests;
    private final ApplicationRepository applications;

    public ApplicationCatalogue(ManifestRegistry manifests, ApplicationRepository applications) {
        this.manifests = manifests;
        this.applications = applications;
    }

    /** 全部在架应用(每个应用的最新版本), 按 appId 排序。 */
    public List<ApplicationManifest> discoverable() {
        return manifests.applications().stream()
                .filter(m -> isDiscoverable(m.applicationId())).toList();
    }

    /** 某个能力下的在架应用 —— 发现链的第二级。 */
    public List<ApplicationManifest> discoverableFor(String capabilityId) {
        return manifests.byCapability(capabilityId).stream()
                .filter(m -> isDiscoverable(m.applicationId())).toList();
    }

    /** 某个应用当前在架的那一版; 不在架就是空。 */
    public Optional<ApplicationManifest> discoverable(String applicationId) {
        if (!isDiscoverable(applicationId)) {
            return Optional.empty();
        }
        return manifests.published(applicationId);
    }

    /** @see ApplicationCatalogue 类注释里"查不到即在架"的那一段 */
    public boolean isDiscoverable(String applicationId) {
        return availabilityOf(applicationId).inMarket();
    }

    /**
     * 十态原值。账本里没有这一行时给 {@code null} —— <b>刻意不在这里编一个状态</b>:
     * {@link #availabilityOf} 的"查不到即在架"是一条<em>行为</em>上的兜底, 而状态是一个事实,
     * 没有事实就该说没有。要把状态讲给人听的地方(开发者后台、审核队列)读这个。
     */
    public ApplicationStatus statusOf(String applicationId) {
        if (applicationId == null) {
            return null;
        }
        return applications.findById(applicationId).map(ApplicationRecord::statusEnum).orElse(null);
    }

    /**
     * 这个应用此刻能不能用 —— §4.1 那张表, 十态投影成的五态。
     *
     * <p>{@link #isDiscoverable} 现在只是它的一列。这不是合并两件事, 而是认出它们本来就是
     * 一件事的两个问法: "在不在发现链上"就是"出现在应用市场里吗"。留着两条独立实现,
     * 迟早会出现"市场里看得见、却开不了会话"这种没人定义过的组合。
     *
     * <p>{@code applicationId} 为空(而不是"账本里没有")时给 {@link Availability#DRAFT}:
     * 那是一个调用方的编程错误, 该得到的答案是"什么都没有", 不是"在架"。
     */
    public Availability availabilityOf(String applicationId) {
        if (applicationId == null) {
            return Availability.DRAFT;
        }
        return applications.findById(applicationId)
                .map(ApplicationRecord::statusEnum)
                .map(status -> {
                    Availability availability = Availability.of(status);
                    if (!availability.inMarket()) {
                        log.debug("[ApplicationCatalogue] {} 当前状态 {} → {} —— 不在发现链上",
                                applicationId, status, availability);
                    }
                    return availability;
                })
                // 账本里没有这一行: 见类注释的"查不到即在架" —— 本构建新引入的应用还没被
                // 启动同步写进数据库时, 让它消失比让它多上一会儿危险得多。
                .orElse(Availability.PUBLISHED);
    }
}
