package com.luxera.companion.client;

import com.luxera.companion.contracts.client.NotificationSignal;
import com.luxera.companion.conversation.ConversationReadStateService;
import com.luxera.companion.conversation.MessageArrivedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * V2.2 §6.1 / §6.2 —— <b>「要不要响这一下铃」这个决定, 在聊天平台这一侧做出</b>。
 *
 * <h2>为什么判定必须在这里发生, 而不是交给 Agent</h2>
 *
 * <p>因为免打扰是<em>聊天软件的用户设置</em>。把判定推给下游, 会得到一个无法解释的世界:
 * 信号一旦发出去, 对面就知道"有人说话了" —— 而"消息免打扰"的语义是<b>连铃都不响</b>,
 * 不是"响了但我不看"。所以这里只有一条规则:
 *
 * <pre>
 *   免打扰生效中  →  一条信号都不产生（未读数照旧 +1, 她打开聊天软件能看到红点）
 *   免打扰未开启  →  每一条消息各产生一条信号（**绝不聚合**）
 * </pre>
 *
 * <p>§6.2 那张两层表里的**下一层**(手机收到信号之后怎么响)不在本仓 —— 那在 Agent 平台的
 * {@code Device.NotificationPolicy} 里。两者都不成立时她才真的"没听见", 而这个状态因为分层
 * 而是可解释的。
 *
 * <h2>为什么是 {@code @TransactionalEventListener(AFTER_COMMIT)}</h2>
 *
 * <p>两个理由, 第二个是硬的:
 *
 * <ul>
 *   <li>通知的消费者(WS、SSE)会立刻去读库看看"到底发生了什么"。在事务提交前发出去, 它们
 *       可能读到一个还没有这条消息的世界 —— 表现为"响了, 但点开是空的"。</li>
 *   <li><b>回滚过的消息不该响。</b>消息落库与未读 +1 在同一个事务里, 而那个事务可能因为
 *       任何一个原因回滚。在一个已经不存在的事实上响铃, 是这一条链路上最难查的一类 bug:
 *       用户说他听见了, 而库里什么都没有。</li>
 * </ul>
 *
 * <p>{@code fallbackExecution = true} 是为了那些**没有事务**的调用点(直接调
 * {@code bumpOnMessage} 的测试、以及将来可能出现的"消息已经在别处提交了"的路径)。少了它,
 * 那些路径上的通知会静默地一条都不发 —— 而"静默地什么都没发生"是这个类最不该有的失败模式。
 */
@Slf4j
@Service
public class ClientNotificationService {

    private final ConversationReadStateService readStates;
    private final NotificationSignalLog signalLog;
    private final ClientStreamRegistry streams;

    public ClientNotificationService(ConversationReadStateService readStates,
                                     NotificationSignalLog signalLog,
                                     ClientStreamRegistry streams) {
        this.readStates = readStates;
        this.signalLog = signalLog;
        this.streams = streams;
    }

    /**
     * 消息成为了事实 —— 对<b>每一个</b>收件人各判一次、各发一条。
     *
     * <p>事件是逐收件人发出来的(见 {@code MessageArrivedEvent} 的说明), 所以这个循环里
     * 只有一个人。免打扰是逐人的: 同一个群里 A 免打扰而 B 没有, 这一次消息对 A 不发信号、
     * 对 B 发。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageArrived(MessageArrivedEvent event) {
        try {
            raise(event.conversationId(), event.memberId(), event.senderAccountId());
        } catch (Exception e) {
            // 通知失败绝不能往上冒: 它的调用点在一次消息落库之后, 而消息已经是事实了 ——
            // 一个"铃声发不出去"不该把一次成功的发送变成一次 500(与
            // ConversationReadStateService.bumpOnMessage 吞异常同一条理由)。
            log.warn("[客户端面] 通知信号产生失败(会话 {} 收件人 {}): {}",
                    event.conversationId(), event.memberId(), e.toString(), e);
        }
    }

    /**
     * 给一个人发一条通知信号 —— 除非他此刻正在免打扰。
     *
     * <p>免打扰是**现查**的(`isMutedNow` 读的就是
     * {@code conversation_read_state.muted_until} 那一行), 不是把某个值传下来: 判定的时刻
     * 必须是"消息到达的时刻", 用户在消息到达前 1 毫秒打开了免打扰, 那一次就该不响。
     *
     * @param recipientAccountId 收件人的聊天账号 id —— 与会话参与者、读状态、WS 连接
     *                           三处用的是同一个命名空间里的同一个值, 于是"判定用的是谁的
     *                           免打扰"与"信号发给谁"不可能不一致
     * @return 产生的信号; 免打扰生效时为 {@link Optional#empty()}
     */
    public Optional<NotificationSignal> raise(String conversationId, String recipientAccountId,
                                              String fromAccountId) {
        if (readStates.isMutedNow(conversationId, recipientAccountId)) {
            // 免打扰: **一条信号都不产生**。未读数已经在 bumpOnMessage 里 +1 过了 ——
            // 那一步刻意不看免打扰, 因为"她打开聊天软件时能看到红点"是 §6.1 明确要求的。
            return Optional.empty();
        }
        // 顺序是"先记后发", 它换来的是**不丢**:
        //
        // 反过来(先广播、后记日志)的话, 一个刚好在这一刻重连上的客户端会走到补发那条路上 ——
        // 而那时日志里还没有这一条, 于是它**一条都收不到**。少响一下是查不出来的, 因为它与
        // "那一刻确实没人说话"长得一模一样。
        //
        // 先记后发最多带来**重复**: 重连补发与广播各给了一次。而重复是可去重的 ——
        // signalId 在同一个账号内单调递增, 客户端只要丢掉 {@code <= lastAckSignalId} 的那些。
        // 用一次可去重的重复, 换掉一次不可察觉的丢失。
        NotificationSignal signal =
                signalLog.append(recipientAccountId, conversationId, fromAccountId);
        streams.notify(recipientAccountId, signal);
        return Optional.of(signal);
    }
}
