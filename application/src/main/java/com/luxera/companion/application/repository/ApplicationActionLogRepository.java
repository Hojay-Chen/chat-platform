package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ApplicationActionLogRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationActionLogRepository extends JpaRepository<ApplicationActionLogRecord, String> {

    List<ApplicationActionLogRecord> findByCompanionIdOrderByCreatedAtDesc(String companionId);

    List<ApplicationActionLogRecord> findByActionIdOrderByCreatedAtDesc(String actionId);

    List<ApplicationActionLogRecord> findByInvocationId(String invocationId);
}
