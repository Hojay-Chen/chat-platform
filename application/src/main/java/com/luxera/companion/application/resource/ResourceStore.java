package com.luxera.companion.application.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.action.ResourceAccess;
import com.luxera.companion.application.domain.ResourceRecord;
import com.luxera.companion.application.repository.ResourceRepository;
import com.luxera.companion.contracts.application.ResourceView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * LAP v1: 资源的唯一读写出口。
 *
 * <p>两件事, 两件都不能打折:
 *
 * <ol>
 *   <li><b>写一律走 CAS。</b>没有任何一条路径会做无条件 UPDATE。影响 0 行就是
 *       {@link StateConflictException}, 落在两个 principal 抢同一步棋上就是"其中一个得到干净的
 *       409 并拿到当前棋盘", 而不是随机丢一个子。</li>
 *   <li><b>读认得两种 backing。</b>先看 {@code resource} 表({@code RESOURCE_STORE}), 没有再问
 *       {@link ResourceProjector}({@code APP_OWNED})。一个 URI 只属于一种 backing, 所以这个顺序
 *       不会产生歧义 —— 有行就是行, 没行才轮到投影。</li>
 * </ol>
 *
 * <p><b>为什么不直接暴露给应用写任意 URI。</b>处理器拿到的是
 * {@link #scoped(String, String)}, 一个绑定到"这次调用的应用与会话"的视图。于是归属不变量
 * (资源的 applicationId 必须等于会话的 applicationId)不是一条需要记得去查的规矩, 而是
 * 接口本身没有别的用法。应用想往别人的会话里写资源, 得先绕开这个类型。
 */
@Slf4j
@Service
public class ResourceStore {

    private final ResourceRepository resources;
    private final ObjectMapper objectMapper;
    private final List<ResourceProjector> projectors;

    public ResourceStore(ResourceRepository resources,
                         ObjectMapper objectMapper,
                         List<ResourceProjector> projectors) {
        this.resources = resources;
        this.objectMapper = objectMapper;
        this.projectors = List.copyOf(projectors);
    }

    /**
     * 绑定到一次调用的写句柄。{@code applicationId} / {@code sessionId} 由网关从归属链上解出,
     * 应用拿不到第二个参数口子。
     */
    public ResourceAccess scoped(String applicationId, String sessionId) {
        return new ScopedAccess(applicationId, sessionId);
    }

    // ─────────────────────────── 读 ───────────────────────────

    /** 资源当前状态; 不存在返回空。 */
    public Optional<ResourceView> find(String uri) {
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }
        Optional<ResourceRecord> row = resources.findByUri(uri);
        if (row.isPresent()) {
            return row.map(this::toView);
        }
        for (ResourceProjector projector : projectors) {
            if (projector.supports(uri)) {
                return projector.project(uri);
            }
        }
        return Optional.empty();
    }

    public Optional<ResourceView> find(String uri, String agentHint) {
        return find(uri).map(v -> new ResourceView(v.uri(), v.resourceType(), v.applicationId(),
                v.sessionId(), v.state(), v.version(), v.updatedAt(), agentHint));
    }

    /** 一个会话下的全部资源(一次棋局只有一个; 未来的应用可能有很多)。 */
    public List<ResourceView> bySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<ResourceView> out = new ArrayList<>();
        for (ResourceRecord row : resources.findBySessionId(sessionId)) {
            out.add(toView(row));
        }
        return out;
    }

    public List<ResourceView> byApplication(String applicationId) {
        return resources.findByApplicationId(applicationId).stream().map(this::toView).toList();
    }

    /** 资源行是否真的存在({@code RESOURCE_STORE} 语义); 投影资源不算。 */
    public boolean existsInStore(String uri) {
        return uri != null && !uri.isBlank() && resources.findByUri(uri).isPresent();
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * CAS 写入。<b>必须在调用方的事务内执行</b> —— 业务动作与它的资源变化要么一起提交,
     * 要么一起回滚。所以这里没有 {@code @Transactional}: 加了反而会开一个新事务,
     * 让"落子成功但棋盘没变"变成可能。
     */
    ResourceView writeInStore(String uri, String resourceType, JsonNode state,
                              Long expectedVersion, String applicationId, String sessionId) {
        String stateJson = serialize(state);
        ResourceRecord existing = resources.findByUri(uri).orElse(null);

        if (existing == null) {
            return create(uri, resourceType, stateJson, applicationId, sessionId);
        }

        long expected = expectedVersion != null ? expectedVersion : existing.getStateVersion();
        int changed = resources.compareAndSet(uri, stateJson, expected, LocalDateTime.now());
        if (changed == 0) {
            ResourceRecord current = resources.findByUri(uri).orElseThrow(() ->
                    new StateConflictException(uri, expected, -1, null, resourceType,
                            applicationId, sessionId));
            throw new StateConflictException(uri, expected, current.getStateVersion(),
                    current.getStateJson(), current.getResourceType(),
                    current.getApplicationId(), current.getSessionId());
        }
        reanchorIfStale(uri, existing, sessionId);
        return toView(resources.findByUri(uri).orElseThrow());
    }

    /**
     * 资源行的会话锚跟着平台解出来的会话走。
     *
     * <p>为什么需要这一步: URI 里<em>没有</em> {@code sessionId} 段的资源, 它的会话锚只在写入的
     * 那一刻被定下来。之后那个人原来的会话被回收器收掉、平台给他解出一个新会话时, 资源行还指着
     * 那个死掉的 —— 于是资源照常能读写(网关的第 2 档发现锚不可用会重新解一次),
     * 但 {@code AgentRouteResolver} 会拿着一个空会话去查参与者, <b>一个人也唤不醒, 而且不报错</b>。
     * 这正是 LAP v2 里最难查的一类失败。
     *
     * <p><b>哪一类资源会走到这里。</b> {@code resource.session_id} 从 R5 起就是可空的(见
     * {@code resourcesWithoutASessionAreAllowed}), 所以"没有会话段的资源"是被允许存在的一类,
     * 而不是一个应该消失的历史遗留。但要注意<b>提醒收件箱不是这一类的例子</b>:
     * {@code reminder://owner/{ownerId}} 是 {@code APP_OWNED}, {@code resource} 表里根本没有
     * 它那一行, 也就没有一个"锚"可挂。目前三个内置应用的 URI 模板全都带会话段
     * ({@code game://session/{id}} / {@code gomoku://match/{id}}), 所以这条路径今天由测试
     * ({@code ResourceStoreTest.aResourceWithoutASessionSegmentFollowsTheSessionResolvedLater})
     * 而<em>不是</em>由某个内置应用来走通 —— 它是给"URI 里没有会话段、却又要落库"的那类应用
     * 留着的。
     *
     * <p>只在不一致时才写: 正常路径上一次 UPDATE 都不多发。
     */
    private void reanchorIfStale(String uri, ResourceRecord existing, String sessionId) {
        if (java.util.Objects.equals(existing.getSessionId(), sessionId)) {
            return;
        }
        resources.reanchor(uri, sessionId);
    }

    private ResourceView create(String uri, String resourceType, String stateJson,
                                String applicationId, String sessionId) {
        ResourceRecord fresh = new ResourceRecord();
        fresh.setUri(uri);
        fresh.setResourceType(resourceType == null || resourceType.isBlank() ? applicationId : resourceType);
        fresh.setApplicationId(applicationId);
        fresh.setSessionId(sessionId);
        fresh.setStateJson(stateJson);
        fresh.setStateVersion(1);
        fresh.setStatus(ResourceRecord.STATUS_ACTIVE);
        ResourceRecord saved = resources.saveAndFlush(fresh);
        return toView(saved);
    }

    private String serialize(JsonNode state) {
        try {
            return objectMapper.writeValueAsString(state == null ? objectMapper.createObjectNode() : state);
        } catch (Exception e) {
            throw new IllegalStateException("资源状态无法序列化: " + e.getMessage(), e);
        }
    }

    private ResourceView toView(ResourceRecord row) {
        return new ResourceView(
                row.getUri(),
                row.getResourceType(),
                row.getApplicationId(),
                row.getSessionId(),
                parse(row.getStateJson()),
                row.getStateVersion(),
                toInstant(row.getUpdatedAt()),
                null);
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("[ResourceStore] 资源状态不是合法 JSON, 按空对象处理: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 处理器看得见的写句柄 —— 只有两个方法, 且两个都钉死在这次调用的应用与会话上。
     */
    private final class ScopedAccess implements ResourceAccess {

        private final String applicationId;
        private final String sessionId;

        private ScopedAccess(String applicationId, String sessionId) {
            this.applicationId = applicationId;
            this.sessionId = sessionId;
        }

        @Override
        public ResourceView read(String uri) {
            return find(uri).orElse(null);
        }

        @Override
        public ResourceView write(String uri, String resourceType, JsonNode state, Long expectedVersion) {
            return writeInStore(uri, resourceType, state, expectedVersion, applicationId, sessionId);
        }
    }
}
