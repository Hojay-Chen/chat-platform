package com.luxera.companion.client;

import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.client.ClientConversation;
import com.luxera.companion.contracts.client.ClientMessage;
import com.luxera.companion.contracts.client.ContactProfile;
import com.luxera.companion.contracts.client.ConversationNotificationSetting;
import com.luxera.companion.contracts.client.MessagePage;
import com.luxera.companion.contracts.client.SendResult;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationParticipant;
import com.luxera.companion.conversation.ConversationParticipantRepository;
import com.luxera.companion.conversation.ConversationReadState;
import com.luxera.companion.conversation.ConversationReadStateService;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageCoreService;
import com.luxera.companion.conversation.MessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V2.2 §6.3 —— 聊天列表、读消息、发消息、已读、免打扰、联系人资料。
 *
 * <h2>寻址用账号, 不用会话 id</h2>
 *
 * <p>六个动作全都以 {@code accountId}(<b>对方的聊天账号</b>)寻址, 而不是会话 id。这不是
 * 路径好看: 真人从来不说"我要打开会话 {@code conv-9f3a}", 他说的是"我要找小满说话"。会话 id
 * 是平台的内部关联字段, 客户端只在把 {@link com.luxera.companion.contracts.client.NotificationSignal}
 * 对到某一行的时候用它。
 *
 * <p>寻址方式的改变顺带把鉴权变成结构性的: {@link #resolve} <b>不是</b>"拿会话 id 查出来再
 * 判它归不归我", 而是"从**我这个账号参与的会话**里找那个对方账号是我的谁" —— 找不到就是
 * 404。一段不属于我的会话根本没有出现在候选集里的机会, 于是"判归属"这件事没有写漏的余地。
 *
 * <h2>真人说话与 Agent 说话走的是两条写入链(这不是"共用接口"的反例)</h2>
 *
 * <p>接口是同一个, 但两侧的**语义**本来就不同, 而这个不同在库里、在前端、在认知链上都是
 * 真实的:
 *
 * <ul>
 *   <li><b>真人打字</b> → {@link MessageCoreService#send}: 消息落库 → 会话事件流 → 通知
 *       Agent 平台"有人说话了"。它是**发起**。走这条路还有一个必需的原因: 只有它会把
 *       {@code sender_id} 显式写成那个真人(见 {@code ConversationService.deriveSenderId} ——
 *       {@code user} 侧刻意不推导), 而 {@code sender_id} 正是通知信号里的
 *       {@code fromAccountId}。写成别的路径会让信号里"谁发的"变成 null。</li>
 *   <li><b>Agent 打字</b> → {@link ChatWorldPort#append}: 它是**回复**, 不触发任何认知,
 *       而且必须走与数字人平台逐字相同的那条路 —— 否则同一个 Agent 从两条路说的话, 一条
 *       实时出现在对方屏幕上, 另一条要刷新才有。</li>
 * </ul>
 *
 * <p>换言之, 分叉的不是权限, 是**方向**。权限侧两边完全一样(都只认
 * {@link ClientPrincipal#accountId()}), 所以 §6.4 的"共用一套接口"没有被打折。
 *
 * <h2>为什么列表页没有"最后一条消息"的预览</h2>
 *
 * <p>见 {@link ClientConversation} 的说明: 正文只能从 {@link #page} 那条路出来。
 * 这不是漏了, 是 §8.2.4 要求在实现上把"她还没读就知道内容"这个状态去掉。
 */
@Slf4j
@Service
public class ClientConversationService {

    /** 不传 limit 时的一屏条数 —— 与 §6.3 第 4 项的示例同一个值。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** 上限。没有它, {@code ?limit=1000000} 就是一次内存放大的入口。 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 第一页的游标: 一个远期时刻 + 空 id。见 {@code MessageRepository.pageBefore}。 */
    private static final LocalDateTime FIRST_PAGE_AT = LocalDateTime.of(9999, 12, 31, 23, 59);
    private static final String FIRST_PAGE_ID = "";

    private final ConversationService conversations;
    private final ConversationReadStateService readStates;
    private final ConversationParticipantRepository participants;
    private final MessageRepository messages;
    private final MessageCoreService messageCore;
    private final ChatWorldPort chatWorld;
    private final UserRepository users;

    public ClientConversationService(ConversationService conversations,
                                     ConversationReadStateService readStates,
                                     ConversationParticipantRepository participants,
                                     MessageRepository messages,
                                     MessageCoreService messageCore,
                                     ChatWorldPort chatWorld,
                                     UserRepository users) {
        this.conversations = conversations;
        this.readStates = readStates;
        this.participants = participants;
        this.messages = messages;
        this.messageCore = messageCore;
        this.chatWorld = chatWorld;
        this.users = users;
    }

    // ── 第 3 / 10 项: 会话列表 (+ 搜索) ──────────────────────────────────────

    /**
     * 「我参与的全部会话」, 最近说过话的在前。
     *
     * <p>走 {@code ConversationService.listForMember} —— <b>同一份数据, 前端与 Agent 各看到
     * 自己那一份</b>。这个方法刻意与聊天 tab 那个 {@code /api/conversations} 用同一个来源:
     * 两个面给同一个账号的列表必须是同一份, 否则"聊天软件里看到的"与"这里看到的"会漂移,
     * 而那种漂移只有把两个界面对着看才看得出来。
     *
     * <p>读状态<b>一次取回</b>再在内存里对({@code statesOf}), 不是每个会话查一次 ——
     * 与 {@code ConversationService.summariesFor} 同一条理由: 列表页的会话数会一直涨, 而
     * "循环里查一次"在三个会话时看不出慢。
     *
     * @param q 搜索词, 可空。匹配**对方账号 id** 与会话标题 ——
     *          <p>刻意<b>不</b>按昵称匹配: 平台侧的昵称是"备注体系"的一部分, 而 §6.3 第 9 项
     *          已经说了"这个人叫什么"是她自己的知识, 平台不该主动把它变成检索入口。
     */
    @Transactional(readOnly = true)
    public List<ClientConversation> list(ClientPrincipal me, String q) {
        Map<String, ConversationReadState> states = readStates.statesOf(me.accountId());
        LocalDateTime now = LocalDateTime.now();
        String needle = StringUtils.hasText(q) ? q.trim().toLowerCase(java.util.Locale.ROOT) : null;

        List<ClientConversation> out = new ArrayList<>();
        for (Row row : rowsOf(me)) {
            Conversation conv = row.conversation();
            if (needle != null && !matches(needle, row.peerAccountId(), conv.getTitle())) continue;
            ConversationReadState s = states.get(conv.getId());
            out.add(new ClientConversation(
                    conv.getId(),
                    row.peerAccountId(),
                    s == null ? 0 : s.getUnreadCount(),
                    conv.getLastMessageAt(),
                    ConversationReadStateService.isPinned(s),
                    // 免打扰取的是**此刻**的判定(和列表页同一条规则): 一个已经过去的
                    // mutedUntil 不该让这一行永远显示"免打扰中"。
                    ConversationReadStateService.isMuted(s, now)));
        }
        return out;
    }

    // ── 第 4 / 5 项: 读消息(分页) ────────────────────────────────────────────

    /**
     * 「点开某人」与「上翻拉更多」是同一个端点, 区别只在于后者带 {@code cursor}。
     *
     * <p>顺序是**到序**(最新在前) —— 与真实聊天软件一致: 点开一个人看到的是最近说的话, 想看
     * 更早的要往上翻。所以第一页从最新往回取, {@code nextCursor} 指向"这一页之前"。
     *
     * @param limit  可空, 默认 {@value #DEFAULT_PAGE_SIZE}, 上限 {@value #MAX_PAGE_SIZE}
     * @param cursor 上一页返回的 {@code nextCursor}; 首页不传
     */
    @Transactional(readOnly = true)
    public MessagePage page(ClientPrincipal me, String peerAccountId, Integer limit, String cursor) {
        Conversation conv = resolve(me, peerAccountId);
        int size = limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        Cursor from = Cursor.decode(cursor);

        // 多取一条来判"还有没有" —— 比再发一次 count 查询便宜, 而且与这一页读的是同一个时刻的
        // 库: count + 分页两次查询之间落进来一条新消息时, 那个 count 就已经是错的了。
        List<Message> fetched = messages.pageBefore(conv.getId(), from.beforeAt(), from.beforeId(),
                PageRequest.of(0, size + 1));
        boolean hasMore = fetched.size() > size;
        List<Message> page = hasMore ? new ArrayList<>(fetched.subList(0, size)) : fetched;

        List<ClientMessage> views = new ArrayList<>(page.size());
        for (Message m : page) views.add(toClientMessage(m));

        String next = hasMore && !page.isEmpty() ? Cursor.encode(page.get(page.size() - 1)) : null;
        return new MessagePage(views, next, hasMore);
    }

    // ── 第 6 项: 打字发送 ────────────────────────────────────────────────────

    /**
     * 「打字发送」—— 见类注释里"真人说话与 Agent 说话走的是两条写入链"。
     *
     * <p>{@code idempotencyKey} 是**可选**的, 但强烈建议给: 它落到
     * {@code messages.client_message_id}(同会话唯一), 于是"超时重发"不会变成两句一样的话。
     * 它是调用方生成的(与一键创建 agent 的 {@code requestId} 同一先例) —— 服务端编不出来,
     * 因为服务端不知道这两次请求是"同一次意图"。
     */
    @Transactional
    public SendResult send(ClientPrincipal me, String peerAccountId, String content,
                           String idempotencyKey) {
        Conversation conv = resolve(me, peerAccountId);
        if (!StringUtils.hasText(content)) {
            throw ClientApiException.badRequest("content 不能为空", null);
        }
        String text = content.trim();
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;

        if (me.isHuman()) {
            MessageCoreService.SendItem item = new MessageCoreService.SendItem();
            item.setContent(text);
            item.setClientMessageId(key);
            MessageCoreService.SendResult sent = messageCore.send(
                    me.accountId(), conv.getCompanionId(), conv.getId(), List.of(item));
            MessageView last = sent.last();
            if (last == null) {
                // 幂等键命中一条**内容为空**的历史行时才会走到这里 —— 那不该发生, 但真发生时
                // 一个 500 比一个 "messageId: null" 更容易被运维看见。
                throw new IllegalStateException("消息未落库: " + conv.getId());
            }
            return new SendResult(last.getId(), last.getCreatedAt());
        }

        MessageView saved = chatWorld.append(MessageAppendCommand
                .of(conv.getId(), "companion", text)
                .withIdempotencyKey(key));
        return new SendResult(saved.getId(), saved.getCreatedAt());
    }

    // ── 第 7 项: 已读 ────────────────────────────────────────────────────────

    /**
     * 「这一段我读过了」。
     *
     * <p>两件事, 但只在 Agent 那一侧做两件:
     * <ol>
     *   <li>把我在这段会话里的未读数清零 —— 两侧都做, 它决定我自己列表上的角标。</li>
     *   <li>把真人发的消息推进到 {@code READ}(投递状态, 对方据此看到"已读")—— <b>只有
     *       Agent 侧做</b>。真人侧刻意不做, 因为前端那条老路
     *       ({@code ConversationListController.markRead})也不做: 两个面对同一个账号的同一个
     *       动作必须同行为, 否则"从客户端面标已读"会变成一个能让对方看到回执的特权动作。</li>
     * </ol>
     */
    @Transactional
    public void markRead(ClientPrincipal me, String peerAccountId, String lastMessageId) {
        Conversation conv = resolve(me, peerAccountId);
        readStates.markRead(conv.getId(), me.accountId(), lastMessageId);
        if (!me.isHuman()) {
            conversations.markHumanMessagesRead(conv.getId());
        }
    }

    // ── 第 8 项: 免打扰 / 置顶 ───────────────────────────────────────────────

    /**
     * 「消息免打扰」的开关 —— <b>§6.2 那张两层表里的上一层, 聊天平台自己管的那个</b>。
     *
     * <p>{@code PUT} 的语义是整体替换, 所以两个字段一起写、一次事务
     * (见 {@code ConversationReadStateService.setNotification})。写完之后这个账号在这段会话里
     * 的**免打扰判定立刻生效**: 平台在产生通知信号之前现查这一行
     * ({@code ClientNotificationService}), 于是"开了免打扰还在响"这种状态不存在。
     *
     * <p>Agent 能读能改它, 就像真人用微信的"消息免打扰"开关一样 —— 但"要不要发信号"这个
     * 决定由平台做出, 不是由 Agent 自己决定要不要在意(§6.2)。
     */
    @Transactional
    public ConversationNotificationSetting setNotification(ClientPrincipal me, String peerAccountId,
                                                          boolean muted, boolean pinned) {
        Conversation conv = resolve(me, peerAccountId);
        ConversationReadState state =
                readStates.setNotification(conv.getId(), me.accountId(), muted, pinned);
        return new ConversationNotificationSetting(conv.getId(), muted, pinned, state.getUpdatedAt());
    }

    // ── 第 9 项: 联系人资料 ──────────────────────────────────────────────────

    /**
     * 「这个人是谁」—— <b>平台只回答它知道的那么多</b>。
     *
     * <h2>为什么 Agent 这一侧永远是空的 displayName</h2>
     *
     * <p>§6.3 第 9 项与 §4.3.7 说的是同一件事: "聊天账号 ID 对应的是谁, 这是 agent 自己聊天
     * 的时候需要构建的关系网"。Agent 要看的是**它自己**建起来的那个 {@code PersonObject},
     * 而不是平台这边的昵称 —— 后者是"备注", 属于另一个人的界面, 平台不该顺手把它交出去。
     * 于是对 Agent 来说这个接口回答的是"这个账号真实存在, 我们之间有过会话", 而名字由它自己填。
     *
     * <p>真人这一侧相反: 前端要在一屏里把对方的名字显示出来, 而那个名字本来就来自它自己的
     * 登录态, 给它没有新增任何信息。
     *
     * <h2>为什么要求"必须出现在我的会话里"</h2>
     *
     * <p>不加这一条, 这个端点就是一台**账号枚举器**: 拿一个 id 试一次, 存在与不存在立刻可分。
     * 而它对这个接口的用途毫无损失 —— 客户端只会问它列表里出现过的那些账号(§6.3 第 9 项就是
     * "查看某人资料", 而"某人"是从列表里点进来的)。
     *
     * @param avatarUrl 恒为 {@code null}: {@code users} 表里没有头像列, 不编一个出来。
     */
    @Transactional(readOnly = true)
    public ContactProfile contact(ClientPrincipal me, String accountId) {
        if (!StringUtils.hasText(accountId)) {
            throw ClientApiException.badRequest("accountId 不能为空", null);
        }
        boolean known = rowsOf(me).stream().anyMatch(r -> accountId.equals(r.peerAccountId()));
        if (!known) {
            throw ClientApiException.notFound("没有这个账号的会话");
        }
        return new ContactProfile(accountId, me.isHuman() ? displayNameOf(accountId) : null, null);
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    /**
     * 「对方账号是 {@code peerAccountId} 的那一段会话」—— <b>只在我的会话里找</b>。
     *
     * <p>这是客户端面唯一的归属判定, 也是整个类最要紧的一行: 候选集是"我参与的会话",
     * 于是越界不是一个被抓到的错误, 而是一个**找不到的结果**(404)。
     */
    private Conversation resolve(ClientPrincipal me, String peerAccountId) {
        if (!StringUtils.hasText(peerAccountId)) {
            throw ClientApiException.badRequest("accountId 不能为空", null);
        }
        for (Row row : rowsOf(me)) {
            if (peerAccountId.equals(row.peerAccountId())) return row.conversation();
        }
        // 与"这个账号根本没有会话"答同一个东西 —— 分不出来正是要的效果。
        throw ClientApiException.notFound("没有与账号 " + peerAccountId + " 的会话");
    }

    /**
     * 「我这个账号参与的会话」与每一段的"对方账号"。
     *
     * <h2>对方账号怎么来的</h2>
     *
     * <p>先看参与者表: 除我之外的那一行就是对方。参与者行缺失时, 用**与
     * {@code ConversationService.deriveSenderId} 逐字相同的那条回退规则**推:
     * 我是真人那一侧 → 对方是 {@code agent_account_id}(没有则 {@code companion_id});
     * 我是 Agent 那一侧 → 对方是 {@code user_id}。
     *
     * <p>必须用同一条回退规则, 因为这决定了通知信号里的 {@code fromAccountId} 与这里的
     * {@code accountId} 是不是同一个值。两边不一致的症状是"列表里点下去说找不到这个账号",
     * 而两段代码各自看都是对的。
     */
    private List<Row> rowsOf(ClientPrincipal me) {
        List<Conversation> convs = conversations.listForMember(me.accountId());
        List<Row> out = new ArrayList<>(convs.size());
        for (Conversation conv : convs) {
            String peer = peerAccountIdOf(conv, me.accountId());
            if (peer == null) continue; // 一个只剩我自己的会话 —— 列表里不该出现一个点不开的行
            out.add(new Row(conv, peer));
        }
        return out;
    }

    private String peerAccountIdOf(Conversation conv, String myAccountId) {
        for (ConversationParticipant p : participants.findByConversationId(conv.getId())) {
            if (p.getLeftAt() != null) continue;
            if (!myAccountId.equals(p.getMemberId())) return p.getMemberId();
        }
        if (myAccountId.equals(conv.getUserId())) {
            String agentAccount = conv.getAgentAccountId();
            return StringUtils.hasText(agentAccount) ? agentAccount : conv.getCompanionId();
        }
        return conv.getUserId();
    }

    /**
     * 平台这一侧这个人显示成什么名字。
     *
     * <p>顺序是 {@code display_name} → {@code nickname} —— 理由在 {@code User} 里:
     * SIMULATOR 账号的 {@code display_name} 就是那个 Agent 的名字(一键创建时写进去的),
     * 而真人普通账号两者都有时 {@code display_name} 才是最新的那个。
     * <p>两个都空就给 {@code null}, 不退回 {@code username}: 那是登录名, 它同时是
     * {@code /api/auth/login} 的入参 —— 把它当展示名发出去, 等于把一个可用于登录的标识
     * 交给了任何加过它好友的人。
     */
    private String displayNameOf(String accountId) {
        User u = users.findById(accountId).orElse(null);
        if (u == null) return null;
        if (StringUtils.hasText(u.getDisplayName())) return u.getDisplayName();
        return StringUtils.hasText(u.getNickname()) ? u.getNickname() : null;
    }

    private static boolean matches(String needle, String peerAccountId, String title) {
        return (peerAccountId != null && peerAccountId.toLowerCase(java.util.Locale.ROOT).contains(needle))
                || (title != null && title.toLowerCase(java.util.Locale.ROOT).contains(needle));
    }

    /**
     * 库里的一行 → 客户端看到的 {@link ClientMessage}。
     *
     * <h2>{@code kind} 是一次投影, 不是一个新字段</h2>
     *
     * <p>§6.3 给的词表是 {@code TEXT / IMAGE / SYSTEM / APPLICATION_CARD}, 而库里那一列
     * ({@code messages.message_kind}) 是平台自己的消息模式词表
     * ({@code NORMAL / SHORT_ACK / PROACTIVE / FOLLOW_UP / SYSTEM / TOOL_RESULT})。两者不是
     * 同一个东西, 所以这里做一次显式的映射, 而不是把库里的值直接透出去 —— 透出去的话,
     * 客户端会收到 {@code SHORT_ACK} 这种它没有定义过的种类, 而每一种客户端都会各自决定
     * 怎么处理它。
     *
     * <p>{@code IMAGE} <b>刻意不映射</b>: 平台今天没有任何附件存储, 也就是说没有一条消息
     * 真的是图片。编一个永远不会出现的取值出来, 只会让客户端去实现一个它永远测不到的分支。
     *
     * <h2>{@code senderAccountId} 可空</h2>
     *
     * <p>{@code system} 消息没有作者(库里 {@code sender_id} 为空), 而老消息(加列之前写的)
     * 也没有。客户端必须容忍 null —— 这不是疏漏, 是那一列本身可空的性质。
     */
    private static ClientMessage toClientMessage(Message m) {
        return new ClientMessage(
                m.getId(),
                m.getSenderId(),
                clientKindOf(m),
                m.getContent(),
                m.getCreatedAt(),
                m.getDeliveryStatus());
    }

    private static String clientKindOf(Message m) {
        if (m.getMetadata() != null && !m.getMetadata().isEmpty()) return "APPLICATION_CARD";
        return "SYSTEM".equals(m.getMessageKind()) ? "SYSTEM" : "TEXT";
    }

    /** 「一段会话 + 对方的账号 id」—— 只在类内部流转。 */
    private record Row(Conversation conversation, String peerAccountId) {}

    /**
     * 分页游标: {@code <createdAt ISO-8601>|<messageId>}。
     *
     * <h2>为什么是两段而不是一个 messageId</h2>
     *
     * <p>见 {@code MessageRepository.pageBefore} 的说明: id 是随机 UUID(大小与时间无关),
     * 单用时间戳又会在同一毫秒的两条上跳过一条。两段一起才既能寻址、又能定序。
     *
     * <h2>为什么把它当成一个不透明字符串</h2>
     *
     * <p>调用方只该原样回传 {@code nextCursor}。它内部长什么样是**可以改的** —— 今天它是
     * 两段文本, 明天换成 base64 或者一个游标表都不该影响任何客户端。这一条只有把解码写得
     * 足够严格才成立(解不开就是 400, 而不是"尽力解释成一个时间"), 否则一个旧版本的游标
     * 会被新代码悄悄解释成另一个位置, 表现为"翻页翻丢了几条"。
     */
    private record Cursor(LocalDateTime beforeAt, String beforeId) {

        static Cursor decode(String raw) {
            if (!StringUtils.hasText(raw)) return new Cursor(FIRST_PAGE_AT, FIRST_PAGE_ID);
            int bar = raw.indexOf('|');
            if (bar <= 0 || bar == raw.length() - 1) {
                throw ClientApiException.badRequest("cursor 无法解析",
                        "cursor 必须原样回传上一页返回的 nextCursor");
            }
            try {
                return new Cursor(LocalDateTime.parse(raw.substring(0, bar)), raw.substring(bar + 1));
            } catch (Exception e) {
                throw ClientApiException.badRequest("cursor 无法解析",
                        "cursor 必须原样回传上一页返回的 nextCursor");
            }
        }

        static String encode(Message m) {
            return m.getCreatedAt() + "|" + m.getId();
        }
    }
}
