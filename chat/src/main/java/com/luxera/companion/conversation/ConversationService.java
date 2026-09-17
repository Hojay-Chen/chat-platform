package com.luxera.companion.conversation;

import com.luxera.companion.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V10 §2 — conversation storage. Owned entirely by the chat platform.
 *
 * <p>Knows three things: a conversation has a human side ({@code userId}), an opaque peer side
 * ({@code companionId}) and a list of messages. It does not know that the peer is a digital human,
 * that it has a persona, or that anything decides whether to reply — those questions belong to the
 * digital-human platform, which reaches in through {@code ChatWorldPort}.
 */
@Slf4j
@Service
public class ConversationService {

    private final ConversationRepository convRepo;
    private final MessageRepository msgRepo;
    private final ConversationParticipantService participantService;
    private final ConversationParticipantRepository participantRepo;
    private final ConversationReadStateService readStateService;
    private final SessionManager sessionManager;
    private final AgentChatIdentity agentChatIdentity;

    public ConversationService(ConversationRepository convRepo, MessageRepository msgRepo,
                               ConversationParticipantService participantService,
                               ConversationParticipantRepository participantRepo,
                               ConversationReadStateService readStateService,
                               SessionManager sessionManager,
                               AgentChatIdentity agentChatIdentity) {
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.participantService = participantService;
        this.participantRepo = participantRepo;
        this.readStateService = readStateService;
        this.sessionManager = sessionManager;
        this.agentChatIdentity = agentChatIdentity;
    }

    /**
     * Find-or-create the single thread between a human and a peer. Used by the "open the chat for
     * the first time" flow; the digital human then decides what (if anything) to say first.
     */
    @Transactional
    public Conversation ensureConversation(String userId, String companionId, String companionName) {
        List<Conversation> list = convRepo.findByUserIdAndCompanionIdOrderByLastMessageAtDesc(userId, companionId);
        if (!list.isEmpty()) {
            Conversation existing = list.get(0);
            repairAgentIdentity(existing, companionId, companionName);
            return existing;
        }
        return newConversation(userId, companionId, freshTitle(companionName), companionName);
    }

    /**
     * 「打开一段已有的会话」时顺手把它的 Agent 身份修正过来 —— 只在**确有可修之处**时动手。
     *
     * <h2>为什么这条路径要管身份, 而不只是"确保参与者在"</h2>
     *
     * 因为这是唯一一个**必然会碰到老会话**的入口: 界面上点开一个两年前的对话、或数字人平台
     * 要求打开某个 agent 的线程, 走的都是这里。一次性的回填 runner
     * ({@code ConversationIdentityBackfill}) 把存量改完之后, 还有一小段窗口是它管不到的:
     * 回填写的是"它扫过的那一刻"的库, 而这段会话可能在扫描之后又被建出来(用旧代码的进程还在
     * 跑), 或者它当时因为某种临时原因失败了。
     *
     * <p>把修正放在这里, 系统的正确性就不再依赖"那次迁移跑得完整" —— 它会自己收敛。
     *
     * <h2>只在会话还没有身份记录时动手</h2>
     *
     * 判据是 {@code conv.getAgentAccountId()} 为空, 而不是"参与者行与账号对不上"。后者会让
     * 每一段正常会话每次被打开都多查一次参与者表 —— 而正常会话恰恰是绝大多数。空值则精确地
     * 圈出"这段会话还没被迁移过"。
     *
     * <p>{@code addIfMissing} 那种"确保有"的写法在这里是危险的: 对一个参与者仍写着
     * {@code companionId} 的老会话, 它会**再插一行**账号的参与者 —— 一个会话里两个 agent
     * 参与者, 未读判定会随机落在其中一行上, 而列表页取名字用 {@code putIfAbsent} 又会取到
     * 另一行。
     */
    private void repairAgentIdentity(Conversation existing, String companionId, String companionName) {
        if (existing.getAgentAccountId() != null && !existing.getAgentAccountId().isBlank()) {
            // 已经有身份记录了 —— 什么都不做。会话内容不受影响, 而"参与者行还在不在"是另一
            // 回事(种子的 try/catch 会吞异常), 由 listForMember 的并集兜住。
            return;
        }
        String resolved = agentChatIdentity.chatAccountIdOf(companionId);
        if (resolved == null) {
            // 这个 Agent 还没有聊天账号 —— 保持现状。参与者的 member_id 仍是 companionId,
            // 与 deriveSenderId 的回退值一致, 未读判定照常工作。
            seedParticipants(existing, existing.getUserId(), companionId, companionName);
            return;
        }
        existing.setAgentAccountId(resolved);
        convRepo.save(existing);
        boolean moved = participantService.repointAgent(existing.getId(), companionId, resolved);
        if (!moved) {
            // 没搬成: 要么这个会话还没有 agent 参与者(那就补一行), 要么已经有一行是新 id 了
            // (那正是想要的终态)。补一行是安全的 —— addIfMissing 撞到已有行时什么都不做。
            seedParticipants(existing, existing.getUserId(), resolved, companionName);
        }
        log.info("[会话身份] 会话 {} 的 Agent 身份已修正为账号 {}", existing.getId(), resolved);
    }

    @Transactional
    public Conversation create(String userId, String companionId, String title, String companionName) {
        String resolved = title == null || title.isBlank() ? freshTitle(companionName) : title;
        return newConversation(userId, companionId, resolved, companionName);
    }

    private static String freshTitle(String companionName) {
        return companionName == null || companionName.isBlank() ? "新的对话" : "初见 · " + companionName;
    }

    private Conversation newConversation(String userId, String companionId, String title, String companionName) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setCompanionId(companionId);
        conv.setTitle(title);
        String agentAccountId = agentChatIdentity.chatAccountIdOf(companionId);
        conv.setAgentAccountId(agentAccountId);
        convRepo.save(conv);
        // 参与者的 member_id 与消息的 sender_id 必须是**同一个命名空间**, 否则
        // ConversationReadStateService.bumpOnMessage 那句
        // `p.getMemberId().equals(senderId)` 永远为假 —— Agent 会因为它自己发的每一条消息
        // 收到未读角标。没有账号时退回 companionId: 那是这次迁移之前一直用的值,
        // 也是 deriveSenderId 的回退值, 两边必须同时退回同一个, 才对得上。
        seedParticipants(conv, userId, memberIdOf(companionId, agentAccountId), companionName);
        return conv;
    }

    /**
     * Agent 在会话里的 member_id —— 有账号就用账号, 没有就退回 agent id。
     *
     * <p>写成一个小函数而不是在两处各写一次三元表达式: 这两处(参与者行、消息的 sender_id)
     * **必须**给出同一个答案, 而它们的值来自同一个判断。抄两份的话, 将来只改一处会造出
     * "参与者是账号、消息是 agent id"这种最难查的组合 —— 它不报错, 只是未读角标再也不对。
     */
    private static String memberIdOf(String companionId, String agentAccountId) {
        return agentAccountId != null && !agentAccountId.isBlank() ? agentAccountId : companionId;
    }

    /**
     * §五十二: 注册会话参与者(群聊数据模型的地基)。参与者用不透明的 memberId, 不依赖任何人格表。
     *
     * <p><b>这里仍然吞异常, 但不再沉默。</b> 参与者注册失败确实不该挡住"建一个会话"这件
     * 主流程(而且 {@link #listForMember} 取并集兜住了它)。但过去它是**一声不吭**地吞的,
     * 于是失败的唯一可见后果是调用方收到一个莫名其妙的
     * {@code UnexpectedRollbackException} —— 因为参与者写入已经把事务标成了 rollback-only,
     * 而真正的原因(这行日志)被丢掉了。
     *
     * <p>二期把参与者表提升为鉴权主路径时, 这个 catch 应当整体去掉。
     */
    private void seedParticipants(Conversation conv, String userId, String companionId, String companionName) {
        try {
            participantService.seed(conv, userId, companionId, companionName);
        } catch (Exception e) {
            log.warn("会话 {} 的参与者注册失败(不影响会话本身): {}", conv.getId(), e.toString(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<Conversation> list(String userId, String companionId) {
        return convRepo.findByUserIdAndCompanionIdOrderByLastMessageAtDesc(userId, companionId);
    }

    /**
     * 「这个会话这个人看得到吗」。
     *
     * <p><b>方法名的语义是二期的, 方法体是今天的</b> —— 今天只可能是会话的属主, 所以查
     * {@code userId}; 二期群聊里能看的人 = 参与者名单里的人, 那时只需把方法体换成查
     * {@code conversation_participants}, **所有调用点都不用动**。
     *
     * <p>刻意没有叫 {@code requireOwned}: 一旦叫了这个名字, 二期就会出现
     * "明明是群聊却要 requireOwned" 这种一眼看上去不合理、于是被绕过去的调用点。
     */
    @Transactional(readOnly = true)
    public Conversation requireVisible(String userId, String conversationId) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("会话不存在"));
        if (!conv.getUserId().equals(userId)) {
            throw BusinessException.badRequest("无权访问该会话");
        }
        return conv;
    }

    /**
     * 「我参与的所有会话」—— 会话列表页。
     *
     * <p>走参与者表而不是 {@code Conversation.userId}（见 {@code
     * ConversationParticipantRepository#findByMemberIdAndLeftAtIsNull} 的理由）。
     *
     * <p><b>这里取的是并集, 不是纯参与者查询。</b> 原因是一段历史: {@code seedParticipants}
     * 至今把一个 try/catch 吞异常的影子路径包在参与者注册外面, 所以库里可能存在
     * "有会话、没有对应 participant 行"的数据。纯走参与者表会让那些会话**凭空消失** ——
     * 用户看着自己的聊天记录不见了, 这是最不能接受的一种 bug。二期把 seed 提升为
     * 鉴权主路径并回填之后, 这个并集可以退化成只查参与者。
     */
    @Transactional(readOnly = true)
    public List<Conversation> listForMember(String userId) {
        java.util.LinkedHashMap<String, Conversation> byId = new java.util.LinkedHashMap<>();
        List<String> memberConvIds = participantRepo.findByMemberIdAndLeftAtIsNull(userId).stream()
                .map(ConversationParticipant::getConversationId).toList();
        if (!memberConvIds.isEmpty()) {
            for (Conversation c : convRepo.findAllById(memberConvIds)) byId.put(c.getId(), c);
        }
        for (Conversation c : convRepo.findByUserIdOrderByLastMessageAtDesc(userId)) {
            byId.putIfAbsent(c.getId(), c);
        }
        List<Conversation> out = new ArrayList<>(byId.values());
        // 最近说过话的排前面; 从没说过话的(lastMessageAt 为空)沉底, 但不丢弃
        out.sort(java.util.Comparator.comparing(Conversation::getLastMessageAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Message> messages(String conversationId) {
        return msgRepo.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /** 最近 N 条消息(升序) */
    @Transactional(readOnly = true)
    public List<Message> recentMessages(String conversationId, int limit) {
        List<Message> desc = msgRepo.findTop200ByConversationIdOrderByCreatedAtDesc(conversationId);
        List<Message> asc = new ArrayList<>(desc);
        java.util.Collections.reverse(asc);
        if (asc.size() > limit) {
            asc = new ArrayList<>(asc.subList(asc.size() - limit, asc.size()));
        }
        return asc;
    }

    /** Message Lifecycle: 更新消息投递状态(DELIVERED/READ/DEFERRED/IGNORED) */
    @Transactional
    public void updateDeliveryStatus(String messageId, String status) {
        msgRepo.findById(messageId).ifPresent(m -> {
            m.setDeliveryStatus(status);
            msgRepo.save(m);
        });
    }

    @Transactional
    public Message addMessage(String conversationId, String senderType, String content,
                              String clientMessageId) {
        return addMessage(conversationId, senderType, null, content, null, null, null,
                false, null, null, null, clientMessageId);
    }

    /**
     * LAP v2 §66: <b>一条带结构化载荷的消息</b> —— 应用卡片与邀请链接走这一条。
     *
     * <p>它比下面的全参重载少九个参数, 因为那九个里它只需要一个 {@code messageKind}:
     * 一条平台通告没有感知结果、不属于任何 Exchange、也没有客户端幂等键。让它去传九个
     * {@code null} 不只是难看 —— 那会让"这条消息到底有没有感知"变成一个要看调用点才知道的
     * 问题, 而 {@code metadata} 是这条消息真正的载荷。
     *
     * <p>{@code metadata} 里的键由调用方定义, chat 只是存储方: 它不认识
     * {@code sessionId} 指的是聊天自己的会话还是应用会话, 也不该去认识 —— 那是
     * {@code ConversationApplicationService} 的事。
     */
    @Transactional
    public Message addMessage(String conversationId, String senderType, String content,
                              String messageKind, Map<String, Object> metadata) {
        Message m = addMessage(conversationId, senderType, null, content, null, null, null, false,
                messageKind, null, null, null);
        m.setMetadata(metadata);
        return msgRepo.save(m);
    }

    /**
     * 不带 {@code senderId} 的重载 —— 给那些**说不出发消息的人具体是哪个 id** 的调用方:
     * 数字人平台的 {@code append}(它只知道"agent 说了一句话", 不知道 agent 的账号 id),
     * 以及 WS 命令面。
     *
     * <p>它们最终都落到下面那个全参重载, 由 {@code deriveSenderId} 按 senderType 推导。
     * 知道 id 的调用方请用全参版本 —— 不要把"我知道"降级成"让它猜"。
     */
    @Transactional
    public Message addMessage(String conversationId, String senderType, String content,
                              String intent, String emotion, String topic,
                              boolean proactive, String messageKind, String sessionId,
                              String exchangeId, String clientMessageId) {
        return addMessage(conversationId, senderType, null, content, intent, emotion, topic,
                proactive, messageKind, sessionId, exchangeId, clientMessageId);
    }

    /**
     * 唯一落库入口。{@code intent/emotion/topic} 是数字人平台的感知结果, chat 只是存储方,
     * 不解释它们的含义。
     *
     * <p>{@code senderId} 见 {@link Message#getSenderId()}。传 null 时会在下面尝试推导 ——
     * 推导得到就填上, 推导不到就留空, 不猜。
     */
    @Transactional
    public Message addMessage(String conversationId, String senderType, String senderId, String content,
                              String intent, String emotion, String topic,
                              boolean proactive, String messageKind, String sessionId,
                              String exchangeId, String clientMessageId) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("会话不存在"));
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setSenderType(senderType);
        m.setSenderId(senderId != null ? senderId : deriveSenderId(senderType, conv));
        m.setContent(content);
        if (intent != null) m.setIntent(intent);
        if (emotion != null) m.setEmotion(emotion);
        if (topic != null) m.setTopic(topic);
        m.setProactive(proactive);
        if (messageKind != null) m.setMessageKind(messageKind);
        if (sessionId != null) m.setSessionId(sessionId);
        if (exchangeId != null) m.setExchangeId(exchangeId);
        if (clientMessageId != null) m.setClientMessageId(clientMessageId);
        m = msgRepo.save(m);
        // §二十~§二十六: 每条消息都归入 Session/Exchange(会话模型由 chat 自己维护)
        try {
            sessionManager.assign(m, conv.getUserId(), conv.getCompanionId(), LocalDateTime.now());
        } catch (Exception ignored) {
            // 会话归集失败不影响消息落库
        }
        conv.setMessageCount(conv.getMessageCount() + 1);
        conv.setLastMessageAt(m.getCreatedAt());
        convRepo.save(conv);
        // 未读只能在这里维护 —— 这是消息成为事实的唯一入口
        try {
            readStateService.bumpOnMessage(conversationId, m.getSenderId());
        } catch (Exception ignored) {
            // 未读计数失败不影响消息落库
        }
        return m;
    }

    // ── 会话列表 ────────────────────────────────────────────────────

    /**
     * 聊天 tab 那一屏的全部数据, **四条查询**取完, 与会话数无关。
     *
     * <p>写成"每个会话把最后一条消息、参与者、读状态各查一遍"是这段代码最自然的写法,
     * 也是它最容易在会话变多之后变慢的原因。这里刻意一次取回再在内存里对 ——
     * 调用方没有机会写成 N+1, 因为它连一个会话一个会话遍历的入口都没有。
     */
    @Transactional(readOnly = true)
    public List<ConversationSummaryView> summariesFor(String userId) {
        List<Conversation> convs = listForMember(userId);
        if (convs.isEmpty()) return List.of();
        List<String> ids = convs.stream().map(Conversation::getId).toList();

        Map<String, ConversationReadState> states = readStateService.statesOf(userId);

        Map<String, Message> lastByConv = new java.util.HashMap<>();
        for (Message m : msgRepo.findLatestPerConversation(ids)) {
            lastByConv.putIfAbsent(m.getConversationId(), m);
        }

        Map<String, String> peerNames = new java.util.HashMap<>();
        for (ConversationParticipant p : participantRepo.findByConversationIdIn(ids)) {
            if (ConversationParticipant.ROLE_AGENT.equals(p.getRole()) && p.getDisplayName() != null) {
                peerNames.putIfAbsent(p.getConversationId(), p.getDisplayName());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        return convs.stream()
                .map(c -> toSummary(c, states.get(c.getId()), lastByConv.get(c.getId()),
                        peerNames.get(c.getId()), now))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationSummaryView summaryOf(String userId, Conversation conv) {
        Map<String, ConversationReadState> states = readStateService.statesOf(userId);
        Map<String, Message> lastByConv = new java.util.HashMap<>();
        for (Message m : msgRepo.findLatestPerConversation(List.of(conv.getId()))) {
            lastByConv.putIfAbsent(m.getConversationId(), m);
        }
        Map<String, String> peerNames = new java.util.HashMap<>();
        for (ConversationParticipant p : participantRepo.findByConversationIdIn(List.of(conv.getId()))) {
            if (ConversationParticipant.ROLE_AGENT.equals(p.getRole()) && p.getDisplayName() != null) {
                peerNames.putIfAbsent(p.getConversationId(), p.getDisplayName());
            }
        }
        return toSummary(conv, states.get(conv.getId()), lastByConv.get(conv.getId()),
                peerNames.get(conv.getId()), LocalDateTime.now());
    }

    private static ConversationSummaryView toSummary(Conversation c, ConversationReadState state,
                                                     Message last, String peerName, LocalDateTime now) {
        return ConversationSummaryView.builder()
                .id(c.getId())
                .peerId(c.getCompanionId())
                // 参与者行里没有名字时退回会话标题 —— 缺一个名字会让整行看起来像坏了
                .peerName(peerName != null ? peerName : c.getTitle())
                .title(c.getTitle())
                .status(c.getStatus())
                .messageCount(c.getMessageCount())
                .lastMessageAt(c.getLastMessageAt())
                .unreadCount(state == null ? 0 : state.getUnreadCount())
                .pinned(ConversationReadStateService.isPinned(state))
                .muted(ConversationReadStateService.isMuted(state, now))
                .lastMessage(last == null ? null : ConversationSummaryView.LastMessage.builder()
                        .id(last.getId())
                        .senderType(last.getSenderType())
                        .senderId(last.getSenderId())
                        .content(last.getContent())
                        .messageKind(last.getMessageKind())
                        .createdAt(last.getCreatedAt())
                        .build())
                .build();
    }

    /**
     * 只推导**结构上无歧义**的那一种: {@code companion} 消息的作者必然是会话的 companion
     * —— 一个会话只有一个 companion 列, 没有第二种可能。
     *
     * <p>{@code user} 刻意**不推导**。一对一里推导成 {@code conv.getUserId()} 是对的,
     * 但群聊里"用户"不再是一个人, 那时任何漏传 {@code senderId} 的调用点都会被静默填成
     * **群主的 id** —— 一条署错名的消息比一条没有署名的消息难查得多。留空至少是诚实的。
     *
     * <h2>为什么优先 {@code agentAccountId} 而不是 {@code companionId}</h2>
     *
     * 因为推导出来的这个值要拿去和参与者的 {@code member_id} 比
     * ({@code ConversationReadStateService.bumpOnMessage} 判"这条消息是不是我自己发的",
     * 靠的就是这个比较)。参与者的 member_id 在有账号时是**聊天账号 id** —— 两个值必须来自
     * 同一个命名空间, 否则比较永远为假, 而症状是"Agent 自己发的消息给它自己涨未读"。
     *
     * <p>回退到 {@code companionId} 的那一支不是兼容代码, 是**必须有**的一支: 一个还没有
     * 聊天账号的 Agent 至今是正常状态, 而建会话那条路在同样的情况下也会退回同一个值
     * (见 {@code memberIdOf})。两处必须同时退到同一个值, 这才能对上。
     *
     * <p>已经是 {@code static} 的老签名 + 一个实例方法: 保留 static 版本给需要它的地方
     * (推导规则与实例状态无关), 但读账号要拿 instance 字段, 所以这里是实例方法。
     */
    private String deriveSenderId(String senderType, Conversation conv) {
        if (!"companion".equals(senderType)) return null;
        String accountId = conv.getAgentAccountId();
        return accountId != null && !accountId.isBlank() ? accountId : conv.getCompanionId();
    }
}
