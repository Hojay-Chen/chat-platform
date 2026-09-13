package com.luxera.companion.application.permission;

/**
 * LAP v1: 权限判定的结果。
 *
 * <p>三态而不是两态 —— {@link #confirmationRequired} 是有意留出来的:
 * "可以做, 但要先跟真人确认"既不是允许也不是拒绝, 把 MCP 的 {@code elicit} 与 REST 的
 * 428 Precondition Required 都挂在这一态上。压成布尔值, 中等风险动作要么被静默执行,
 * 要么被静默拒绝, 两种都不对。
 *
 * @param allowed               是否放行(确认态下为 false, 但没有"错误")
 * @param confirmationRequired  需要调用方显式确认后再来一次
 * @param code                  稳定的机器码({@code NOT_A_PARTICIPANT}, {@code RISK_TOO_HIGH}, …)
 * @param message               给人看的说明
 */
public record PermissionDecision(boolean allowed,
                                 boolean confirmationRequired,
                                 String code,
                                 String message) {

    public static final String ALLOW_CODE = "ALLOW";

    public static PermissionDecision allow() {
        return new PermissionDecision(true, false, ALLOW_CODE, null);
    }

    public static PermissionDecision deny(String code, String message) {
        return new PermissionDecision(false, false, code, message);
    }

    public static PermissionDecision confirm(String code, String message) {
        return new PermissionDecision(false, true, code, message);
    }

    /** 与 {@link #confirm} 同义 —— 保留旧名, 让调用点的意图读起来更直接。 */
    public static PermissionDecision requireConfirmation(String code, String message) {
        return confirm(code, message);
    }

    /** 明确被拒(不含"待确认")。 */
    public boolean denied() {
        return !allowed && !confirmationRequired;
    }

    public boolean requiresConfirmation() {
        return confirmationRequired;
    }

    /** {@link #message()} 的别名, 用于"拒绝原因"读起来更顺的调用点。 */
    public String reason() {
        return message;
    }

    /** 审计用的一格: ALLOW / DENY / REQUIRE_CONFIRMATION。 */
    public String auditLabel() {
        if (allowed) return "ALLOW";
        return confirmationRequired ? "REQUIRE_CONFIRMATION" : "DENY";
    }
}
