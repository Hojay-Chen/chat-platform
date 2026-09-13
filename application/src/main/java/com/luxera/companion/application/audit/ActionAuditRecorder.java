package com.luxera.companion.application.audit;

import com.luxera.companion.application.domain.ApplicationActionLogRecord;
import com.luxera.companion.application.repository.ApplicationActionLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LAP v1: 动作审计。<b>取代 {@code dh_application_action_log}</b>。
 *
 * <p>旧表的 {@code permission_decision} 与 {@code execution_status} 用同一个参数赋值, 于是
 * "权限为什么被拒"这个审计最该回答的问题永远查不到。这里两者分开, 且都是真的:
 * 一次被拒的动作会写下 {@code DENY / NOT_A_PARTICIPANT}, 而不是一行没有信息的噪音。
 *
 * <p><b>为什么一律 {@code REQUIRES_NEW}。</b>审计的职责是记录"发生过什么", 包括那些业务上
 * 失败、回滚、甚至根本没通过权限的尝试。挂在业务事务里的话, 恰恰是最需要留下的那些行会随着
 * 回滚一起消失。独立事务保证: 只要平台走到了这一步, 这一行就一定在。
 *
 * <p>代价是审计行可能与业务结果不一致(业务回滚了但审计写了)。这不构成问题 ——
 * {@code executionStatus} 记的是<em>当时判定出来的结果</em>, 而"当时判定成功但随后回滚"这件
 * 事本身就该被看见。真正的一致性由 {@code action_invocation} 的终态负责。
 */
@Slf4j
@Service
public class ActionAuditRecorder {

    private final ApplicationActionLogRepository logs;

    public ActionAuditRecorder(ApplicationActionLogRepository logs) {
        this.logs = logs;
    }

    /** 一次动作尝试的审计材料。字段都是可空的 —— 权限被拒时还没有 invocation, 也没有资源。 */
    public record AuditEntry(String invocationId,
                             String actionId,
                             String applicationId,
                             String principalType,
                             String principalId,
                             String companionId,
                             String userId,
                             String resourceUri,
                             String permissionDecision,
                             String executionStatus,
                             String errorMessage,
                             String correlationId) {

        public static AuditEntry of(String actionId, String applicationId, String resourceUri) {
            return new AuditEntry(null, actionId, applicationId, null, null, null, null,
                    resourceUri, null, null, null, null);
        }

        public AuditEntry withPrincipal(String type, String id, String companionId, String userId) {
            return new AuditEntry(invocationId, actionId, applicationId, type, id, companionId, userId,
                    resourceUri, permissionDecision, executionStatus, errorMessage, correlationId);
        }

        public AuditEntry withInvocation(String invocationId) {
            return new AuditEntry(invocationId, actionId, applicationId, principalType, principalId,
                    companionId, userId, resourceUri, permissionDecision, executionStatus,
                    errorMessage, correlationId);
        }

        public AuditEntry withPermission(String decision) {
            return new AuditEntry(invocationId, actionId, applicationId, principalType, principalId,
                    companionId, userId, resourceUri, decision, executionStatus,
                    errorMessage, correlationId);
        }

        public AuditEntry withExecution(String status, String errorMessage) {
            return new AuditEntry(invocationId, actionId, applicationId, principalType, principalId,
                    companionId, userId, resourceUri, permissionDecision, status,
                    errorMessage, correlationId);
        }

        public AuditEntry withCorrelation(String correlationId) {
            return new AuditEntry(invocationId, actionId, applicationId, principalType, principalId,
                    companionId, userId, resourceUri, permissionDecision, executionStatus,
                    errorMessage, correlationId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEntry entry) {
        try {
            ApplicationActionLogRecord row = new ApplicationActionLogRecord();
            row.setInvocationId(entry.invocationId());
            row.setActionId(entry.actionId() == null ? "unknown" : entry.actionId());
            row.setApplicationId(entry.applicationId());
            row.setPrincipalType(entry.principalType());
            row.setPrincipalId(entry.principalId());
            row.setCompanionId(entry.companionId());
            row.setUserId(entry.userId());
            row.setResourceUri(truncate(entry.resourceUri(), 256));
            row.setPermissionDecision(entry.permissionDecision());
            row.setExecutionStatus(entry.executionStatus());
            // error_message 列宽 512 —— 不截断的话, 一段长的异常消息会让审计写入自己失败,
            // 于是"动作失败"变成"动作失败且没有任何记录"。
            row.setErrorMessage(truncate(entry.errorMessage(), 512));
            row.setCorrelationId(entry.correlationId());
            logs.save(row);
        } catch (Exception e) {
            // 审计写不进去不该让动作本身失败 —— 但必须留下痕迹。
            log.error("[Audit] 写入动作审计失败 action={} principal={}: {}",
                    entry.actionId(), entry.principalId(), e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }
}
