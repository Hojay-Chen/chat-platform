package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ApplicationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationRepository extends JpaRepository<ApplicationRecord, String> {

    List<ApplicationRecord> findByStatus(String status);
}
