package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationRecord;
import com.luxera.companion.application.domain.DeveloperRecord;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.application.repository.DeveloperRepository;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * LAP v2 R14: <b>开发者门户要调的那五个入口</b> —— 建开发者、建应用、认领应用、挂起开发者。
 *
 * <p>它补上的是 {@code LapDeveloperController} 缺的那一半: R8 只给了"推状态机"与"写 manifest",
 * 而一个第三方要上架, 得先有<b>东西可推</b>。这一轮不做人化的注册流程(邮箱验证码那一套) ——
 * 建开发者仍然是平台侧的动作, 但入口与身份模型就位之后, 门户只要接在它们上面。
 *
 * <h2>id 是谁起的, 这件事在这一层就要管住</h2>
 * <p>{@code application.id} 用的是 manifest 的 {@code identity.id}(如 {@code com.luxera.tictactoe}),
 * 不是 UUID —— 它要出现在 URI、日志、LLM 上下文里。于是"建应用"不是生成一个 id, 而是
 * <b>认领一个 id</b>: 开发者给的是什么, 平台记的就是什么。这带来两条这里必须管住的规则:
 * <ol>
 *   <li><b>id 被认领过就是别人的</b> —— 第二个开发者想建同名应用, 得到 {@code APPLICATION_TAKEN}
 *       而不是"你拿到了一个覆盖了别人那行的应用"。同一行的 id 语义是"世界上只有一个
 *       com.luxera.tictactoe", 这正是发现链与权限判定依赖它的一点;</li>
 *   <li><b>能改应用的人 = 认领它的那个开发者(或平台)</b> —— {@link #requireOwned}。R8 的
 *       {@code ApplicationLifecycleService.requirePlatformActor} 只回答"你是不是平台", 这里
 *       回答"这是不是<b>你的</b>应用": 两个都持服务密钥的开发者, 各自只能动自己的那几行。</li>
 * </ol>
 *
 * <h2>挂起开发者是"收回钥匙", 不是"删数据"</h2>
 * <p>{@code status=SUSPENDED} 之后它不能再建/改应用({@link #requireActive}), 但它名下的应用行、
 * 版本行、审计行一条不动 —— "谁在上架了什么"是要能回答的历史问题, 而历史不归当下管。
 */
@Service
public class DeveloperService {

    private final DeveloperRepository developers;
    private final ApplicationRepository applications;

    public DeveloperService(DeveloperRepository developers, ApplicationRepository applications) {
        this.developers = developers;
        this.applications = applications;
    }

    // ─────────────────────────── 开发者 ───────────────────────────

    /**
     * 建一个开发者身份。
     *
     * <p>{@code ownerUserId} 是自然人(users 表的 id), 不是开发者自己的 id —— 一个自然人可以
     * 持多个开发者身份(公司一个、个人一个), 这正是 {@code DeveloperRecord.ownerUserId} 单独
     * 成列的原因。同一个 owner 建第二个开发者身份是合法的(名字不同即可); 同名且同 owner
     * 是幂等的(返回既有那一行) —— 门户的"再点一次注册"不该得到一个 500。
     */
    @Transactional
    public DeveloperRecord createDeveloper(String ownerUserId, String name) {
        if (!StringUtils.hasText(ownerUserId)) {
            throw new SessionException("INVALID_ARGUMENT", "建开发者需要 ownerUserId",
                    ActionStatus.INVALID_ARGUMENT);
        }
        if (!StringUtils.hasText(name)) {
            throw new SessionException("INVALID_ARGUMENT", "建开发者需要名字",
                    ActionStatus.INVALID_ARGUMENT);
        }
        DeveloperRecord existing = developers.findByOwnerUserId(ownerUserId).stream()
                .filter(row -> name.equals(row.getName()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        DeveloperRecord row = new DeveloperRecord();
        row.setOwnerUserId(ownerUserId);
        row.setName(name);
        row.setStatus("ACTIVE");
        return developers.save(row);
    }

    /** 某个自然人名下的全部开发者身份 —— 门户"我的应用"页从这一问开始。 */
    public List<DeveloperRecord> developersOf(String ownerUserId) {
        return developers.findByOwnerUserId(ownerUserId);
    }

    /** 开发者详情; 不存在就是 {@code UNKNOWN_DEVELOPER} —— 门户不该把"查无此人"当 500 处理。 */
    public DeveloperRecord require(String developerId) {
        return developers.findById(developerId).orElseThrow(() ->
                new SessionException("UNKNOWN_DEVELOPER", "开发者不存在: " + developerId));
    }

    /** 收回钥匙。已建的应用行不动 —— 见类注释。幂等: 已挂起再挂起仍是 200。 */
    @Transactional
    public DeveloperRecord suspend(String developerId) {
        DeveloperRecord row = require(developerId);
        row.setStatus("SUSPENDED");
        return developers.save(row);
    }

    // ─────────────────────────── 应用 ───────────────────────────

    /**
     * 认领一个应用 id —— "建应用"的真实含义, 见类注释。
     *
     * <p>落下来的行是 {@code DRAFT} 且没有 {@code latestVersion}: 一个没有 manifest 的应用
     * 什么都不是, 而"把第一份 manifest 写上去"是另一个入口
     * ({@code ApplicationVersionService.saveManifest}, 也带着自己的校验)。两步分开的理由是
     * 一步做两件事的话, "id 被认领了但 manifest 一半写坏了"这种中间态没有回滚的说法。
     */
    @Transactional
    public ApplicationRecord createApplication(String developerId, String applicationId, String name,
                                               String category) {
        DeveloperRecord developer = requireActive(developerId);
        if (!StringUtils.hasText(applicationId) || applicationId.length() > 128) {
            throw new SessionException("INVALID_ARGUMENT",
                    "应用 id 必填且不超过 128 字符(惯例是反向域名, 如 com.example.paper-plane)",
                    ActionStatus.INVALID_ARGUMENT);
        }
        if (!StringUtils.hasText(name)) {
            throw new SessionException("INVALID_ARGUMENT", "应用名字必填",
                    ActionStatus.INVALID_ARGUMENT);
        }
        if (applications.findById(applicationId).isPresent()) {
            throw new SessionException("APPLICATION_TAKEN",
                    "应用 id 已被认领: " + applicationId, ActionStatus.STATE_CONFLICT);
        }
        ApplicationRecord row = new ApplicationRecord();
        row.setId(applicationId);
        row.setDeveloperId(developer.getId());
        row.setName(name);
        row.setCategory(StringUtils.hasText(category) ? category : null);
        row.setStatus(com.luxera.companion.application.domain.ApplicationStatus.DRAFT.name());
        return applications.save(row);
    }

    /** 这个开发者名下的应用 —— 门户的列表页。不校验活跃: 被挂起的开发者也该能看见自己有什么。 */
    public List<ApplicationRecord> applicationsOf(String developerId) {
        require(developerId);
        return applications.findAll().stream()
                .filter(row -> developerId.equals(row.getDeveloperId()))
                .toList();
    }

    /**
     * 改这一行的应用必须先过这一关 —— 服务里所有"动应用"的入口都调它。
     *
     * <p>{@code actorDeveloperId == null} 表示平台自己(System 级运维): 平台不该被自己造的
     * 归属模型挡在门外 —— 挂起一个违规应用是运营动作, 不是开发动作。
     */
    public ApplicationRecord requireOwned(String applicationId, String actorDeveloperId) {
        ApplicationRecord row = applications.findById(applicationId).orElseThrow(() ->
                new SessionException("UNKNOWN_APPLICATION", "应用不存在: " + applicationId));
        if (actorDeveloperId != null && !actorDeveloperId.equals(row.getDeveloperId())) {
            throw new SessionException("NOT_YOUR_APPLICATION",
                    "应用 " + applicationId + " 属于别的开发者", ActionStatus.DENIED);
        }
        return row;
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private DeveloperRecord requireActive(String developerId) {
        DeveloperRecord row = require(developerId);
        if (!"ACTIVE".equals(row.getStatus())) {
            throw new SessionException("DEVELOPER_SUSPENDED",
                    "开发者 " + developerId + " 已被挂起, 不能再建应用",
                    ActionStatus.DENIED);
        }
        return row;
    }

    /** 供控制器生成关联 id 时用 —— 与 {@code DeveloperRecord.assignId} 同一形状。 */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
