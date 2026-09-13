package com.luxera.companion.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.domain.ActionInvocationRecord;
import com.luxera.companion.application.domain.InvocationStatus;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ActionInvocationRepository;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * LAP v1: 幂等 —— 真的实现, 不是"收下 key 就丢掉"。
 *
 * <p><b>禁止 check → insert → execute。</b>那是 TOCTOU: 两个并发请求会双双通过检查, 然后同一步
 * 棋落两次。正确次序是<em>让唯一索引当锁</em>:
 *
 * <pre>
 *   tx1: INSERT (principal, key) VALUES (..., 'IN_PROGRESS')   ← 唯一键冲突即"已存在"
 *        COMMIT                                                 ← 必须提交, 否则锁不住
 *   tx2: 执行 handler + 写资源 + 回填终态 response_json         ← 同一个事务
 *        COMMIT
 * </pre>
 *
 * <p><b>为什么终态写在 tx2 里, 而不是另开一个事务回填。</b>因为这样"业务已提交"与"终态已记录"
 * 就是同一件事: 不会出现"棋下完了但调用记录还是 IN_PROGRESS", 也不会出现"调用记成 SUCCESS 但
 * 棋没动"。由此还能推出一个很强的结论, 崩溃恢复直接受益:
 *
 * <blockquote>
 *   一条停在 {@code IN_PROGRESS} 的行, 意味着 tx2 <em>从未提交</em>, 也就是这次动作
 *   <b>确定没有发生</b>。
 * </blockquote>
 *
 * <p>于是重试是安全的(抢占后重新执行), 回收器也能有把握地断言"业务未生效"。方案原文说
 * "无法判定 → 一律 EXPIRED" —— 在"终态另开事务回填"的设计下确实无法判定; 在单事务设计下
 * 可以判定, 这是这个次序换来的东西。
 *
 * <p><b>两个时间阈值, 不能合并</b>:
 * <ul>
 *   <li>{@code invocation-timeout} (默认 60s) —— 超过它认为执行方已死, 允许<em>重试者</em>
 *       用 CAS 抢占并重新执行;</li>
 *   <li>{@code reap-after} (默认 15min) —— 超过它还<em>没人重试</em>, 回收器才把它封成
 *       {@code EXPIRED}。</li>
 * </ul>
 * 合并成一个的话, 回收器会在客户端还来得及重试之前就把键烧掉, 于是"崩溃后重试"永远得到
 * {@code EXPIRED}。
 */
@Slf4j
@Service
public class IdempotencyService {

    private final ActionInvocationRepository invocations;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final Duration invocationTimeout;
    private final Duration reapAfter;

    public IdempotencyService(ActionInvocationRepository invocations,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.lap.invocation-timeout-ms:60000}") long invocationTimeoutMs,
                              @Value("${app.lap.invocation-reap-after-ms:900000}") long reapAfterMs) {
        this.invocations = invocations;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.invocationTimeout = Duration.ofMillis(invocationTimeoutMs);
        this.reapAfter = Duration.ofMillis(reapAfterMs);
    }

    /** 一次幂等认领的结果: 要么继续执行, 要么直接给调用方一个答复。 */
    public record Claim(ActionInvocationRecord record, ActionResponse replay, ActionResponse conflict) {

        public static Claim proceed(ActionInvocationRecord record) {
            return new Claim(record, null, null);
        }

        public static Claim replay(ActionResponse response) {
            return new Claim(null, response, null);
        }

        public static Claim reject(ActionResponse response) {
            return new Claim(null, null, response);
        }

        public boolean shouldExecute() {
            return record != null;
        }
    }

    // ─────────────────────────── 认领 ───────────────────────────

    public Claim claim(String idempotencyKey,
                       ResolvedPrincipal principal,
                       ActionResolution resolution,
                       String sessionId,
                       String requestHash) {
        ActionInvocationRecord fresh = new ActionInvocationRecord();
        fresh.setIdempotencyKey(idempotencyKey);
        fresh.setPrincipalType(principal.typeName());
        fresh.setPrincipalId(principal.principalId());
        fresh.setActionId(resolution.actionId());
        fresh.setApplicationId(resolution.applicationId());
        fresh.setSessionId(sessionId);
        fresh.setRequestHash(requestHash);
        fresh.setStatus(InvocationStatus.IN_PROGRESS.name());
        fresh.setStartedAt(LocalDateTime.now());
        fresh.setAttemptCount(1);
        fresh.setCorrelationId(principal.correlationId());

        try {
            ActionInvocationRecord saved = requiresNew.execute(status -> {
                invocations.saveAndFlush(fresh);
                return fresh;
            });
            return Claim.proceed(saved);
        } catch (DataIntegrityViolationException e) {
            // 唯一键把并发的第二个请求挡在了这里 —— 这就是"让索引当锁"。
            return resolveExisting(idempotencyKey, principal, sessionId, requestHash);
        }
    }

    /**
     * 插入撞了唯一键之后, 把<em>那一个</em>行找回来。
     *
     * <p>查找的四列必须与唯一键的四列一样 —— 见
     * {@code ActionInvocationRepository#findByPrincipalTypeAndPrincipalIdAndSessionIdAndIdempotencyKey}。
     */
    private Claim resolveExisting(String idempotencyKey,
                                  ResolvedPrincipal principal,
                                  String sessionId,
                                  String requestHash) {
        ActionInvocationRecord existing = invocations
                .findByPrincipalTypeAndPrincipalIdAndSessionIdAndIdempotencyKey(
                        principal.typeName(), principal.principalId(), sessionId, idempotencyKey)
                .orElse(null);
        if (existing == null) {
            // 插入失败但查不到 —— 只可能是并发删除之类的非常规情况。保守拒绝, 不冒险执行。
            log.warn("[Idempotency] 插入冲突但查不到既有行 key={} principal={}",
                    idempotencyKey, principal.principalId());
            return Claim.reject(ActionResponse.failure(ActionStatus.IDEMPOTENCY_IN_PROGRESS,
                    "IDEMPOTENCY_IN_PROGRESS", "同 key 的请求正在进行中"));
        }

        if (!requestHash.equals(existing.getRequestHash())) {
            // 同一个 key 配不同载荷是客户端 bug; 静默按第一次的结果返回会让它极难排查。
            return Claim.reject(ActionResponse.failure(ActionStatus.IDEMPOTENCY_KEY_REUSED,
                    "IDEMPOTENCY_KEY_REUSED",
                    "该 Idempotency-Key 已用于不同的请求载荷"));
        }

        if (existing.terminal()) {
            return Claim.replay(deserialize(existing));
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minus(invocationTimeout);
        if (existing.getStartedAt() != null && existing.getStartedAt().isBefore(staleBefore)) {
            Integer claimed = requiresNew.execute(status -> invocations.claimStale(
                    existing.getId(), InvocationStatus.IN_PROGRESS.name(), staleBefore, now));
            if (claimed != null && claimed == 1) {
                ActionInvocationRecord reloaded = invocations.findById(existing.getId()).orElse(existing);
                log.info("[Idempotency] 抢占超时的 IN_PROGRESS 调用 key={} attempt={}",
                        idempotencyKey, reloaded.getAttemptCount());
                return Claim.proceed(reloaded);
            }
        }
        return Claim.reject(ActionResponse.failure(ActionStatus.IDEMPOTENCY_IN_PROGRESS,
                "IDEMPOTENCY_IN_PROGRESS", "同 key 的请求正在进行中"));
    }

    // ─────────────────────────── 回填 ───────────────────────────

    /**
     * 在<em>调用方的事务内</em>写终态 —— 刻意不加 {@code @Transactional}。
     * 加了就变成"业务一个事务、终态另一个事务", 上面那整段推导全部作废。
     */
    public void completeInCurrentTransaction(String invocationId, ActionResponse response) {
        ActionInvocationRecord row = invocations.findById(invocationId).orElse(null);
        if (row == null) {
            log.warn("[Idempotency] 回填终态时找不到调用行 id={}", invocationId);
            return;
        }
        row.setStatus(response.isSuccess()
                ? InvocationStatus.SUCCESS.name() : InvocationStatus.FAILED.name());
        row.setResponseJson(serialize(response));
        row.setErrorCode(response.error() == null ? null : response.error().code());
        row.setCompletedAt(LocalDateTime.now());
        invocations.flush();
    }

    /** 业务事务已经回滚了, 用独立事务把失败记下来 —— 否则这次调用会永远停在 IN_PROGRESS。 */
    public void markFailedInNewTransaction(String invocationId, String errorCode, String message) {
        try {
            requiresNew.executeWithoutResult(status -> {
                ActionInvocationRecord row = invocations.findById(invocationId).orElse(null);
                if (row == null || row.terminal()) {
                    return;
                }
                row.setStatus(InvocationStatus.FAILED.name());
                row.setErrorCode(errorCode);
                row.setCompletedAt(LocalDateTime.now());
                row.setResponseJson(serialize(ActionResponse.failure(ActionStatus.FAILED,
                        errorCode, message)));
                invocations.flush();
            });
        } catch (Exception e) {
            log.error("[Idempotency] 记录失败终态时又失败了 invocation={}: {}", invocationId, e.getMessage());
        }
    }

    // ─────────────────────────── 回收 ───────────────────────────

    /**
     * 崩溃遗留的回收。{@code startedAt} 早于 {@code reapAfter} 且仍无人重试的行置
     * {@code EXPIRED} —— 绝不静默删除。
     *
     * <p>之所以敢断言"业务未生效": 终态与业务写在同一个事务里, 所以还停在 IN_PROGRESS
     * 就意味着那个事务没有提交过(见类注释)。
     */
    public int reapStale() {
        LocalDateTime staleBefore = LocalDateTime.now().minus(reapAfter);
        return requiresNew.execute(status -> {
            var stale = invocations.findByStatusAndStartedAtBefore(
                    InvocationStatus.IN_PROGRESS.name(), staleBefore);
            for (ActionInvocationRecord row : stale) {
                row.setStatus(InvocationStatus.EXPIRED.name());
                row.setErrorCode("INVOCATION_EXPIRED");
                row.setCompletedAt(LocalDateTime.now());
                row.setResponseJson(serialize(ActionResponse.failure(ActionStatus.EXPIRED,
                        "INVOCATION_EXPIRED",
                        "执行进程在事务提交前退出, 该动作确定未生效; 请用新的 Idempotency-Key 重试")));
                log.warn("[Idempotency] 回收崩溃遗留的调用 id={} action={} principal={} 已尝试 {} 次",
                        row.getId(), row.getActionId(), row.getPrincipalId(), row.getAttemptCount());
            }
            invocations.flush();
            return stale.size();
        });
    }

    public Duration invocationTimeout() {
        return invocationTimeout;
    }

    public Duration reapAfter() {
        return reapAfter;
    }

    // ─────────────────────────── 载荷指纹 ───────────────────────────

    /**
     * 请求载荷的规范化指纹。字段按字典序排列 —— {@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}}
     * 是同一个请求, 对它们算出不同的 hash 会让"同 key 重发"被误判成"同 key 不同载荷"。
     */
    public static String requestHash(ObjectMapper mapper, ActionRequest request) {
        try {
            var canonical = new TreeMap<String, Object>();
            canonical.put("action", request.action());
            canonical.put("target", request.target());
            canonical.put("input", canonicalize(mapper, request.input()));
            canonical.put("expectedResourceVersion", request.expectedResourceVersion());
            String json = mapper.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算请求指纹: " + e.getMessage(), e);
        }
    }

    private static JsonNode canonicalize(ObjectMapper mapper, JsonNode input) {
        if (input == null || input.isNull()) {
            return mapper.nullNode();
        }
        if (input.isObject()) {
            List<String> names = new ArrayList<>();
            input.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            ObjectNode out = mapper.createObjectNode();
            for (String name : names) {
                out.set(name, canonicalize(mapper, input.get(name)));
            }
            return out;
        }
        if (input.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            input.forEach(node -> out.add(canonicalize(mapper, node)));
            return out;
        }
        return input;
    }

    // ─────────────────────────── 序列化 ───────────────────────────

    public String serialize(ActionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("[Idempotency] 响应序列化失败, 该次调用将无法重放: {}", e.getMessage());
            return null;
        }
    }

    private ActionResponse deserialize(ActionInvocationRecord row) {
        if (row.getResponseJson() == null || row.getResponseJson().isBlank()) {
            return ActionResponse.failure(ActionStatus.EXPIRED, "INVOCATION_EXPIRED",
                    "该次调用的响应未被保留, 无法重放");
        }
        try {
            return objectMapper.readValue(row.getResponseJson(), ActionResponse.class);
        } catch (Exception e) {
            log.warn("[Idempotency] 重放响应反序列化失败 id={}: {}", row.getId(), e.getMessage());
            return ActionResponse.failure(ActionStatus.FAILED, "REPLAY_DESERIALIZE_FAILED",
                    "该次调用的响应无法重放");
        }
    }

}
