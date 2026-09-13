package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ApplicationVersionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationVersionRepository extends JpaRepository<ApplicationVersionRecord, String> {

    Optional<ApplicationVersionRecord> findByApplicationIdAndVersion(String applicationId, String version);

    List<ApplicationVersionRecord> findByApplicationIdOrderByCreatedAtDesc(String applicationId);
}
