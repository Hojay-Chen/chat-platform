package com.luxera.companion.application.builtin.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.RecordingApplicationEventSink;
import com.luxera.companion.application.action.ActionGateway;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「到点了」—— <b>提醒功能里唯一的调度器</b>, 也是"订阅不是调度器"这句话的可执行版本。
 *
 * <p>订阅回答的是"发生了一件事之后, 谁想收到"; 而 15:00 提醒交房租的这一刻<em>什么也没发生</em>,
 * 只是时间到了。所以它不能表达成订阅, 只能表达成"扫一遍到点的行"。这个类钉住的就是那个扫描:
 *
 * <ol>
 *   <li><b>只挑 PENDING 且已到点。</b>没到点的不动, 办完/取消了的不动;</li>
 *   <li><b>挑中即翻成 DISPATCHED。</b>于是第二次扫描不会重复挑中同一行 —— 扫描范围有界,
 *       而不是"每五分钟把历史全部重发一遍";</li>
 *   <li><b>发 {@code reminder.due}, 且只有真的叫得醒人才出平台。</b>这场里没有数字人时事件
 *       安静地留在平台里, 不该凭空产生一次投递。</li>
 * </ol>
 *
 * <p>用例里造的时间是<em>写死的</em>而不是 {@code now()} —— 调度器接受一个"现在", 所以
 * "到点了没有"这件事可以被精确地摆出来, 不必等到真的到点。
 *
 * <p><b>v1 → v2: "谁被叫醒"的判据换了。</b> v1 是"这个数字人装了提醒应用吗", v2 是"它在这场里吗"。
 * 于是用例的布景多了一步 —— 把数字人<em>拉进这个人的会话</em>(见 {@link #invite})。这一步不是
 * 测试为了迁就实现加上去的仪式: 它正是线上真实发生的事(用户把数字人请进来), 只是 v1 用一个
 * installation 行糊弄过去了。
 */
@ActiveProfiles("test")
@SpringBootTest
class ReminderDispatchJobTest {

    private static final String APP_ID = "com.luxera.reminder";
    private static final String CREATE = "reminder.create";
    private static final String UPDATE = "reminder.update";
    private static final String COMPLETE = "reminder.complete";
    private static final String DUE_EVENT = "reminder.due";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 15, 0);

    @Autowired
    ReminderDispatchJob job;

    @Autowired
    ReminderItemRepository items;

    @Autowired
    ActionGateway gateway;

    @Autowired
    ParticipantService participantService;

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    RecordingApplicationEventSink events;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * 调度器扫的是<em>整张表</em> —— 它本来就是全局的, 没有"只扫某个人的"这种形态。
     * 于是任何断言精确条数的用例都必须先自己拥有这张表, 否则它测到的是别的用例留下的行。
     * 清空是诚实的做法: 与其把断言放宽成"至少 2 条", 不如让每一轮扫描的范围就是这一条用例摆出来的局面。
     */
    @BeforeEach
    void cleanTheTable() {
        items.deleteAll();
        events.clear();
    }

    // ─────────────────────────── 扫描范围 ───────────────────────────

    @Test
    void onlyRemindersThatAreDueAndPendingAreDispatched() {
        Human owner = newHuman();
        String past = create(owner, "早就该提醒了", "2026-09-12T14:00");
        String exactlyNow = create(owner, "刚好到点", "2026-09-12T15:00");
        String future = create(owner, "还没到", "2026-09-12T16:00");
        String done = create(owner, "已经办完了", "2026-09-12T13:00");
        gateway.execute(ActionRequest.of(COMPLETE, uriOf(owner), idInput(done)), ctx(owner));

        assertEquals(2, job.dispatch(NOW), "到点的两条应当被派发, 其余不动");

        assertEquals(ReminderItem.STATUS_DISPATCHED, status(past));
        assertEquals(ReminderItem.STATUS_DISPATCHED, status(exactlyNow), "恰好等于 now 的也算到点");
        assertEquals(ReminderItem.STATUS_PENDING, status(future), "没到点的不动");
        assertEquals(ReminderItem.STATUS_DONE, status(done), "已经办完的不动");
    }

    @Test
    void nothingHappensWhenNothingIsDue() {
        Human owner = newHuman();
        create(owner, "以后再说", "2027-01-01T09:00");

        assertEquals(0, job.dispatch(NOW));
    }

    /** 挑选是<em>有界</em>的: 派发过的不会在下一轮里被再挑一次。 */
    @Test
    void aDispatchedReminderIsNotPickedUpAgain() {
        Human owner = newHuman();
        create(owner, "交房租", "2026-09-12T14:00");

        assertEquals(1, job.dispatch(NOW));
        assertEquals(0, job.dispatch(NOW.plusMinutes(5)), "第二轮不该再挑中它");
        assertEquals(0, job.dispatch(NOW.plusDays(1)), "一天之后也不该");
    }

    /** 改期到未来会让它重新进入待办 —— "改个时间"是用户最常见的操作。 */
    @Test
    void reschedulingBringsAReminderBackIntoTheScan() {
        Human owner = newHuman();
        String id = create(owner, "交房租", "2026-09-12T14:00");
        assertEquals(1, job.dispatch(NOW));

        ObjectNode input = idInput(id);
        input.put("dueAt", "2026-09-12T18:00");
        gateway.execute(ActionRequest.of(UPDATE, uriOf(owner), input), ctx(owner));

        assertEquals(0, job.dispatch(NOW), "改到未来之后现在还没到点");
        assertEquals(1, job.dispatch(NOW.plusHours(3)), "到了新时间会再响一次");
    }

    // ─────────────────────────── 事件的形状 ───────────────────────────

    /** 到了点, 应用说"这条提醒关着谁" —— 名单里是它自己的 {@code companionId}。 */
    @Test
    void theDueEventSaysWhoTheReminderIsAbout() {
        Human owner = newHuman();
        Agent companion = newAgent();
        String companionId = companion.companionId;
        create(owner, "交房租", "2026-09-12T14:00", companionId);
        invite(owner, companion);

        job.dispatch(NOW);

        ApplicationEvent event = dueEvent();
        assertEquals(DUE_EVENT, event.type());
        assertEquals(APP_ID, event.source());
        assertEquals(uriOf(owner), event.target());
        assertEquals(companionId, event.data().path("notifyPrincipalIds").get(0).asText());
        assertEquals("20260912T1400", event.data().path("dueKey").asText());
        assertTrue(event.data().path("agentTrigger").asBoolean(),
                "到点是这条应用里唯一会唤起数字人的事");
    }

    /**
     * <b>这场里没有数字人时, 事件不出平台。</b>
     *
     * <p>用户自己设的提醒如果没挂任何数字人, 那么"到点了"这件事在平台里成立、在数字人那里不成立 ——
     * 它不是一条给谁的消息。路由答不出收件人, 投递就不该发生。
     *
     * <p>同时断言<em>库里的状态照翻</em>: 扫描与投递是两件事。让扫描因为无人接收而回退, 会让
     * 这条提醒在下一轮被重新挑中, 于是"没人收"变成"每五分钟扫一遍", 而它本来只是"这一条没人收"。
     */
    @Test
    void aDueEventWithNoAgentInTheSessionDoesNotLeaveThePlatform() {
        Human owner = newHuman();
        String id = create(owner, "没人关心的提醒", "2026-09-12T14:00");
        events.clear();

        assertEquals(1, job.dispatch(NOW));

        assertEquals(List.of(), events.events(), "没有数字人时不该有人收到投递");
        assertEquals(ReminderItem.STATUS_DISPATCHED, status(id));
    }

    /**
     * 在这场里的数字人会被叫醒, 而且事件上被盖了 {@code companionId}。
     *
     * <p>这一条是 {@code 应用说"关着谁" → 平台答"其中谁是数字人"}} 这条链路的端到端证据。
     * 应用侧代码里没有一处 Human / Agent 分支 —— 它只是把 {@code companionId} 填进名单,
     * 谁是数字人由平台查参与者表回答。
     *
     * <p><b>这也是 {@code AgentRouteResolver} 唯一的保险丝。</b>它住在 {@code event/} 而不是
     * {@code permission/}, 删掉 installation 时编译器不会指着它 —— 漏改的表现是"事件一个人也
     * 不唤醒", 而权限、动作、资源那一片测试全绿。这条断言红的唯一原因就是那处漏改。
     */
    @Test
    void aDueEventReachesTheAgentThatIsInTheSession() {
        Human owner = newHuman();
        Agent companion = newAgent();
        create(owner, "交房租", "2026-09-12T14:00", companion.companionId);
        invite(owner, companion);
        events.clear();

        job.dispatch(NOW);

        assertEquals(companion.companionId,
                dueEvent().data().path("companionId").asText(),
                "平台把'谁是数字人'的答案盖了进去");
    }

    /** 挂了一个<em>不在这一局里的</em>数字人不算数 —— 路由查的是参与者表, 不是名单本身。 */
    @Test
    void aCompanionThatIsNotInTheSessionIsNotWoken() {
        Human owner = newHuman();
        create(owner, "交房租", "2026-09-12T14:00", "companion-not-invited");
        events.clear();

        job.dispatch(NOW);

        assertEquals(List.of(), events.events(), "不在场里的数字人不是这条事件的对象");
    }

    /**
     * 事件 id 里带着 {@code dueKey}: 同一次到点重发是同一件事, 改了期是另一件事。
     *
     * <p>这是"到点"这种事件的天然困难 —— 它没有"用户点了按钮"这样的唯一动作可依附, 只有一个时刻。
     * 把那个时刻写进 id, 就把"重复扫描"和"又一次到点"分开来了。
     */
    @Test
    void theEventIdCarriesTheDueKey() {
        Human owner = newHuman();
        Agent companion = newAgent();
        String id = create(owner, "交房租", "2026-09-12T14:00", companion.companionId);
        // v2: 事件能不能出平台, 判据是"这个数字人在不在这场里"。先动一次再拉它进来 ——
        // 见类注释里"v1 → v2: '谁被叫醒'的判据换了"。
        invite(owner, companion);

        job.dispatch(NOW);
        String first = dueEvent().id();
        assertTrue(first.endsWith("20260912T1400"), "id 里应当看得见是哪一次: " + first);

        ObjectNode input = idInput(id);
        input.put("dueAt", "2026-09-12T18:00");
        gateway.execute(ActionRequest.of(UPDATE, uriOf(owner), input), ctx(owner));
        events.clear();
        job.dispatch(NOW.plusHours(4));

        assertNotEquals(first, dueEvent().id(), "改期后的到点是另一次到点");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private record Human(String principalId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
        }
    }

    private record Agent(String companionId, String userId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId, userId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_INTERNAL);
        }
    }

    private Human newHuman() {
        return new Human(UUID.randomUUID().toString());
    }

    private Agent newAgent() {
        return new Agent("companion-" + UUID.randomUUID(), UUID.randomUUID().toString());
    }

    /**
     * 这个人在这条应用里的会话 —— <b>从平台的记账里取, 不从资源行上取</b>。
     *
     * <p>提醒收件箱的 URI ({@code reminder://owner/{userId}}) 里没有 {@code {sessionId}} 段, 而它
     * 又是主体型资源({@code APP_OWNED}, {@code resource} 里没有它那一行), 所以资源上<em>没有</em>
     * 会话锚可读。网关的做法是按"这个人最近的活动会话"解一个 —— 这里调的就是那个方法(会话解析
     * 第 4 档), 只是站在测试这一侧问一遍。
     *
     * <p>事件路由走的也是同一个问题的另一种问法, 见
     * {@code AgentRouteResolver#byNotifyList}。
     */
    private String sessionOf(Human owner) {
        return sessionService.findLive(APP_ID, owner.principal())
                .orElseThrow(() -> new AssertionError(
                        "动过一次之后, 这个人应当已经有一个提醒应用的会话"))
                .getId();
    }

    /**
     * 把数字人请进这个人的会话 —— <b>必须在这条提醒建好之后调用</b>, 因为会话是随第一次动作才有的。
     *
     * <p>v1 这里是一行 installation(给 Agent 装个应用), v2 是一行参与者。判据换了, 但用例想说的
     * 事情一模一样: <em>数字人得先在场, 到点的那条事件才找得到它</em>。
     */
    private void invite(Human owner, Agent companion) {
        participantService.join(sessionOf(owner), companion.principal(),
                SessionParticipantRecord.ROLE_MEMBER, true);
    }

    private static String uriOf(Human human) {
        return ReminderApplication.uriOf(human.principalId());
    }

    private String create(Human owner, String title, String dueAt) {
        return create(owner, title, dueAt, null);
    }

    private String create(Human owner, String title, String dueAt, String companionId) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("title", title);
        input.put("dueAt", dueAt);
        if (companionId != null) {
            input.put("companionId", companionId);
        }
        return gateway.execute(ActionRequest.of(CREATE, uriOf(owner), input), ctx(owner))
                .result().path("id").asText();
    }

    private String status(String reminderId) {
        return items.findById(reminderId).orElseThrow().getStatus();
    }

    /** 最后一条投递出去的 {@code reminder.due} —— 用例都通过"数字人收到了什么"来观察调度结果。 */
    private ApplicationEvent dueEvent() {
        List<ApplicationEvent> matching = events.eventsOfType(DUE_EVENT);
        assertTrue(!matching.isEmpty(), "没有收到 " + DUE_EVENT + " 投递: " + events.events());
        return matching.get(matching.size() - 1);
    }

    private ObjectNode idInput(String reminderId) {
        return objectMapper.createObjectNode().put("reminderId", reminderId);
    }

    private static InvocationContext ctx(Human human) {
        return human.principal().toInvocationContext();
    }
}
