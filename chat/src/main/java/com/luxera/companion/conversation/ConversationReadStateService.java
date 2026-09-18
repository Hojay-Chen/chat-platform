package com.luxera.companion.conversation;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 读状态: 未读数、置顶、免打扰 —— 全部按 {@code (会话, 人)} 维度。
 *
 * <p>维护未读计数的地方只有一处: 消息落库时给**除发送者外**的每个参与者 +1。
 * 这个规则在一对一里是"给对方 +1", 在群聊里是"给其余所有人 +1" —— 同一条规则,
 * 不需要为群聊换算法。清零点也只有一处: 有人读到某条消息。
 */
@Service
public class ConversationReadStateService {

    /**
     * 「永久免打扰」在库里的表示 —— 一个远期时刻, 不是一个额外的布尔列。
     *
     * <p>V2.2 §6.2 的 {@code ConversationNotificationSetting.muted} 就是它: 这个哨兵值存在
     * ⇔ 免打扰开着。两个类型读的是同一个字段, 因此不存在"设置页显示免打扰、铃声照响"这种
     * 两个开关各说各话的状态。
     */
    public static final LocalDateTime MUTED_FOREVER = LocalDateTime.of(9999, 12, 31, 23, 59);

    private final ConversationReadStateRepository repo;
    private final ConversationParticipantRepository participantRepo;
    private final ApplicationEventPublisher events;

    public ConversationReadStateService(ConversationReadStateRepository repo,
                                        ConversationParticipantRepository participantRepo,
                                        ApplicationEventPublisher events) {
        this.repo = repo;
        this.participantRepo = participantRepo;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public int unreadOf(String conversationId, String memberId) {
        return find(conversationId, memberId).map(ConversationReadState::getUnreadCount).orElse(0);
    }

    /** 这一行读状态本身 —— 会话列表页要拿它渲染"未读几条、置顶了没有、免打扰没有"。 */
    @Transactional(readOnly = true)
    public Optional<ConversationReadState> stateOf(String conversationId, String memberId) {
        return find(conversationId, memberId);
    }

    /**
     * 这个人此刻是不是处于免打扰。<b>判定与 {@code mutedUntil} 那一行是同一份数据</b> ——
     * 不存在"另一个地方也记了一份免打扰"的可能(见 {@code ConversationReadState} 的说明)。
     *
     * <p>调用它的是通知信号的产生方(V2.2 §6.2): 免打扰为真 → 一条信号都不发。这里刻意
     * 现查而不是把 {@code mutedUntil} 传下去, 因为判定的时刻必须是"消息到达的时刻" ——
     * 用户在消息到达前 1 毫秒打开了免打扰, 那次就该不响。
     */
    @Transactional(readOnly = true)
    public boolean isMutedNow(String conversationId, String memberId) {
        return isMuted(find(conversationId, memberId).orElse(null), LocalDateTime.now());
    }

    /**
     * 一次把"免打扰 / 置顶"两件事写掉 —— V2.2 §6.3 第 8 项的 {@code PUT .../notification}。
     *
     * <p>为什么是这个粒度而不是让调用方自己调两次({@code setMuted} + {@code setPinned}):
     * 那是两次独立的事务, 中间那一刻的状态是"改了一半"的。对一个 {@code PUT}(整体替换)
     * 来说, "改了一半"不该是一个能被别的事务观察到的状态。
     */
    @Transactional
    public ConversationReadState setNotification(String conversationId, String memberId,
                                                boolean muted, boolean pinned) {
        ConversationReadState s = getOrCreate(conversationId, memberId);
        s.setMutedUntil(muted ? MUTED_FOREVER : null);
        s.setPinnedAt(pinned ? LocalDateTime.now() : null);
        // saveAndFlush 而不是 save: 调用方(客户端面的 PUT .../notification)要把 updatedAt
        // **回给调用方**, 而那一列是 @UpdateTimestamp —— Hibernate 在 flush 时才给它赋值。
        // 用 save 的话, 事务还没提交就被读走的 updatedAt 是null, 表现是响应里少了这个字段
        // (而不是一个错), 只有对着库看才发现对不上。
        return repo.saveAndFlush(s);
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
            // V2.2 §6.1: 每一条消息、每一个收件人各发一条通知信号(绝不聚合)。
            //
            // 发布点选在这里, 而不是在 ConversationService.addMessage 里, 有两个理由:
            //  1. 收件人名单**本来就**是这一个循环算出来的("除发送者外的每个参与者")。
            //     在别处再算一次, 两处迟早会在群聊的某个边界上给出不同的答案。
            //  2. 免打扰就写在刚刚 save 的这一行上(mutedUntil)。判定与自增读的是同一行,
            //     不存在"用另一份状态判免打扰"这种可能。
            //
            // 事件本身不含正文(见 MessageArrivedEvent): 通知里不会有内容这件事, 从这里
            // 往下就是一条不可破坏的性质, 而不是下游每个消费者各自要记得的纪律。
            events.publishEvent(new MessageArrivedEvent(conversationId, p.getMemberId(), senderId));
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
        s.setMutedUntil(muted ? MUTED_FOREVER : null);
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
