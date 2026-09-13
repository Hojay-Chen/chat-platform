package com.luxera.companion.application.web;

import com.luxera.companion.application.domain.SubscriptionRecord;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.SubscriptionService;
import com.luxera.companion.contracts.application.SubscriptionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * LAP v2: <b>订阅</b> —— "这一类资源上的这一类事件我要"。
 *
 * <p>从 v1 的 {@code LapInstallationController} 里拆出来的第三件事。拆的理由与其他两件不同:
 * 安装是<em>没了</em>, 会话与参与者是<em>分家</em>, 而订阅是<em>本来就不属于那里</em> ——
 * 它描述的是会话<em>之内</em>的一个可选行为, 却和安装挤在同一个类里, 于是"订阅是不是也算一种
 * 安装"这个问题在源码结构上一直没有答案。现在有了: 订阅挂在会话上, 与安装无关。
 *
 * <p>{@code sessionId} 允许为空 —— 一条订阅可以只按 URI pattern 匹配。会话被结束时, 挂在上面的
 * 订阅由 {@code SubscriptionService} 自己按 pattern 与新的事件继续匹配, 不做级联撤销:
 * 一条订阅说"这类资源上的这类事件", 它比某一个会话活得久是合理的。
 */
@RestController
@RequestMapping("/api/v1")
public class LapSubscriptionController {

    private final SubscriptionService subscriptions;
    private final PrincipalResolvers principals;

    public LapSubscriptionController(SubscriptionService subscriptions,
                                     PrincipalResolvers principals) {
        this.subscriptions = subscriptions;
        this.principals = principals;
    }

    public record SubscribeRequest(String sessionId,
                                   String resourceUriPattern,
                                   List<String> eventTypes,
                                   String deliveryMode,
                                   Instant expiresAt) {}

    public record SubscriptionResponse(String subscriptionId,
                                       String sessionId,
                                       String principalType,
                                       String principalId,
                                       String resourceUriPattern,
                                       List<String> eventTypes,
                                       String deliveryMode,
                                       String status) {

        static SubscriptionResponse of(SubscriptionRecord row) {
            return new SubscriptionResponse(row.getId(), row.getSessionId(),
                    row.getPrincipalType(), row.getPrincipalId(),
                    row.getResourceUriPattern(),
                    row.getEventTypes() == null || row.getEventTypes().isBlank()
                            ? List.of() : List.of(row.getEventTypes().split(",")),
                    row.getDeliveryMode(), row.getStatus());
        }
    }

    @PostMapping("/subscriptions")
    public SubscriptionResponse subscribe(@RequestBody SubscribeRequest request,
                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        SubscriptionRecord row = subscriptions.subscribe(principal, request.sessionId(),
                new SubscriptionRequest(request.resourceUriPattern(), request.eventTypes(),
                        request.deliveryMode(), request.expiresAt()));
        return SubscriptionResponse.of(row);
    }

    @GetMapping("/subscriptions")
    public List<SubscriptionResponse> subscriptions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return subscriptions.of(principal(authorization, correlationId)).stream()
                .map(SubscriptionResponse::of).toList();
    }

    @DeleteMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> revoke(@PathVariable String subscriptionId,
                                       @RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        subscriptions.revoke(subscriptionId, principal(authorization, correlationId));
        return ResponseEntity.noContent().build();
    }

    private ResolvedPrincipal principal(String authorization, String correlationId) {
        return principals.resolveHeader(authorization,
                correlationId == null || correlationId.isBlank()
                        ? UUID.randomUUID().toString()
                        : correlationId);
    }
}
