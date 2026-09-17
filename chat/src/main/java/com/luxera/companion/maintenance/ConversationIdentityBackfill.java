package com.luxera.companion.maintenance;

import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationParticipant;
import com.luxera.companion.conversation.ConversationParticipantRepository;
import com.luxera.companion.conversation.ConversationRepository;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.simulator.server.SimulatorDevice;
import com.luxera.companion.simulator.server.SimulatorDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 一次性回填: 把"这段会话里的 Agent 是哪个聊天账号"这件事, 从**只有 companionId** 的状态
 * 改成**两个 id 都在**的状态。
 *
 * <h2>它改的三处, 以及为什么必须一起改</h2>
 *
 * <ol>
 *   <li>{@code conversations.agent_account_id} —— 新列, 由本 runner 从
 *       {@code simulator_devices} 的绑定关系推出来。</li>
 *   <li>{@code conversation_participants.member_id} —— 把 agent 那一行的
 *       {@code companionId} 换成聊天账号 id。</li>
 *   <li>{@code messages.sender_id} —— 把历史消息的 {@code sender_id} 补上, 并且把**已经写上
 *       的**那些 companion 消息从 {@code companionId} 换成聊天账号 id。</li>
 * </ol>
 *
 * <p>三者必须同时正确, 因为 {@code ConversationReadStateService.bumpOnMessage} 判"这条消息
 * 是不是我自己发的"用的是 {@code participant.memberId.equals(message.senderId)} 逐个比。
 * 只要参与者的 member_id 与消息的 sender_id 停在**两个不同的命名空间**里, 那个比较就永远
 * 为假 —— 于是 Agent 会因为它自己发的每一条消息收到未读角标, 而人类的未读数是正常的。
 * 这个缺陷不报错、不抛异常, 只表现为"那个小红点消不掉"。
 *
 * <p>第 3 条里"换掉已经写上的那些"这一半容易被漏掉: 平台上一部分消息的 {@code sender_id}
 * 是**新代码**写进去的({@code deriveSenderId} 返回的是 {@code companionId}), 它们非空、
 * 看起来"已经补好了", 但值的命名空间是旧的。只补 null 的写法会把它们原样留下 —— 于是
 * 补完之后那 82 条旧消息仍然会让 Agent 看到未读。
 *
 * <h2>顺序: 必须在 {@code AgentChatAccountBackfill} 之后</h2>
 *
 * {@code @Order(20)} 对着那边的 {@code @Order(10)}。本 runner 只认**已经存在**的账号:
 * 一个 Agent 还没铸号时, 它的会话会被原样跳过(计入"仍未绑定"), 而不是给一个瞎猜的值。
 * 跳过是安全的 —— 读的一侧对空值有回退(退回 {@code companionId}), 所以半完成状态下的站点
 * 仍然是可用的, 只是那些会话还没切过去。
 *
 * <p>两个开关是分开的: 只开这一个 = 已经把号铸好的那些会话先切过去; 只开后一个 =
 * 只铸号, 会话不动。两个都开(推荐)时 {@code @Order} 保证这一轮就全部切完。
 *
 * <h2>幂等</h2>
 *
 * 三项判据都是"目标值是否已经是它": 列非空就不再查设备表, participant 已经是账号 id 就跳过,
 * 消息 sender_id 已经是账号 id 就跳过。第二次跑只读不写。
 *
 * <h2>为什么不用一条 SQL 批量 UPDATE</h2>
 *
 * 因为这里有三类"看着该改、其实不该动"的行, 而它们在 SQL 里都表现为"条件成立":
 * <ul>
 *   <li>会话的 {@code companion_id} 指向一个**已删除**的 Agent —— 它没有账号, 该跳过。</li>
 *   <li>participant 行已经有目标 member_id(同一个会话里 agent 与账号两行并存)——
 *       改过去会撞 {@code uk_conv_participant} 唯一约束, 而"先删一行再插一行"在迁移里是
 *       不可接受的动作。</li>
 *   <li>消息的 {@code sender_id} 是一个既不是 companionId 也不是 null 的值 —— 那是别人写的,
 *       不该被这次迁移覆盖。</li>
 * </ul>
 * 这三种都要**单独报出来**, 因为每一种都意味着两边的记录出了本 runner 解决不了的问题。
 * 一条 UPDATE 会把它们静默地一起改掉或一起放过。
 */
@Slf4j
@Component
@Order(20)
@ConditionalOnProperty(name = "app.maintenance.backfill-conversation-identities", havingValue = "true")
public class ConversationIdentityBackfill implements ApplicationRunner {

    private static final String ROLE_AGENT = "agent";
    private static final String SENDER_COMPANION = "companion";
    private static final String SENDER_USER = "user";

    private final ConversationRepository conversations;
    private final ConversationParticipantRepository participants;
    private final MessageRepository messages;
    private final SimulatorDeviceRepository devices;

    public ConversationIdentityBackfill(ConversationRepository conversations,
                                        ConversationParticipantRepository participants,
                                        MessageRepository messages,
                                        SimulatorDeviceRepository devices) {
        this.conversations = conversations;
        this.participants = participants;
        this.messages = messages;
        this.devices = devices;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Conversation> all = conversations.findAll();
        log.warn("[会话身份回填] 开始: {} 段会话 —— 会改写 participants.member_id 与 "
                + "messages.sender_id 的命名空间", all.size());

        int linked = 0, unbound = 0;
        Tally tally = new Tally();

        for (Conversation conv : all) {
            try {
                String accountId = conv.getAgentAccountId();
                if (accountId == null || accountId.isBlank()) {
                    SimulatorDevice device = devices
                            .findFirstByCompanionIdOrderByCreatedAtAsc(conv.getCompanionId())
                            .orElse(null);
                    if (device == null) {
                        // 这个 Agent 还没有聊天账号(补铸没开到它 / 它是已删除的)。
                        // 原样留着 —— 读的一侧会回退到 companionId。
                        unbound++;
                        continue;
                    }
                    accountId = device.getAccountId();
                    conv.setAgentAccountId(accountId);
                    conversations.save(conv);
                    linked++;
                }

                relinkParticipants(conv, accountId, tally);
                relinkMessages(conv, accountId, tally);
            } catch (Exception e) {
                // 逐段继续: 一段会话改不动不该让后面几十段都不做。已提交的保持已提交,
                // 重跑时会跳过它们(幂等), 所以"修好再跑一遍"总是安全的。
                log.warn("[会话身份回填] 会话 {} 处理失败(继续下一段): {}", conv.getId(), e.toString());
            }
        }

        log.warn("[会话身份回填] 完成: 新回填会话 {} 段(跳过 {} 段仍无账号的), 参与者行 {} 条, "
                        + "companion 消息 {} 条, user 消息 {} 条。"
                        + "**跑完请把 app.maintenance.backfill-conversation-identities 关掉。**",
                linked, unbound, tally.participantRows, tally.companionMsgs, tally.userMsgs);
        if (tally.skipped > 0) {
            log.error("[会话身份回填] 有 {} 条记录指向了本 runner 认不出的 id(含撞唯一约束的), "
                    + "它们**没有**被改动 —— 需要人看一眼(见上面的逐条日志)。", tally.skipped);
        }
    }

    /**
     * 累计计数 —— 五六个 int 在方法之间传来传去, 不如一个明确命名的容器。
     *
     * <p>刻意不做成字段: 那会让这个 runner 变成有状态的, 而它被重跑时状态必须清零。
     */
    private static final class Tally {
        int participantRows;
        int companionMsgs;
        int userMsgs;
        /** 看见了但**刻意没改**的行数 —— 每一条都单独打过日志。 */
        int skipped;
    }

    /**
     * agent 那一行的 member_id: companionId → 聊天账号 id。
     *
     * <p>只碰 {@code role='agent'} 且 {@code member_id} 正好等于本会话的 {@code companion_id}
     * 的行 —— 一个字都不能放宽: 放宽到"改了也没坏处"的话, 同一个会话里那个
     * {@code role='user'} 的行会被改成 agent 的账号, 于是真人从自己的会话里消失。
     */
    private void relinkParticipants(Conversation conv, String accountId, Tally tally) {
        for (ConversationParticipant p : participants.findByConversationId(conv.getId())) {
            if (!ROLE_AGENT.equals(p.getRole())) continue;
            if (accountId.equals(p.getMemberId())) continue;          // 已经切过了
            if (!conv.getCompanionId().equals(p.getMemberId())) {
                tally.skipped++;
                log.warn("[会话身份回填] 会话 {} 的 agent 参与者 {} 指向 {} —— 既不是账号 {} 也不是 "
                                + "companion {}, 不动它", conv.getId(), p.getId(), p.getMemberId(),
                        accountId, conv.getCompanionId());
                continue;
            }
            // 唯一约束 uk_conv_participant (conversation_id, member_id): 同一个会话里已经有
            // 一行是这个账号时不能再插一行进去。这种并存本身说明数据被改过至少一次, 报出来。
            if (participants.existsByConversationIdAndMemberId(conv.getId(), accountId)) {
                tally.skipped++;
                log.error("[会话身份回填] 会话 {} 里已经有一行 member_id={}, 无法把 agent 参与者 {} "
                        + "切过去 —— 该会话的未读判定仍会出错", conv.getId(), accountId, p.getId());
                continue;
            }
            p.setMemberId(accountId);
            participants.save(p);
            tally.participantRows++;
        }
    }

    /**
     * 消息的 sender_id —— 两种 sender_type 各有各的目标值, 判据完全不同。
     *
     * <p>companion 那一路要**同时**处理 null 与非 null 两种: 非 null 的那些是
     * {@code deriveSenderId} 写进去的 {@code companionId}, 它们看起来"已经有值了",
     * 但值的命名空间是旧的。只补 null 的写法会留下它们, 于是补完之后那些历史消息
     * 仍然会让 Agent 看到未读。
     */
    private void relinkMessages(Conversation conv, String accountId, Tally tally) {
        for (Message m : messages.findByConversationIdOrderByCreatedAtAsc(conv.getId())) {
            if (SENDER_COMPANION.equals(m.getSenderType())) {
                if (accountId.equals(m.getSenderId())) continue;
                if (m.getSenderId() != null && !conv.getCompanionId().equals(m.getSenderId())) {
                    // 不是这个 agent 的 id, 也不是 null —— 别的东西写过这一列, 不动它
                    tally.skipped++;
                    log.warn("[会话身份回填] 消息 {} 的 sender_id={} 既不是账号 {} 也不是 companion {}",
                            m.getId(), m.getSenderId(), accountId, conv.getCompanionId());
                    continue;
                }
                m.setSenderId(accountId);
                messages.save(m);
                tally.companionMsgs++;
            } else if (SENDER_USER.equals(m.getSenderType())) {
                // 真人那一侧的目标值就是会话的 user_id —— 它本来就是 users.id 命名空间,
                // 所以这里只补 null, 从不改写非空值(那可能是"某个参与者"而不是创建者,
                // 而将来有群聊时这两者会不一样)。
                if (m.getSenderId() != null) continue;
                m.setSenderId(conv.getUserId());
                messages.save(m);
                tally.userMsgs++;
            }
            // 其他 sender_type(系统消息之类)一概不碰
        }
    }
}
