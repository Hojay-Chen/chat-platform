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
}
