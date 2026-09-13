package com.luxera.companion.application.builtin.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.RecordingApplicationEventSink;
import com.luxera.companion.application.action.ActionGateway;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.EventIdMinter;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.ResourceView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提醒应用 —— 第三个参考应用, 也是唯一一个把状态放在自己表里的。
 *
 * <p>它证明的是棋类证明不了的三件事, 三件都在这个类里被钉住:
 *
 * <ol>
 *   <li><b>主体型资源。</b>提醒的 URI 是 {@code reminder://owner/{ownerId}}, 里面没有
 *       {@code sessionId} 段 —— 平台靠"这一行资源属于哪个会话"来解, 而不是靠 URI 里写着一个。
 *       于是"下周三提醒我交房租"活得过任何一个会话: 会话没了, 平台重新解一个, 资源行跟着换锚,
 *       提醒本身不受影响(见 {@link #theInboxIsAnchoredOnTheOwnersSession})。</li>
 *   <li><b>IDOR 守卫。</b>URI 里的 {@code ownerId} 是<em>调用方给的</em>。权限判定管的是"你能
 *       不能调 {@code reminder.list}", 它不看 URI 里那一段 —— v2 里它连"你在不在这一局"都管了,
 *       但仍然管不到"这个 URI 里的 ownerId 是不是你"。守卫必须在应用里, 而为了证明它<em>真的</em>
 *       在应用里, 这几个用例用的是<b>被请进这一局的旁观者</b>: 平台放行, 应用拒绝。</li>
 *   <li><b>应用自有的业务规则。</b>生日提醒"每年最多一条"是<em>数据</em>的规则, 所以判在数据这里;
 *       数字人的生日服务每天被 cron 叫醒一次, 它没有义务自己记住"今年建过了"。</li>
 * </ol>
 *
 * <p>还有一件被刻意钉住的事: {@code reminder.create} 的 {@code dueAt} <b>原样入库</b>。
 * "用户说 15:00"就必须是 15:00 —— 应用不替用户提前几分钟, 也不替数字人做这个决定。
 */
@ActiveProfiles("test")
@SpringBootTest
class ReminderApplicationTest {

    private static final String APP_ID = "com.luxera.reminder";
    private static final String CREATE = "reminder.create";
    private static final String UPDATE = "reminder.update";
    private static final String COMPLETE = "reminder.complete";
    private static final String CANCEL = "reminder.cancel";
    private static final String LIST = "reminder.list";

    private static final String DUE = "2026-09-12T15:00";

    @Autowired
    ActionGateway gateway;

    @Autowired
    ParticipantService participantService;

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    RecordingApplicationEventSink events;

    @Autowired
    ReminderItemRepository items;

    @Autowired
    ManifestRegistry manifests;

    @Autowired
    ObjectMapper objectMapper;

    // ─────────────────────────── 创建与读回 ───────────────────────────

    @Test
    void aPrincipalCanCreateAndReadBackItsOwnInbox() {
        Human alice = newHuman();

        ActionResponse created = create(alice, "交房租", DUE);
        assertEquals(ActionStatus.SUCCESS, created.status(), String.valueOf(created.error()));
        assertNotNull(created.result().path("id").asText(null), "创建要返回这条提醒的 id");

        ResourceView inbox = inbox(alice);
        assertEquals(1, inbox.state().path("total").asInt());
        assertEquals(1, inbox.state().path("pending").asInt());
        assertEquals("交房租", inbox.state().path("items").get(0).path("title").asText());
        assertEquals(DUE, inbox.state().path("items").get(0).path("dueAt").asText(),
                "用户说 15:00 就是 15:00, 应用不替他挪");
    }

    /** 收件箱是<em>集合型</em>资源: 一个人的全部提醒都在同一条 URI 下, 按时间排序。 */
    @Test
    void theInboxIsACollectionOrderedByDueTime() {
        Human alice = newHuman();

        create(alice, "晚的", "2026-10-01T09:00");
        create(alice, "早的", "2026-09-20T08:00");
        create(alice, "最晚的", "2026-12-31T23:00");

        JsonNode state = inbox(alice).state();
        assertEquals(3, state.path("total").asInt());
        assertEquals("早的", state.path("items").get(0).path("title").asText());
        assertEquals("晚的", state.path("items").get(1).path("title").asText());
        assertEquals("最晚的", state.path("items").get(2).path("title").asText());
        assertEquals("2026-09-20T08:00", state.path("nextDueAt").asText(),
                "nextDueAt 是最近一条<em>待办</em>的时间");
    }

    /** 空收件箱不是一个错误, 也不是一个不存在的资源 —— 它是"这个人还没有任何提醒"。 */
    @Test
    void anEmptyInboxIsAValidResource() {
        Human alice = newHuman();

        ResourceView inbox = inbox(alice);

        assertEquals(0, inbox.state().path("total").asInt());
        assertEquals(0, inbox.state().path("pending").asInt());
        assertTrue(inbox.state().path("nextDueAt").isNull());
        assertEquals(0, inbox.state().path("items").size());
        assertEquals(0L, inbox.version(), "空收件箱的版本是 0");
    }

    /**
     * 收件箱<b>不挂在任何会话上</b> —— 而这不是漏了一处, 是这类资源的定义。
     *
     * <p>{@code reminder://owner/{ownerId}} 是<em>主体型</em>资源: 它属于这个人, 活得过任何一个
     * 会话。给它记一个会话锚等于说"这条收件箱属于当时恰好开着的那一局", 而那既不真也不稳 ——
     * 同一个人在两个会话里各建一条提醒, 收件箱只会有一个, 锚却会有两个候选。
     *
     * <p>于是它连 {@code resource} 表里的一行都没有({@code APP_OWNED}, 读的时候由
     * {@link com.luxera.companion.application.builtin.reminder.ReminderResourceProjector} 现算),
     * {@code sessionId} 恒为空。这条用例把它钉住, 是为了挡住一种很自然的"顺手修一下": 看到空锚
     * 就给它补一个。补上之后棋局那套语义会被错误地搬过来, 而真正的答案在别处 ——
     * 事件路由对这类资源走的是"名单上的人在不在这一个应用的某个活跃会话里"
     * (见 {@code AgentRouteResolver#byNotifyList})。
     *
     * <p>另一半同样重要: <b>资源没有锚, 动作却有会话</b>。网关会按"这个人最近的活动会话"解一个
     * (没有就新建一个, 见会话解析第 4/5 档), 否则权限判定就没有判据, 每一次提醒调用都会新建一个
     * 会话。这里把那一半也断言掉, 两条合起来才是这个 URI 形状的完整形状。
     */
    @Test
    void theInboxIsNotAnchoredOnAnySessionButTheActionStillRunsInOne() {
        Human alice = newHuman();
        create(alice, "交房租", DUE);

        assertNull(inbox(alice).sessionId(),
                "主体型资源不属于任何会话 —— 给它补一个锚是把两类资源混为一谈");
        assertNotNull(sessionOf(alice),
                "但动它的那次调用跑在一个真实的会话里, 否则权限判定无从谈起");
    }

    /** 资源要带上 manifest 里写的 {@code agentHint} —— 数字人靠它知道 state.items 是什么。 */
    @Test
    void theInboxCarriesTheManifestsAgentHint() {
        Human alice = newHuman();

        String hint = inbox(alice).agentHint();

        assertNotNull(hint);
        assertTrue(hint.contains("state.items"), "提示应当说明怎么读这个资源: " + hint);
    }

    // ─────────────────────────── IDOR ───────────────────────────

    /**
     * <b>本类最重要的一条。</b>在场的人<em>不能</em>读别人的收件箱。
     *
     * <p>URI 是调用方给的, 所以 {@code ownerId} 这一段完全可能是别人的 id。权限判定在这一层
     * 帮不上忙 —— 它只看"你能不能调 {@code reminder.list}", 不看 URI 里那一段。
     *
     * <p>v2 让这条断言更锋利了: 这里的 carol 是<b>被请进爱丽丝这一局的旁观者</b>。她通过了平台
     * 那一关(确实在场, 也确实有读权限), 所以接下来拒绝她的只可能是应用自己的归属检查 ——
     * 断言失败时没有第二种解释。
     */
    @Test
    void anInvitedBystanderCannotReadSomeoneElsesInbox() {
        Human alice = newHuman();
        Human carol = newHuman();
        create(alice, "爱丽丝的事", DUE);
        invite(alice, carol);

        ActionResponse peeked = gateway.execute(
                ActionRequest.of(LIST, uriOf(alice), null), ctx(carol));

        assertEquals(ActionStatus.DENIED, peeked.status());
        assertEquals("NOT_RESOURCE_OWNER", peeked.error().code());
    }

    /**
     * <b>外面的人与同席的旁观者, 得到的是同一个拒绝。</b>
     *
     * <p>这一条是刻意写下来的, 为了钉住一件事: 棋局那张"平台管你在不在场、应用管这个 URI 是不是
     * 你的"两层图, <em>不能照搬到主体型资源上</em>。平台看 {@code reminder://owner/alice} 时
     * 没有任何办法知道这段 URI 是关于爱丽丝的 —— ownerId 是应用层的语义, 平台上连这一行资源都
     * 不存在(它是投影出来的)。于是鲍勃来读时, 平台只能按"他自己有没有活跃会话"解一个, 而他有。
     *
     * <p>所以对这类资源<b>只有一层</b>: 应用自己的归属检查。这不是缺陷, 是"URI 里带的是谁的 id"
     * 这件事本来就只能由知道那个 id 是什么意思的一方来判。它同时也是一个警告 —— 如果哪天有人
     * 以为平台还兜着底, 把应用里那行检查删掉, 这条用例会以同样的码红给他看。
     */
    @Test
    void anOutsiderGetsTheSameRefusalAsAnInvitedBystander() {
        Human alice = newHuman();
        Human bob = newHuman();
        Human carol = newHuman();
        create(alice, "爱丽丝的事", DUE);
        create(bob, "鲍勃的事", DUE);
        invite(alice, carol);

        for (Human peeked : List.of(bob, carol)) {
            ActionResponse response = gateway.execute(
                    ActionRequest.of(LIST, uriOf(alice), null), ctx(peeked));
            assertEquals(ActionStatus.DENIED, response.status());
            assertEquals("NOT_RESOURCE_OWNER", response.error().code(),
                    "主体型资源的归属只有应用自己判得了: " + peeked.principalId());
        }
    }

    /** 写路径上的同一条守卫: 同席的旁观者不能在别人的收件箱里创建提醒。 */
    @Test
    void anInvitedBystanderCannotCreateInSomeoneElsesInbox() {
        Human alice = newHuman();
        Human carol = newHuman();
        create(alice, "爱丽丝的事", DUE);
        invite(alice, carol);

        ActionResponse response = gateway.execute(
                ActionRequest.of(CREATE, uriOf(alice), title("替别人建的")), ctx(carol));

        assertEquals(ActionStatus.DENIED, response.status());
        assertEquals("NOT_RESOURCE_OWNER", response.error().code());
        assertEquals(1, inbox(alice).state().path("total").asInt(),
                "被拒的写不该落盘 —— 爱丽丝原来那一条还在, 且只有那一条");
    }

    /**
     * 第二条守卫: 改/删/完成一个<em>别人的</em> {@code reminderId}。
     *
     * <p>光校验 URI 里的 ownerId 是不够的 —— {reminderId} 也是调用方给的。鲍勃完全可以在
     * <em>自己的</em>收件箱 URI 上报一个爱丽丝的提醒 id, 于是 URI 校验通过而操作落到了别人的
     * 数据上。这个用例存在的唯一理由就是那句话。
     */
    @Test
    void aPrincipalCannotTouchAReminderIdThatBelongsToSomeoneElse() {
        Human alice = newHuman();
        Human bob = newHuman();
        String aliceReminderId = create(alice, "爱丽丝的事", DUE).result().path("id").asText();

        for (String action : List.of(UPDATE, COMPLETE, CANCEL)) {
            ActionResponse response = gateway.execute(
                    ActionRequest.of(action, uriOf(bob), reminderId(aliceReminderId)), ctx(bob));

            assertEquals(ActionStatus.NOT_FOUND, response.status(), action + " 不该找到别人的提醒");
            assertEquals("REMINDER_NOT_FOUND", response.error().code(), action);
        }

        assertEquals("PENDING", inbox(alice).state().path("items").get(0).path("status").asText(),
                "爱丽丝的提醒没有被动过");
    }

    // ─────────────────────────── 修改与关闭 ───────────────────────────

    @Test
    void updateChangesTitleNoteAndTime() {
        Human alice = newHuman();
        String id = create(alice, "交房租", DUE).result().path("id").asText();

        ObjectNode input = reminderId(id);
        input.put("title", "交水电费");
        input.put("note", "记得带发票");
        input.put("dueAt", "2026-09-13T10:30");
        assertEquals(ActionStatus.SUCCESS, gateway.execute(
                ActionRequest.of(UPDATE, uriOf(alice), input), ctx(alice)).status());

        JsonNode item = inbox(alice).state().path("items").get(0);
        assertEquals("交水电费", item.path("title").asText());
        assertEquals("记得带发票", item.path("note").asText());
        assertEquals("2026-09-13T10:30", item.path("dueAt").asText());
    }

    @Test
    void completeMarksTheReminderDoneAndIsIdempotent() {
        Human alice = newHuman();
        String id = create(alice, "交房租", DUE).result().path("id").asText();

        assertEquals(ActionStatus.SUCCESS, close(alice, COMPLETE, id).status());
        assertEquals("DONE", inbox(alice).state().path("items").get(0).path("status").asText());
        assertEquals(0, inbox(alice).state().path("pending").asInt(), "办完的提醒不再计入待办");

        // 重复调用安全 —— 数字人可能重试, 用户可能点两次。
        assertEquals(ActionStatus.SUCCESS, close(alice, COMPLETE, id).status());
    }

    @Test
    void cancelMarksTheReminderCancelled() {
        Human alice = newHuman();
        String id = create(alice, "交房租", DUE).result().path("id").asText();

        assertEquals(ActionStatus.SUCCESS, close(alice, CANCEL, id).status());
        assertEquals("CANCELLED", inbox(alice).state().path("items").get(0).path("status").asText());
    }

    /** 已经结束的提醒改不动 —— "改一条已经办完的事"只会让状态变得无法解释。 */
    @Test
    void aClosedReminderCannotBeEdited() {
        Human alice = newHuman();
        String id = create(alice, "交房租", DUE).result().path("id").asText();
        close(alice, COMPLETE, id);

        ObjectNode input = reminderId(id);
        input.put("title", "改一下");
        ActionResponse response = gateway.execute(
                ActionRequest.of(UPDATE, uriOf(alice), input), ctx(alice));

        assertEquals(ActionStatus.FAILED, response.status());
        assertEquals("REMINDER_CLOSED", response.error().code());
    }

    /**
     * 改期 = 重新计时。已经派发过的提醒改到未来必须回到 {@code PENDING}, 否则它永远不会再响 ——
     * "改个时间"是用户最常见的操作之一, 而静默失效是它最坏的失败形态。
     */
    @Test
    void reschedulingADispatchedReminderMakesItPendingAgain() {
        Human alice = newHuman();
        String id = create(alice, "交房租", DUE).result().path("id").asText();
        ReminderItem stored = items.findById(id).orElseThrow();
        stored.setStatus(ReminderItem.STATUS_DISPATCHED);
        items.save(stored);

        ObjectNode input = reminderId(id);
        input.put("dueAt", "2026-10-01T09:00");
        assertEquals(ActionStatus.SUCCESS, gateway.execute(
                ActionRequest.of(UPDATE, uriOf(alice), input), ctx(alice)).status());

        assertEquals(ReminderItem.STATUS_PENDING, items.findById(id).orElseThrow().getStatus());
    }

    // ─────────────────────────── 参数 ───────────────────────────

    @Test
    void aReminderNeedsATitle() {
        Human alice = newHuman();
        ActionResponse response = gateway.execute(
                ActionRequest.of(CREATE, uriOf(alice), dueAt(DUE)), ctx(alice));

        assertEquals(ActionStatus.INVALID_ARGUMENT, response.status());
        assertEquals("TITLE_REQUIRED", response.error().code());
    }

    /**
     * <b>没有时间就不是提醒。</b>数字人的 {@code agentHint} 里写着"用户没给时间就先问一句, 别猜" ——
     * 应用这一侧必须真的拒绝, 否则那句提示只是一句好听的建议, 猜出来的时间照样能落库。
     */
    @Test
    void aReminderNeedsADueTime() {
        Human alice = newHuman();
        ActionResponse response = gateway.execute(
                ActionRequest.of(CREATE, uriOf(alice), title("交房租")), ctx(alice));

        assertEquals(ActionStatus.INVALID_ARGUMENT, response.status());
        assertEquals("DUE_AT_REQUIRED", response.error().code());
    }

    /** 三种写法都收: 用户在对话里怎么说, 数字人就怎么传。 */
    @Test
    void severalNaturalTimeFormatsAreAccepted() {
        Human alice = newHuman();

        assertEquals(ActionStatus.SUCCESS, create(alice, "A", "2026-09-12T15:00").status());
        assertEquals(ActionStatus.SUCCESS, create(alice, "B", "2026-09-12 15:00").status());
        assertEquals(ActionStatus.SUCCESS, create(alice, "C", "2026-09-12T15:00:30").status());
    }

    @Test
    void anUnparseableTimeIsRefused() {
        Human alice = newHuman();

        ActionResponse response = gateway.execute(
                ActionRequest.of(CREATE, uriOf(alice), titleWithDue("交房租", "下周三下午")), ctx(alice));

        assertEquals(ActionStatus.INVALID_ARGUMENT, response.status());
        assertEquals("DUE_AT_REQUIRED", response.error().code(),
                "解析不了的时间不能当成某一天 —— 那会是一条用户没说过的时间");
    }

    // ─────────────────────────── 生日提醒的应用侧规则 ───────────────────────────

    /**
     * 生日提醒"每年最多一条"由<em>应用</em>判定。
     *
     * <p>数字人的生日服务每天被 cron 叫醒一次, 它没有义务自己记住"今年已经建过了"; 而"最多一条"
     * 是关于数据的规则, 数据在这里。让调用方去判, 意味着任何一个新调用方都得重新实现一遍这条规则,
     * 而漏掉的那一个会造出重复的生日提醒。
     */
    @Test
    void aSecondBirthdayReminderForTheSameCompanionIsDedupedByTheApplication() {
        Human alice = newHuman();
        String companionId = "companion-" + UUID.randomUUID();

        String first = create(alice, "生日", "2026-10-01T09:00", ReminderItem.TYPE_BIRTHDAY, companionId)
                .result().path("id").asText();
        String second = create(alice, "生日(又建了一次)", "2026-10-01T09:00",
                ReminderItem.TYPE_BIRTHDAY, companionId).result().path("id").asText();

        assertEquals(first, second, "第二次应当返回已存在的那一条, 而不是新建");
        assertEquals(1, items.findByOwnerPrincipalIdAndCompanionIdAndTypeAndStatus(
                alice.principalId, companionId, ReminderItem.TYPE_BIRTHDAY,
                ReminderItem.STATUS_PENDING).size());
    }

    /** 去重只对<em>同一个</em>伴侣生效: 两个数字人各自的生日提醒是两件事。 */
    @Test
    void birthdayRemindersForDifferentCompanionsCoexist() {
        Human alice = newHuman();

        create(alice, "生日", "2026-10-01T09:00", ReminderItem.TYPE_BIRTHDAY, "companion-a");
        create(alice, "生日", "2026-11-01T09:00", ReminderItem.TYPE_BIRTHDAY, "companion-b");

        assertEquals(2, inbox(alice).state().path("total").asInt());
    }

    /** 去重只对生日生效 —— 用户自己设的两条"交房租"是两条, 不是一条。 */
    @Test
    void twoUserSetRemindersWithTheSameTitleAreStillTwo() {
        Human alice = newHuman();

        create(alice, "交房租", DUE);
        create(alice, "交房租", DUE);

        assertEquals(2, inbox(alice).state().path("total").asInt());
    }

    // ─────────────────────────── 事件 ───────────────────────────

    /**
     * 创建发 {@code reminder.created} —— 但它<em>只出现在响应里, 不出平台</em>。
     *
     * <p>这两件事必须分开看: 应用每次动作都可以陈述"发生了什么"(进 {@code ActionResponse.events},
     * 调用方看得见), 而"要不要叫醒数字人"由 manifest 的 {@code triggersAgent} 决定。创建一条提醒
     * 不是叫醒谁的理由, 所以 {@code RecordingApplicationEventSink}(数字人那扇门)什么也收不到。
     */
    @Test
    void creatingAReminderEmitsAnEventThatDoesNotLeaveThePlatform() {
        Human alice = newHuman();
        events.clear();

        ApplicationEvent event = onlyEvent(create(alice, "交房租", DUE), "reminder.created");

        assertEquals(APP_ID, event.source());
        assertEquals(uriOf(alice), event.target());
        assertNotEquals(true, event.data().path("agentTrigger").asBoolean(),
                "创建事件不该带 agentTrigger");
        assertEquals(List.of(), events.events(), "没声明 triggersAgent 的事件不该推给数字人");
    }

    @Test
    void completingAReminderEmitsItsOwnEvent() {
        Human alice = newHuman();
        String createId = create(alice, "交房租", DUE).result().path("id").asText();

        ApplicationEvent event = onlyEvent(close(alice, COMPLETE, createId), "reminder.completed");

        assertEquals(createId, event.data().path("reminderId").asText());
        assertEquals(ReminderItem.STATUS_DONE, event.data().path("status").asText());
    }

    @Test
    void cancellingAReminderEmitsItsOwnEvent() {
        Human alice = newHuman();
        String id = create(alice, "交房租", DUE).result().path("id").asText();

        ApplicationEvent event = onlyEvent(close(alice, CANCEL, id), "reminder.cancelled");

        assertEquals(id, event.data().path("reminderId").asText());
        assertEquals(ReminderItem.STATUS_CANCELLED, event.data().path("status").asText());
    }

    /**
     * 事件 id 是<em>确定性</em>的, 而且<em>能区分不同的发生</em>。
     *
     * <p>这是去重的地基: 消费者靠事件 id 判断"这件事我是不是已经反应过了"。所以两个性质都要 ——
     * 同一件事重发时 id 必须逐字相同(否则重试会被当成新事件), 两件不同的事 id 必须不同
     * (否则第二次会被静默吞掉)。只满足第一条的 id 方案(比如光用 {@code uri#type})看着能用,
     * 直到同一个收件箱里第二条提醒被办完 —— 那时它安静地消失。
     */
    @Test
    void eventIdsAreDeterministicAndDistinguishSeparateOccurrences() {
        Human alice = newHuman();
        String first = create(alice, "交房租", DUE).result().path("id").asText();
        String second = create(alice, "另一件事", DUE).result().path("id").asText();

        ApplicationEvent doneFirst = onlyEvent(close(alice, COMPLETE, first), "reminder.completed");
        ApplicationEvent doneSecond = onlyEvent(close(alice, COMPLETE, second), "reminder.completed");
        assertNotEquals(doneFirst.id(), doneSecond.id(), "两条不同的提醒不能铸出同一个事件 id");

        // 重发同一件事(同一条提醒、同一个状态) —— id 必须一样。
        assertEquals(doneFirst.id(), EventIdMinter.mint(
                        manifest(), "reminder.completed", uriOf(alice), doneFirst.data()),
                "同一件事重发时事件 id 必须逐字相同, 否则去重失效");
    }

    /**
     * 到点事件的 id 里带着 {@code dueKey} —— 于是"同一条提醒改期后又响了一次"是两个不同的事件,
     * 而"同一次到点被重复扫描"是同一个事件。
     *
     * <p>这正是 {@code triggersAgent} 事件被强制要求 {@code idTemplate} 的原因: 它们会真的
     * 叫醒数字人, 重复送达就是重复行动。
     */
    @Test
    void aDueEventIsIdentifiedByTheTimeItFired() {
        Human alice = newHuman();
        String id = create(alice, "交房租", DUE).result().path("id").asText();
        ReminderItem item = items.findById(id).orElseThrow();

        String atFifteen = dueEventId(alice, item);
        assertEquals(atFifteen, dueEventId(alice, item), "同一个 dueKey 重发时 id 不变");

        item.setDueAt(ReminderApplication.parseTime("2026-09-12T16:00"));
        assertNotEquals(atFifteen, dueEventId(alice, item), "改期后的到点是另一次到点");

        item.setDueAt(ReminderApplication.parseTime(DUE));
        assertEquals(atFifteen, dueEventId(alice, item), "改回来又是原来那一次到点");
    }

    private ApplicationEvent onlyEvent(ActionResponse response, String type) {
        List<ApplicationEvent> matching = response.events().stream()
                .filter(e -> type.equals(e.type()))
                .toList();
        assertEquals(1, matching.size(), "应当恰好有一个 " + type + " 事件: " + response.events());
        return matching.get(0);
    }

    private ApplicationManifest manifest() {
        return manifests.published(APP_ID).orElseThrow();
    }

    /** 走真实的两条路径共用的铸 id 函数 —— 见 {@link ReminderEvents} 的类注释。 */
    private String dueEventId(Human owner, ReminderItem item) {
        return EventIdMinter.mint(manifest(), "reminder.due", uriOf(owner),
                ReminderEvents.data(item, true));
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private record Human(String principalId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
        }
    }

    private Human newHuman() {
        return new Human(UUID.randomUUID().toString());
    }

    /**
     * 把一个人请进 {@code owner} 的会话 —— <b>调用前 {@code owner} 必须已经动过一次</b>
     * (会话是随第一次动作才有的)。
     *
     * <p>会话 id 从<em>平台的记账</em>里取, 不从资源行上取: 收件箱是主体型资源, 它上面没有会话
     * 锚(见 {@link #theInboxIsNotAnchoredOnAnySessionButTheActionStillRunsInOne})。这也正是
     * 查会话解析第 4 档的那个方法本身 —— 平台在别处就是这么做会话解析的。
     *
     * <p>这个夹具的存在是为了让"应用的守卫"与"平台的守卫"能被分开测: 旁观者通过了平台那一关
     * (她确实在局里), 于是断言失败的唯一可能就只剩应用自己那一条。
     */
    private void invite(Human owner, Human guest) {
        participantService.join(sessionOf(owner), guest.principal(),
                SessionParticipantRecord.ROLE_MEMBER, true);
    }

    /** 这个人此刻在这个应用里的活跃会话 id —— 会话解析第 4 档那个问题的直接答案。 */
    private String sessionOf(Human who) {
        return sessionService.findLive(APP_ID, who.principal())
                .orElseThrow(() -> new AssertionError(
                        "动过一次之后, 这个人应当已经有一个提醒应用的会话: " + who.principalId()))
                .getId();
    }

    private static String uriOf(Human human) {
        return ReminderApplication.uriOf(human.principalId());
    }

    private ActionResponse create(Human human, String title, String dueAt) {
        return create(human, title, dueAt, ReminderItem.TYPE_USER_SET, null);
    }

    private ActionResponse create(Human human, String title, String dueAt, String type, String companionId) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("title", title);
        input.put("dueAt", dueAt);
        input.put("type", type);
        if (companionId != null) {
            input.put("companionId", companionId);
        }
        return gateway.execute(ActionRequest.of(CREATE, uriOf(human), input), ctx(human));
    }

    private ActionResponse close(Human human, String action, String reminderId) {
        return gateway.execute(ActionRequest.of(action, uriOf(human), reminderId(reminderId)), ctx(human));
    }

    private ResourceView inbox(Human human) {
        return gateway.read(uriOf(human)).orElseThrow(
                () -> new AssertionError("收件箱资源应当存在: " + uriOf(human)));
    }

    private ObjectNode title(String value) {
        return objectMapper.createObjectNode().put("title", value);
    }

    private ObjectNode dueAt(String value) {
        return objectMapper.createObjectNode().put("dueAt", value);
    }

    private ObjectNode titleWithDue(String title, String due) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("title", title);
        input.put("dueAt", due);
        return input;
    }

    private ObjectNode reminderId(String id) {
        return objectMapper.createObjectNode().put("reminderId", id);
    }

    private static InvocationContext ctx(Human human) {
        return human.principal().toInvocationContext();
    }
}
