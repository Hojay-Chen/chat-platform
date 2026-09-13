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
}
