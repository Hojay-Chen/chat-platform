package com.luxera.companion.client;

import com.luxera.companion.contracts.client.NotificationSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §6.6 —— <b>断线重连要补发的那些信号</b>, 按账号分开记着。
 *
 * <h2>为什么这里可以做得到"每账号一个单调递增序号"</h2>
 *
 * <p>因为信号的产生点是**一处**: {@link ClientNotificationService#raise}, 而它由
 * {@code MessageArrivedEvent} 驱动。整个平台只有那一条路会往这里写, 于是序号是天然有序的
 * —— 不需要在库里做序列, 也不需要处理并发的乱序(同一个账号的两条消息即使并发, 序号的大小
 * 关系仍然与它们进入本方法的关系一致)。
 *
 * <p>序号刻意<b>不是</b> UUID: 它要能比较大小({@link #since} 的判据是
 * {@code signalId > lastAckSignalId}), 而 UUID 的大小与时间无关 —— 拿它当游标会漏发。
 *
 * <h2>为什么它在内存里, 而不是一张表</h2>
 *
 * <p>因为信号本身是**短暂的**: 它只是"刚才响了一下"。它承载的真正事实(有一条新消息)在
 * {@code messages} 表里, 而客户端拿到信号之后的下一件事就是去
 * {@code GET /api/client/conversations/{accountId}/messages} 把它读出来。所以这里丢失的
 * 后果是"重连之后少响了一下", 而不是"少了一条消息" —— 后者才是不可接受的。
 *
 * <p>换来的是两条实在的好处: 一是这张表永远不会成为热路径上的一个写入点(每一条消息都要写
 * 一行, 而它只是为了让一个可能根本不在线的客户端响一下); 二是**它天然会忘**。一个一直在
 * 手机里放着、几周不开的客户端, 重连时不该被补发几万条"那时有人说过话" —— 那正是真实聊天
 * 软件里不需要补收的噪声。进程重启让这个遗忘发生得更早一些, 但方向是同一条。
 *
 * <p>容量上限({@value #DEFAULT_CAPACITY} 条/账号)是同一个理由的延伸: 一个一直不上线的账号
 * 不该把内存吃光。超出之后丢最老的 —— 丢"最久以前的那几声铃"正是我们想要的取舍。
 */
@Slf4j
@Component
public class NotificationSignalLog {

    public static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Map<String, Deque<NotificationSignal>> byAccount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> nextIdByAccount = new ConcurrentHashMap<>();

    public NotificationSignalLog(@Value("${app.client.signal-log-capacity:256}") int capacity) {
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
    }

    /**
     * 记一条信号, 并把它的序号定下来。
     *
     * <p>序号从 1 开始(不是 0): 0 在协议里表示"我还没有确认过任何一条"
     * ({@code ClientStreamFrame.lastAckSignalId}), 两者共用一个数值空间就必须分得开。
     */
    public NotificationSignal append(String accountId, String conversationId, String fromAccountId) {
        long id = nextIdByAccount
                .computeIfAbsent(accountId, k -> new AtomicLong(0))
                .incrementAndGet();
        NotificationSignal signal = new NotificationSignal(
                id, conversationId, fromAccountId, LocalDateTime.now());

        Deque<NotificationSignal> log = byAccount.computeIfAbsent(accountId, k -> new ArrayDeque<>());
        synchronized (log) {
            log.addLast(signal);
            while (log.size() > capacity) {
                log.pollFirst();
            }
        }
        return signal;
    }

    /**
     * 「{@code lastAckSignalId} 之后的那几条」—— 断线重连时补发的就是这一份。
     *
     * <p>返回的每一帧都只带 {@link NotificationSignal}, 于是**补发的只能是信号, 不可能是
     * 正文**: 这个方法没有第二个返回类型可选。§6.6 那句"补发的是通知信号, 不是正文"因此
     * 不是一条实现纪律, 而是这个签名的形状。
     *
     * @param lastAckSignalId 客户端说自己收到了哪一条为止; 0 表示一条都没有
     */
    public List<NotificationSignal> since(String accountId, long lastAckSignalId) {
        Deque<NotificationSignal> log = byAccount.get(accountId);
        if (log == null) return List.of();
        List<NotificationSignal> out = new ArrayList<>();
        synchronized (log) {
            for (NotificationSignal s : log) {
                if (s.signalId() > lastAckSignalId) out.add(s);
            }
        }
        return out;
    }

    /** 这个账号当前发到第几条 —— 只给日志与测试用。 */
    public long latestId(String accountId) {
        AtomicLong next = nextIdByAccount.get(accountId);
        return next == null ? 0 : next.get();
    }
}
