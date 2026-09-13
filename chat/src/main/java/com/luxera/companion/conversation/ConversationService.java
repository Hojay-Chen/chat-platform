package com.luxera.companion.conversation;

import com.luxera.companion.common.BusinessException;
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
@Service
public class ConversationService {

    private final ConversationRepository convRepo;
    private final MessageRepository msgRepo;
    private final ConversationParticipantService participantService;
    private final SessionManager sessionManager;

    public ConversationService(ConversationRepository convRepo, MessageRepository msgRepo,
                               ConversationParticipantService participantService,
                               SessionManager sessionManager) {
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.participantService = participantService;
        this.sessionManager = sessionManager;
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
            seedParticipants(existing, userId, companionId, companionName);
            return existing;
        }
        return newConversation(userId, companionId, freshTitle(companionName), companionName);
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
        convRepo.save(conv);
        seedParticipants(conv, userId, companionId, companionName);
        return conv;
    }

    /** §五十二: 注册会话参与者(群聊数据模型的地基)。参与者用不透明的 memberId, 不依赖任何人格表。 */
    private void seedParticipants(Conversation conv, String userId, String companionId, String companionName) {
        try {
            participantService.seed(conv, userId, companionId, companionName);
        } catch (Exception e) {
            // 参与者注册失败不影响会话主流程
        }
    }

    @Transactional(readOnly = true)
    public List<Conversation> list(String userId, String companionId) {
        return convRepo.findByUserIdAndCompanionIdOrderByLastMessageAtDesc(userId, companionId);
    }

    @Transactional(readOnly = true)
    public Conversation requireOwned(String userId, String conversationId) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("会话不存在"));
        if (!conv.getUserId().equals(userId)) {
            throw BusinessException.badRequest("无权访问该会话");
        }
        return conv;
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
        return addMessage(conversationId, senderType, content, null, null, null,
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
        Message m = addMessage(conversationId, senderType, content, null, null, null, false,
                messageKind, null, null, null);
        m.setMetadata(metadata);
        return msgRepo.save(m);
    }

    /**
     * 唯一落库入口。{@code intent/emotion/topic} 是数字人平台的感知结果, chat 只是存储方,
     * 不解释它们的含义。
     */
    @Transactional
    public Message addMessage(String conversationId, String senderType, String content,
                              String intent, String emotion, String topic,
                              boolean proactive, String messageKind, String sessionId,
                              String exchangeId, String clientMessageId) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("会话不存在"));
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setSenderType(senderType);
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
        return m;
    }
}
