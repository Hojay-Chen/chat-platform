package com.luxera.companion.application.web;

import com.luxera.companion.application.action.ActionGateway;
import com.luxera.companion.application.action.ActionStatusMapper;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * LAP v1: <b>动作的唯一下达处</b> —— {@code POST /api/v1/actions:execute}。
 *
 * <p>真人 UI、Agent(经 DH)、MCP 客户端最终都落到这一个方法上。控制器本身不做任何判断:
 * 不判权限、不判幂等、不判归属, 一律交给 {@link ActionGateway} —— 传输层多一条
 * "顺手也检查一下"的分支, 就是协议出现第二个事实来源的开始。
 *
 * <p><b>Canonical 与 Alias。</b>{@code /actions:execute} 是正式协议(RFC 3986 里 {@code :}
 * 是合法的路径段字符), {@code /actions/execute} 只是给那些会被冒号噎住的反代/客户端留的后门。
 * 两者映射到同一个方法, 行为逐字节相同 —— 新客户端只用 Canonical。
 *
 * <p><b>幂等键从请求头来, 不在这里派生。</b>没带 {@code Idempotency-Key} 的写请求是客户端 bug,
 * 应当拿到 400; 替它派生一个键会把"忘了带"永远藏起来。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class LapActionController {

    private final ActionGateway gateway;
    private final PrincipalResolvers principals;

    public LapActionController(ActionGateway gateway, PrincipalResolvers principals) {
        this.gateway = gateway;
        this.principals = principals;
    }

    @PostMapping({"/actions:execute", "/actions/execute"})
    public ResponseEntity<ActionResponse> execute(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) ActionRequest request) {

        String correlation = correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;

        ResolvedPrincipal principal =
                principals.resolveHeader(authorization, correlation);

        ActionGateway.ActionExecution execution =
                gateway.execute(request, principal, idempotencyKey);

        HttpStatus status = ActionStatusMapper.httpStatus(execution.response().status());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (execution.replayed()) {
            // 让客户端(和运维)看得出来这次没有真的执行 —— 重放与首次执行若无法区分,
            // "为什么同一步棋发了两遍"就只能靠翻数据库回答。
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(execution.response());
    }
}
