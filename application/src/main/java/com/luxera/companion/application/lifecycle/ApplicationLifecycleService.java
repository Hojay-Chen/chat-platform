package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationRecord;
import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.PrincipalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LAP v1: <b>应用生命周期状态机</b> —— 谁在什么状态下能走到哪一步。
 *
 * <p>规则本身不在这个类里, 在 {@link ApplicationStatus#canMoveTo}。这是刻意的: 合法迁移表是
 * <em>领域知识</em>, 放在枚举上就只有一个地方能回答"能不能", 而不是"控制器放行、服务再判一次、
 * 运维脚本判第三次"。本类只做三件事 —— 查当前状态、问规则、把结果落下去。
 *
 * <p><b>为什么状态机必须真的落地, 而不只是一个字段。</b>一个从不被读的状态字段与一个不存在
 * 的状态字段没有任何区别。所以这里有两条实际的后果:
 * <ul>
 *   <li>{@link #transition} 把 {@code application.status} 与 {@code application_version.status}
 *       一起推进 —— 版本行的状态正是 {@link ApplicationVersionService} 拒绝改写已发布 manifest
 *       的依据(见 {@code VERSION_IMMUTABLE});</li>
 *   <li>{@code ActionGateway} 的发现面按 {@code isDiscoverable()} 过滤 —— 一个被挂起的应用
 *       从能力/应用/动作三个列表上一起消失, 而不是只在一个没人读的字段上写着 SUSPENDED。</li>
 * </ul>
 *
 * <h2>本平台对几个状态的定义(设计方案给了状态名, 语义由这里定死)</h2>
 * <ul>
 *   <li>{@code PUBLISHED} —— 出现在发现链上({@code ApplicationStatus.isDiscoverable()});</li>
 *   <li>{@code SUSPENDED} —— <b>从发现链上撤下, 但已安装的调用不受影响</b>。这是软停用:
 *       一盘正在下的棋不该因为运营点了"暂停"而突然走不动;</li>
 *   <li>{@code DEPRECATED} —— 终态。没有后继({@code canMoveTo} 对任何目标都返回 false);</li>
 *   <li>{@code DRAFT} —— 从未同步过的初始态, 也是启动同步唯一会自动推到 PUBLISHED 的状态。</li>
 * </ul>
 *
 * <h2>谁能推它</h2>
 * 只有 {@code SYSTEM} 与 {@code APPLICATION}(开发者 API 的服务身份)。<b>真人不行</b> ——
 * 上架/下架不是使用者能决定的事。真人拿 JWT 也能到达这个端点, 但会被 403 挡下, 而不是被
 * "反正他也会点对"放过去。
 */
@Slf4j
@Service
public class ApplicationLifecycleService {

    private final ApplicationRepository applications;
    private final ApplicationVersionRepository versions;

    public ApplicationLifecycleService(ApplicationRepository applications,
                                       ApplicationVersionRepository versions) {
        this.applications = applications;
        this.versions = versions;
    }

    /** 应用是否存在——开发者的其它入口(写 manifest)也要问同一个问题。 */
    public boolean exists(String applicationId) {
        return applicationId != null && applications.findById(applicationId).isPresent();
    }

    public ApplicationStatus statusOf(String applicationId) {
        return applications.findById(applicationId)
                .map(ApplicationRecord::statusEnum)
                .orElse(null);
    }

    /**
     * 推进一步。<b>目标状态与当前相同时是幂等的</b> —— 重发一次 PATCH 不该报错,
     * 否则调用方要为了"重试"先读一次状态, 而那一次读与这一次写之间存在竞态。
     */
    @Transactional
    public ApplicationRecord transition(String applicationId,
                                        ApplicationStatus target,
                                        ResolvedPrincipal actor) {
        ApplicationRecord application = applications.findById(applicationId).orElseThrow(() ->
                new SessionException("UNKNOWN_APPLICATION", "应用不存在: " + applicationId));
        if (target == null) {
            throw new SessionException("INVALID_TRANSITION", "缺少目标状态",
                    ActionStatus.INVALID_ARGUMENT);
        }
        requirePlatformActor(actor);

        ApplicationStatus current = application.statusEnum();
        if (current == target) {
            return application;
        }
        if (!current.canMoveTo(target)) {
            // 409 而不是 400: 请求本身没写错, 是目标此刻的状态不允许这件事 —— 调用方该做的是
            // 先把应用推到合法的前驱状态, 而不是改载荷。
            throw new SessionException("ILLEGAL_TRANSITION",
                    "应用 " + applicationId + " 不能从 " + current + " 直接到 " + target
                            + "(合法后继: " + ApplicationStatus.legalSuccessorsOf(current) + ")",
                    ActionStatus.STATE_CONFLICT);
        }

        application.setStatus(target.name());
        applications.save(application);
        int versionsMoved = propagateToVersions(application, target);
        log.info("[Lifecycle] {} {} → {} (由 {}){}", applicationId, current, target,
                actor.typeName() + ":" + actor.principalId(),
                versionsMoved == 0 ? "" : ", 同时推进 " + versionsMoved + " 个版本行");
        return application;
    }

    /**
     * 应用的状态与它的版本行必须一起动, 否则会出现"应用已停用、版本仍在架上"这种谁也不知道
     * 该信哪一份的状态。规则只有两条:
     *
     * <ul>
     *   <li>回到 {@code PUBLISHED} —— 把 {@code latestVersion} 指的那一版也放回架上;</li>
     *   <li>{@code SUSPENDED} / {@code DEPRECATED} —— 把本应用<em>所有</em>在架版本一起撤下
     *       (不只是 latest: 历史版本同样不该被新调用方发现)。</li>
     * </ul>
     *
     * <p>开发者流程中的中间状态({@code DEVELOPING} → … → {@code APPROVED})不在这里处理:
     * 那些状态的版本行本来就还没上架, 没有需要收回的东西。
     */
    private int propagateToVersions(ApplicationRecord application, ApplicationStatus target) {
        List<ApplicationVersionRecord> rows = versions.findByApplicationIdOrderByCreatedAtDesc(
                application.getId());
        int moved = 0;
        for (ApplicationVersionRecord row : rows) {
            boolean shouldMove = switch (target) {
                // 只把"上一次挂起时被我们撤下来的那一版"放回去。条件写成"当前是 SUSPENDED"
                // 而不是"当前是 PUBLISHED": 后者永远不成立(挂起那一步刚把它改成 SUSPENDED),
                // 于是这条分支会变成一段谁也没走到过的死代码 —— 而它的症状是"应用恢复了,
                // 版本还在架下", 也就是恢复了个寂寞。单独被废弃过的历史版本也不该被这次恢复复活。
                case PUBLISHED -> row.getVersion().equals(application.getLatestVersion())
                        && ApplicationStatus.SUSPENDED.name().equals(row.getStatus());
                case SUSPENDED, DEPRECATED -> ApplicationStatus.PUBLISHED.name().equals(row.getStatus());
                default -> false;
            };
            if (!shouldMove) {
                continue;
            }
            row.setStatus(target.name());
            if (target == ApplicationStatus.PUBLISHED && row.getPublishedAt() == null) {
                row.setPublishedAt(LocalDateTime.now());
            }
            versions.save(row);
            moved++;
        }
        return moved;
    }

    private static void requirePlatformActor(ResolvedPrincipal actor) {
        if (actor == null) {
            throw new SessionException("LIFECYCLE_FORBIDDEN", "缺少调用方身份", ActionStatus.DENIED);
        }
        if (actor.type() != PrincipalType.SYSTEM && actor.type() != PrincipalType.APPLICATION) {
            throw new SessionException("LIFECYCLE_FORBIDDEN",
                    "应用生命周期只能由平台或开发者身份的调用方推进, 收到 " + actor.typeName(),
                    ActionStatus.DENIED);
        }
    }
}
