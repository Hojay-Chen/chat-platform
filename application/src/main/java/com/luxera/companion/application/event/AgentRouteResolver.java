package com.luxera.companion.application.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.application.action.ActionResolver;
import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.application.repository.SessionParticipantRepository;
import com.luxera.companion.application.resource.ResourceStore;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.PrincipalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * LAP v2: "这条事件该叫醒哪一个数字人" —— 平台侧的答案。
 *
 * <p><b>为什么这件事必须由平台做, 而不能由应用做。</b> 应用知道的是"这盘棋里除了我之外还有谁"
 * (它把这些人写进 {@code data.notifyPrincipalIds}); 应用<em>不可能</em>知道其中谁是 Agent ——
 * 它的代码里不允许出现 Human / Agent 分支, 那是开工前强制约束里的一条。反过来, 平台只需要查一次
 * {@code application_session_participant} 就知道答案。
 *
 * <h2>v1 → v2: 从 installation 查到 participant 查</h2>
 * <p>v1 这里查的是 {@code installation}(这个 principal 装了某个应用且 {@code principal_type=AGENT},
 * 它就是我们这边的数字人)。安装没了之后, 同一个问题换了答案: <b>这场里有哪些 AGENT 参与者</b>。
 * 这不是把表名换了一下 —— 判据从"这个 Agent 装了游戏吗"变成了"<b>这个 Agent 在这局里吗</b>",
 * 于是"唤醒"与"有权动手"第一次是同一件事: 叫醒的人一定在局里, 在局里的人才叫得醒。
 *
 * <p><b>这一处是整次重构里最容易漏的地方。</b> 它住在 {@code event/} 而不是 {@code permission/},
 * 删掉 installation 时编译器不会指着它, 改完权限测试也全绿 —— 但事件路由会<em>静默地一个人也不
 * 唤醒</em>。{@code check-lap.sh} 与 {@code LapEndToEndTest} 各有一条"事件真的到达数字人"的断言
 * 守着它, 那两条断言的唯一职责就是让这个失败变成红的。
 *
 * <p>于是有一条干净的切分:
 * <ul>
 *   <li>应用说 —— "还有谁该知道这件事" ({@code notifyPrincipalIds});</li>
 *   <li>平台答 —— "其中谁是这场里的数字人" (本类), 并把 {@code companionId} 盖进 {@code data};</li>
 *   <li>数字人说 —— "那我就读一下资源看看能做什么" ({@code AgentApplicationFlow})。</li>
 * </ul>
 *
 * <p><b>不猜。</b> 名单里没有这场里的 AGENT 参与者就返回空, 事件不出平台 —— 宁可数字人晚知道,
 * 也不要它因为一条"大概是给它的"事件去读一个不相干的资源。
 *
 * <p><b>两条路, 一条判据。</b> 事件挂在一个<em>会话型</em>资源上时(棋局), 会话由 URI 或资源行
 * 直接给出, 于是问"名单上的人在不在这一个会话里"; 挂在<em>主体型</em>资源上时(提醒收件箱),
 * 它压根不属于任何会话, 于是问"名单上的人在不在这一个应用的某个活跃会话里"。多出来的这条兜底
 * 不是放宽, 而是把同一个问题问全 —— 少了它, 提醒到点的事件会一个人也唤不醒, 而且不报错。
 */
@Slf4j
@Service
public class AgentRouteResolver {

    private final SessionParticipantRepository participants;
    private final ApplicationSessionRepository sessions;
    private final ActionResolver resolver;
    private final ResourceStore resources;

    public AgentRouteResolver(SessionParticipantRepository participants,
                              ApplicationSessionRepository sessions,
                              ActionResolver resolver,
                              ResourceStore resources) {
        this.participants = participants;
        this.sessions = sessions;
        this.resolver = resolver;
        this.resources = resources;
    }

    /** 路由结果: 叫醒谁, 以及它代表哪位用户。 */
    public record Route(String companionId, String userId) {}

    /** 在事件声明的通知名单里, 找出第一个<em>在这场里</em>的数字人。 */
    public Optional<Route> resolve(String applicationId, ApplicationEvent event) {
        if (applicationId == null || event == null) {
            return Optional.empty();
        }
        List<String> notify = notifyPrincipalIds(event.data());
        if (notify.isEmpty()) {
            return Optional.empty();
        }
        String sessionId = sessionIdOf(applicationId, event.target());
        if (sessionId != null) {
            return withinSession(sessionId, notify);
        }
        return byNotifyList(applicationId, notify);
    }

    /** 事件名得出的会话里, 名单上的第一个 AGENT 参与者。 */
    private Optional<Route> withinSession(String sessionId, List<String> notify) {
        for (SessionParticipantRecord participant : participants
                .findBySessionIdAndPrincipalTypeAndStatus(sessionId,
                        PrincipalType.AGENT.name(), SessionParticipantRecord.STATUS_ACTIVE)) {
            if (notify.contains(participant.getPrincipalId())) {
                return Optional.of(new Route(participant.getPrincipalId(), participant.getUserId()));
            }
        }
        return Optional.empty();
    }

    /**
     * 会话解不出来时的兜底: <b>名单上的人此刻在这个应用的某一个活跃会话里吗</b>。
     *
     * <p>为什么需要这一档。{@code reminder://owner/{ownerId}} 这种"主体型"资源不属于任何会话 ——
     * 它活得过任何一个会话, 平台的 {@code resource} 里根本没有它那一行(它是
     * {@code APP_OWNED}, 读的时候由投影器现算)。于是上一条路必然解不出会话, 而<b>解不出会话不等于
     * 没有人该被叫醒</b>: "下周三提醒我交房租"到点了, 那个人的数字人当然要知道。少了这一档,
     * 提醒到点的事件会安安静静地不出平台, 而且不报错。
     *
     * <p>判据仍然只有一条, 只是换了问法: 从"名单上的人在不在这<em>一个</em>会话里"换成
     * "名单上的人在不在这<em>个应用的某个</em>活跃会话里"。两句话问的是同一件事 —— 这个 Agent
     * 此刻在使用这个应用吗。它挡住的仍然是"给一个早就没在用的 Agent 发事件"。
     *
     * <p><b>只在这条兜底路上生效, 不会放宽棋类那条路。</b> 棋局的 URI 里带着 {@code {sessionId}},
     * 第一档必然命中, 于是这里永远轮不到 —— "换一局棋就唤醒错人"在结构上不可能。
     */
    private Optional<Route> byNotifyList(String applicationId, List<String> notify) {
        for (String principalId : notify) {
            for (SessionParticipantRecord membership : participants
                    .findByPrincipalTypeAndPrincipalIdAndStatus(PrincipalType.AGENT.name(),
                            principalId, SessionParticipantRecord.STATUS_ACTIVE)) {
                ApplicationSessionRecord session =
                        sessions.findById(membership.getSessionId()).orElse(null);
                if (session != null && session.active()
                        && applicationId.equals(session.getApplicationId())) {
                    return Optional.of(new Route(membership.getPrincipalId(), membership.getUserId()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 事件挂在哪一个会话上。两条来源, 顺序即优先级 —— 与 {@code ActionGateway} 的会话解析
     * 前两档保持一致: 先看资源行(它记着真实归属), 再看 URI 模板里的 {@code {sessionId}} 段。
     */
    private String sessionIdOf(String applicationId, String target) {
        if (!StringUtils.hasText(target)) {
            return null;
        }
        var existing = resources.find(target).orElse(null);
        if (existing != null && StringUtils.hasText(existing.sessionId())) {
            return existing.sessionId();
        }
        ApplicationManifest manifest = resolver.ownerOf(target).orElse(null);
        if (manifest == null || !manifest.applicationId().equals(applicationId)) {
            return null;
        }
        return resolver.sessionIdIn(manifest, target).orElse(null);
    }

    /** @see ApplicationEvent 里对 {@code data.notifyPrincipalIds} 的约定 */
    public static List<String> notifyPrincipalIds(JsonNode data) {
        if (data == null || !data.path("notifyPrincipalIds").isArray()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode node : data.get("notifyPrincipalIds")) {
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                ids.add(node.asText());
            }
        }
        return ids;
    }

}
