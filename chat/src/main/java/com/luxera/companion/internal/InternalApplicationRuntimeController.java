package com.luxera.companion.internal;

import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * chat 平台对仿真 Agent 平台开放的服务间端点 —— {@code ApplicationRuntimePort} 的
 * HTTP 面。仓 2 {@code HttpApplicationRuntimeAdapter} 调用这里。
 *
 * <p>一行委托到 {@code ActionGateway}（真人/Agent/MCP 客户端共用的唯一入口）——
 * 经端口类型引用。Gateway 内部按 {@link InvocationContext} 解析 principal（AGENT）,
 * 数字人经此入口与真人同路同权, 幂等键作用域 = principal。
 */
@RestController
@RequestMapping("/internal/runtime")
public class InternalApplicationRuntimeController {

    private final ApplicationRuntimePort runtime;

    public InternalApplicationRuntimeController(ApplicationRuntimePort runtime) {
        this.runtime = runtime;
    }

    // ── 读 ─────────────────────────────────────────────────────────────────

    @GetMapping("/capabilities")
    public List<CapabilityView> capabilities() {
        return runtime.capabilities();
    }

    @GetMapping("/capabilities/{capabilityId}/applications")
    public List<ApplicationView> applicationsFor(@PathVariable String capabilityId) {
        return runtime.applicationsFor(capabilityId);
    }

    @GetMapping("/applications/{applicationId}/actions")
    public List<ActionSpec> actionsOf(@PathVariable String applicationId) {
        return runtime.actionsOf(applicationId);
    }

    @GetMapping("/resources")
    public Optional<ResourceView> read(@RequestParam String uri) {
        return runtime.read(uri);
    }

    @GetMapping("/pending-actions")
    public List<ActionSpec> pendingActions(@RequestParam String uri,
                                           @RequestBody(required = false) InvocationContextBody body) {
        return runtime.pendingActions(uri, body == null ? null : body.context());
    }

    @GetMapping("/applications/{applicationId}/sessions")
    public List<SessionRef> sessionsOf(@PathVariable String applicationId,
                                       @RequestBody(required = false) InvocationContextBody body) {
        return runtime.sessionsOf(applicationId, body == null ? null : body.context());
    }

    // ── 写/变更 ────────────────────────────────────────────────────────────

    @PostMapping("/actions")
    public ActionResponse execute(@RequestBody ExecuteBody body) {
        return runtime.execute(body.request(), body.context());
    }

    @PostMapping("/sessions/ensure")
    public Map<String, String> ensureSession(@RequestBody EnsureSessionBody body) {
        String sessionId = runtime.ensureSession(body.applicationId(), body.context());
        return Map.of("sessionId", sessionId);
    }

    @PostMapping("/invitations/join")
    public Map<String, String> joinByInvitation(@RequestBody JoinInvitationBody body) {
        String sessionId = runtime.joinByInvitation(body.token(), body.context());
        return Map.of("sessionId", sessionId == null ? "" : sessionId);
    }

    @PostMapping("/sessions/{sessionId}/join")
    public void joinSession(@PathVariable String sessionId,
                           @RequestBody InvocationContextBody body) {
        runtime.joinSession(sessionId, body == null ? null : body.context());
    }

    @PostMapping("/sessions/{sessionId}/leave")
    public void leaveSession(@PathVariable String sessionId,
                            @RequestBody InvocationContextBody body) {
        runtime.leaveSession(sessionId, body == null ? null : body.context());
    }

    // ── 请求体 ──────────────────────────────────────────────────────────────

    public record InvocationContextBody(InvocationContext context) {}
    public record ExecuteBody(ActionRequest request, InvocationContext context) {}
    public record EnsureSessionBody(String applicationId, InvocationContext context) {}
    public record JoinInvitationBody(String token, InvocationContext context) {}
}
