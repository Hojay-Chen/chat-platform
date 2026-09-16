package com.luxera.companion.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读状态: 未读数、置顶、免打扰 —— 全部按 {@code (会话, 人)} 维度。
 *
 * <p>维护未读计数的地方只有一处: 消息落库时给**除发送者外**的每个参与者 +1。
 * 这个规则在一对一里是"给对方 +1", 在群聊里是"给其余所有人 +1" —— 同一条规则,
 * 不需要为群聊换算法。清零点也只有一处: 有人读到某条消息。
 */
@Service
public class ConversationReadStateService {

    private final ConversationReadStateRepository repo;
    private final ConversationParticipantRepository participantRepo;

    public ConversationReadStateService(ConversationReadStateRepository repo,
                                        ConversationParticipantRepository participantRepo) {
        this.repo = repo;
        this.participantRepo = participantRepo;
    }

    @Transactional(readOnly = true)
    public int unreadOf(String conversationId, String memberId) {
        return find(conversationId, memberId).map(ConversationReadState::getUnreadCount).orElse(0);
    }

    /**
     * 一次取回我在这些会话里的全部读状态。
     *
     * <p>列表页对每个会话各查一次是最容易写出来的版本, 也是最容易在会话变多之后变慢的版本。
     * 这里刻意做成"一次查完再在内存里对", 调用方没有机会写成 N+1。
     */
    @Transactional(readOnly = true)
    public Map<String, ConversationReadState> statesOf(String memberId) {
        Map<String, ConversationReadState> out = new HashMap<>();
        for (ConversationReadState s : repo.findByMemberId(memberId)) {
            out.put(s.getConversationId(), s);
        }
        return out;
    }

    /**
     * 消息落库后给除发送者外的每个参与者 +1。
     *
     * <p>发送者自己不 +1: 一个人不该因为自己发的消息而看到未读角标。
     * 参与者名单里没有的行也会建出来 —— 那正是"这个会话我还没读过"的初始状态。
     */
    @Transactional
    public void bumpOnMessage(String conversationId, String senderId) {
        for (ConversationParticipant p : participantRepo.findByConversationId(conversationId)) {
            if (p.getMemberId().equals(senderId)) continue;
            ConversationReadState s = getOrCreate(conversationId, p.getMemberId());
            s.setUnreadCount(s.getUnreadCount() + 1);
            repo.save(s);
        }
    }

    /** 读到某条消息为止。传 null 表示"全读了" —— 调用方不必先查出最后一条的 id。 */
    @Transactional
    public void markRead(String conversationId, String memberId, String lastMessageId) {
        ConversationReadState s = getOrCreate(conversationId, memberId);
        s.setUnreadCount(0);
        if (lastMessageId != null) s.setLastReadMessageId(lastMessageId);
        repo.save(s);
    }

    @Transactional
    public void setPinned(String conversationId, String memberId, boolean pinned) {
        ConversationReadState s = getOrCreate(conversationId, memberId);
        s.setPinnedAt(pinned ? LocalDateTime.now() : null);
        repo.save(s);
    }

    @Transactional
    public void setMuted(String conversationId, String memberId, boolean muted) {
        ConversationReadState s = getOrCreate(conversationId, memberId);
        // 免打扰用一个远期时间点表示"一直免打扰", 而不是另开一个布尔 —— 一个字段
        // 同时表达"免打扰到什么时候"和"免打扰开没开", 少一个会互相矛盾的组合。
        s.setMutedUntil(muted ? LocalDateTime.of(9999, 12, 31, 23, 59) : null);
        repo.save(s);
    }

    /**
     * 「这个人能不能动这个会话」**不在这里判** —— 那由 {@code ConversationService.requireVisible}
     * 判, 而且判过之后才调到这儿。
     *
     * <p>这里曾经写成"参与者名单里没有就拒绝"。那是错的: 参与者注册至今包在一个吞异常的
     * 影子路径里({@code ConversationService#seedParticipants}), 于是库里存在"用户看得到、
     * 但参与者表里没有他"的历史会话 —— 对那种会话, 置顶和免打扰会莫名 400, 而用户
     * 明明在聊天列表里看着它。**同一件事判两遍, 两遍的口径还不一样**, 就是这样出问题的。
     */
    private ConversationReadState getOrCreate(String conversationId, String memberId) {
        return find(conversationId, memberId).orElseGet(() -> {
            ConversationReadState fresh = new ConversationReadState();
            fresh.setConversationId(conversationId);
            fresh.setMemberId(memberId);
            return fresh;
        });
    }

    private java.util.Optional<ConversationReadState> find(String conversationId, String memberId) {
        return repo.findByConversationIdAndMemberId(conversationId, memberId);
    }

    /** 会话列表页要判"免打扰生效中" */
    public static boolean isMuted(ConversationReadState s, LocalDateTime now) {
        return s != null && s.getMutedUntil() != null && s.getMutedUntil().isAfter(now);
    }

    public static boolean isPinned(ConversationReadState s) {
        return s != null && s.getPinnedAt() != null;
    }

    /** 一次给一批会话补读状态行 —— 用于历史数据(那些还没有读状态行的会话) */
    @Transactional
    public void ensureRows(String memberId, List<String> conversationIds) {
        for (String id : conversationIds) {
            if (find(id, memberId).isPresent()) continue;
            ConversationReadState fresh = new ConversationReadState();
            fresh.setConversationId(id);
            fresh.setMemberId(memberId);
            repo.save(fresh);
        }
    }
}
