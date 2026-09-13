package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.SubscriptionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionRepository extends JpaRepository<SubscriptionRecord, String> {

    List<SubscriptionRecord> findBySessionId(String sessionId);

    List<SubscriptionRecord> findByPrincipalTypeAndPrincipalIdAndStatus(
            String principalType, String principalId, String status);

    List<SubscriptionRecord> findByStatus(String status);
}
