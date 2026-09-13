package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.DeveloperRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeveloperRepository extends JpaRepository<DeveloperRecord, String> {

    List<DeveloperRecord> findByOwnerUserId(String ownerUserId);
}
