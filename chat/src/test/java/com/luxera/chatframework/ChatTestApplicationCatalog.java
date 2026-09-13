package com.luxera.chatframework;

import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.chat.ApplicationCard;
import com.luxera.companion.contracts.chat.ApplicationCatalogException;
import com.luxera.companion.contracts.chat.ApplicationInvitation;
import com.luxera.companion.contracts.chat.ApplicationLaunchRequest;
import com.luxera.companion.contracts.chat.ApplicationLaunchResponse;
import com.luxera.companion.contracts.chat.ApplicationSessionView;
import com.luxera.companion.contracts.chat.ParticipantView;
import com.luxera.companion.contracts.spi.ApplicationCatalogPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 聊天模块测试用的假应用平台 —— <b>而且是刻意假在一个本仓库从未见过的应用上</b>。
 *
 * <p>这里没有井字棋, 没有五子棋, 没有 {@code game.make_move}。它注册的是一个
 * {@link #APPLICATION_ID} —— 一个真正的第三方应用会有的样子: 一个陌生的 id、
 * 一个陌生的名字、一组陌生的能力。§63 那条"聊天平台不知道任何具体应用"因此不是靠
 * 一句注释成立的, 而是靠<em>这些测试在这样一个应用上全部通过</em>成立的: 只要哪天有人
 * 在聊天侧写下一个真实的 {@code applicationId}, 这个假实现就会立刻漏掉它。
 *
 * <p>它住在 chat-platform 的<em>测试</em>源里(与 {@code ChatPlatformTestApplication}
 * 里那个 {@code CompanionDirectoryPort} 的桩同形), 而不是主源里 —— 主源里放一个假实现,
 * 就等于聊天平台"在没有应用平台时也能跑", 而那不是真的: 它只是不会崩而已。
 */
public class ChatTestApplicationCatalog implements ApplicationCatalogPort {

    /** 一个本仓库里不存在的应用 —— 这正是它存在的意义。 */
    public static final String APPLICATION_ID = "com.example.paper-plane";
    public static final String APPLICATION_NAME = "纸飞机";
    public static final String VERSION = "2.3.1";
    public static final String CAPABILITY = "paper.fold";

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final AtomicInteger tokens = new AtomicInteger();

    /** 每一次 {@code launch} 收到的请求 —— 断言"chat 把 conversationId 传下去了"要用它。 */
    public final List<ApplicationLaunchRequest> launchRequests = new ArrayList<>();

    /** 每一次 {@code launch} 收到的身份 —— 断言"chat 自报了真人身份"要用它。 */
    public final List<InvocationContext> launchContexts = new ArrayList<>();

    /** 每一次 {@code invite} 收到的身份。 */
    public final List<InvocationContext> inviteContexts = new ArrayList<>();

    /** 这个应用此刻能不能开新会话 —— 让测试可以把它拧成"被下架"。 */
    public volatile boolean allowsNewSession = true;

    /**
     * 回到出厂状态。<b>这个方法不是可选的</b> —— 它是一个 Spring 单例, 而
     * {@code @SpringBootTest} 会把同一个上下文(以及同一个实例)交给整个模块的测试类。
     * 一个把 {@code allowsNewSession} 拧成 false 的用例如果不清场, 下一个测试类会在一个
     * "应用已下架"的世界里开始, 而它的失败信息会指向一个与它自己毫无关系的原因。
     */
    public void reset() {
        allowsNewSession = true;
        sessions.clear();
        launchRequests.clear();
        launchContexts.clear();
        inviteContexts.clear();
        tokens.set(0);
    }

    @Override
    public List<ApplicationCard> cards(InvocationContext context) {
        requireIdentity(context);
        return allowsNewSession ? List.of(cardValue()) : List.of();
    }

    @Override
    public Optional<ApplicationCard> card(InvocationContext context, String applicationId) {
        requireIdentity(context);
        // 卡片本身不因下架而消失 —— 它回答的是"这是个什么东西"。
        return APPLICATION_ID.equals(applicationId) ? Optional.of(cardValue()) : Optional.empty();
    }

    @Override
    public List<ApplicationSessionView> sessionsOfConversation(InvocationContext context, String conversationId) {
        requireIdentity(context);
        return sessions.values().stream()
                .filter(s -> conversationId != null && conversationId.equals(s.conversationId))
                .map(Session::view)
                .toList();
    }

    @Override
    public ApplicationSessionView session(InvocationContext context, String sessionId) {
        requireIdentity(context);
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new ApplicationCatalogException("NOT_A_PARTICIPANT",
                    "不在会话 " + sessionId + " 里", ActionStatus.DENIED);
        }
        return session.view();
    }

    @Override
    public ApplicationLaunchResponse launch(InvocationContext context, ApplicationLaunchRequest request) {
        requireIdentity(context);
        launchRequests.add(request);
        launchContexts.add(context);
        if (!allowsNewSession) {
            throw new ApplicationCatalogException("APPLICATION_NOT_AVAILABLE",
                    "应用已下架", ActionStatus.STATE_CONFLICT);
        }
        if (!APPLICATION_ID.equals(request.applicationId())) {
            throw new ApplicationCatalogException("UNKNOWN_APPLICATION",
                    "没有应用 " + request.applicationId(), ActionStatus.NOT_FOUND);
        }
        Session session = new Session(UUID.randomUUID().toString(), request.conversationId(), context.principalId());
        sessions.put(session.id, session);
        return new ApplicationLaunchResponse(session.view(), session.mine());
    }

    @Override
    public ApplicationInvitation invite(InvocationContext context, String sessionId,
                                        String role, Integer maxUses) {
        requireIdentity(context);
        inviteContexts.add(context);
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new ApplicationCatalogException("NOT_A_PARTICIPANT",
                    "不在会话 " + sessionId + " 里", ActionStatus.DENIED);
        }
        String token = "tok-" + tokens.incrementAndGet();
        return new ApplicationInvitation("inv-" + sessionId, sessionId, token, "/join/" + token,
                role == null ? "MEMBER" : role, "CREATED", maxUses, 0, null);
    }

    private ApplicationCard cardValue() {
        return new ApplicationCard(APPLICATION_ID, VERSION, APPLICATION_NAME,
                "折一只会飞的纸飞机", "手工", List.of(CAPABILITY), true, allowsNewSession);
    }

    /**
     * 端口上的身份必须是<em>写下来的</em>真人/Agent, 不能是一个空壳。
     *
     * <p>这条断言在假实现里也要有: 真实的应用平台会拒绝没有 {@code principalType} 的上下文
     * ({@code InternalPrincipalResolver}), 假实现如果来者不拒, 测试就会在一个比生产宽松的
     * 世界里变绿。
     */
    private void requireIdentity(InvocationContext context) {
        if (context == null || context.principalType() == null) {
            throw new ApplicationCatalogException("INTERNAL_PRINCIPAL_TYPE_REQUIRED",
                    "进程内调用必须显式声明 PrincipalType", ActionStatus.DENIED);
        }
    }

    private final class Session {
        private final String id;
        private final String conversationId;
        private final String humanId;

        private Session(String id, String conversationId, String humanId) {
            this.id = id;
            this.conversationId = conversationId;
            this.humanId = humanId;
        }

        private ApplicationSessionView view() {
            return new ApplicationSessionView(id, APPLICATION_ID, VERSION, "ACTIVE", "UNLISTED",
                    "INVITE_ONLY", 1, 8, 1, conversationId, List.of(CAPABILITY),
                    "2026-09-12T00:00:00", "2026-09-12T00:00:00", null, "2026-09-12T00:00:00");
        }

        private ParticipantView mine() {
            return new ParticipantView("p-" + id, PrincipalType.HUMAN, humanId, "OWNER", "ACTIVE", true);
        }
    }
}
