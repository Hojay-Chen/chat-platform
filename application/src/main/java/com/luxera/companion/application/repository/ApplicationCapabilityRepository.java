package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ApplicationCapabilityRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationCapabilityRepository extends JpaRepository<ApplicationCapabilityRecord, String> {

    List<ApplicationCapabilityRecord> findByApplicationVersionId(String applicationVersionId);

    List<ApplicationCapabilityRecord> findByCapabilityId(String capabilityId);
}
