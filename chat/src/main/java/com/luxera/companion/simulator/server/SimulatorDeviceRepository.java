package com.luxera.companion.simulator.server;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SimulatorDeviceRepository extends JpaRepository<SimulatorDevice, String> {

    Optional<SimulatorDevice> findByPairingCodeAndStatus(String pairingCode, String status);

    Optional<SimulatorDevice> findByDeviceIdAndStatus(String deviceId, String status);

    Optional<SimulatorDevice> findByAccountIdAndStatus(String accountId, String status);

    List<SimulatorDevice> findByStatus(String status);

    /**
     * 一键创建的幂等查询 —— 「这个 requestId 是不是已经铸过账号了」。
     *
     * <p>故意<b>不带 status 条件</b>: 三个状态要给的答复不一样(PAIRING 未过期→原样返回同码;
     * 已过期→换新码; ACTIVE→已配对; REVOKED→复活同一账号), 而那只看得见一种状态的方法
     * 会把另外三种表达成"查无此 requestId" —— 于是重试真的铸出第二个账号。
     */
    Optional<SimulatorDevice> findByRequestId(String requestId);

    /**
     * 「这个 agent 在我们这边是不是已经有一个聊天账号了」—— 补铸 runner 的第一道查询。
     *
     * <p>补铸的输入来自仓 2 的"哪些 companion 的 {@code chat_account_id} 还是空的",
     * 而那个判据在**仓 2 那边**。两边可能不同步: 上一次补铸在本仓铸好了账号、绑好了设备,
     * 推回仓 2 的那一步失败了 —— 于是这次它又在"缺账号"的名单里。此时的正确答案是
     * <b>把已有的那个账号推回去</b>, 而不是再铸一个: 再铸一个会得到第二个
     * {@code users} 行 + 第二台设备, 而两者都绑在同一个 agent 上, 那个 agent 从此有两个
     * 聊天身份(它发出去的消息归哪个账号名下, 取决于哪台设备先连上)。
     *
     * <p>{@code findFirst...OrderByCreatedAtAsc} 而不是 {@code findByCompanionId}:
     * {@code companion_id} 上**没有**唯一约束(它是在有数据的表上加的列, 见
     * {@link SimulatorDevice}), 而 Spring Data 在遇到多行时返回 {@code Optional} 的写法会
     * 抛 {@code IncorrectResultSizeDataAccessException} —— 那会让一次本该"取最早那个"的
     * 查询变成一次崩溃。取最早那个也正好是对的: 它是当初真正推给仓 2 的那个账号。
     */
    Optional<SimulatorDevice> findFirstByCompanionIdOrderByCreatedAtAsc(String companionId);
}
