package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.CapabilityRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapabilityRepository extends JpaRepository<CapabilityRecord, String> {
}
