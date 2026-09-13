package com.luxera.companion.application.web;

import com.luxera.companion.application.action.ActionStatusMapper;
import com.luxera.companion.application.manifest.ManifestException;
import com.luxera.companion.application.principal.PrincipalResolver;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * LAP v1: 平台自己的异常 → HTTP, <b>形状与动作响应一致</b>。
 *
 * <p>为什么复用 {@link ActionResponse} 而不是平台的 {@code ApiError}: 调用方(真人前端、Agent、
 * MCP 桥)解析失败时不该先判断"这是不是 LAP 的响应"。同一个 {@code status}/{@code error.code}
 * 结构在成功与失败两条路径上都在, 客户端就只有一套解析逻辑。
 *
 * <p>排在 {@code GlobalExceptionHandler} 之前(它兜底 {@code Exception} → 500)。没有这一条,
 * 一个"你没装这个应用"会以 500 的形式出现在调用方眼前, 而它明明是 403。
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LapExceptionHandler {

    @ExceptionHandler(SessionException.class)
    public ResponseEntity<ActionResponse> handleSession(SessionException e) {
        HttpStatus status = ActionStatusMapper.httpStatus(e.status());
        return ResponseEntity.status(status)
                .body(ActionResponse.failure(e.status(), e.code(), e.getMessage()));
    }

    /**
     * 身份解析失败。401 与 403 的分界(凭据没给对 / 再试也不会好)在
     * {@link ActionStatusMapper#authenticationStatus} 里 —— 与 MCP 面共用同一份判断,
     * 两个传输面在这里只决定错误体的形状。
     */
    @ExceptionHandler(PrincipalResolver.PrincipalException.class)
    public ResponseEntity<ActionResponse> handlePrincipal(PrincipalResolver.PrincipalException e) {
        // ActionStatus 里没有 UNAUTHENTICATED, 而它也不该有: 对权限模型而言两者都是"这个身份
        // 做不了这件事"。HTTP 层面才需要区分 —— 401 让客户端去重拿凭据, 403 让它别白费力气。
        return ResponseEntity.status(ActionStatusMapper.authenticationStatus(e.code()))
                .body(ActionResponse.failure(ActionStatus.DENIED, e.code(), e.getMessage()));
    }

    /**
     * manifest 层面的失败。发布路径上它是 400(内容有问题), 读路径上一般意味着内置资源缺失 ——
     * 那种情况日志里会有堆栈, 但对外仍然是一个可解释的 400, 而不是 500。
     */
    @ExceptionHandler(ManifestException.class)
    public ResponseEntity<ActionResponse> handleManifest(ManifestException e) {
        return ResponseEntity.badRequest()
                .body(ActionResponse.failure(ActionStatus.INVALID_ARGUMENT, e.code(), e.getMessage()));
    }
}
