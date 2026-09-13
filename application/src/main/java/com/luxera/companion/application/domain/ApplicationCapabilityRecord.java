package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.util.UUID;

/**
 * LAP v1: 某个<em>版本</em>提供了哪些能力。挂在版本上而不是应用上 ——
 * 1.0.1 新加的能力不该出现在 1.0.0 的发现结果里。
 */
@Entity
@Table(name = "application_capability",
        uniqueConstraints = @UniqueConstraint(name = "uq_application_capability",
                columnNames = {"application_version_id", "capability_id"}))
@Getter
@Setter
public class ApplicationCapabilityRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "application_version_id", nullable = false, length = 36)
    private String applicationVersionId;

    @Column(name = "capability_id", nullable = false, length = 64)
    private String capabilityId;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
    }
}
