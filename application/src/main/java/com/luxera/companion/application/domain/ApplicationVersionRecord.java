package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LAP v1: <b>manifest 属于版本, 不属于应用。</b> 这张表是那句话的物理形态。
 *
 * <p>发布后不可变: {@code manifest_json} / {@code manifest_hash} / {@code runtime_type}
 * 一旦进入 PUBLISHED 就冻结 —— 任何写入口先查状态, 非 DRAFT/DEVELOPING 一律
 * {@code VERSION_IMMUTABLE}。要改就发 1.0.1。
 */
@Entity
@Table(name = "application_version",
        uniqueConstraints = @UniqueConstraint(name = "uq_application_version",
                columnNames = {"application_id", "version"}))
@Getter
@Setter
public class ApplicationVersionRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "application_id", nullable = false, length = 128)
    private String applicationId;

    @Column(nullable = false, length = 32)
    private String version;

    /** 原始 manifest JSON —— 逐字节保留, 校验与 hash 都以它为准。 */
    @Column(name = "manifest_json", columnDefinition = "text")
    private String manifestJson;

    /** manifest_json 的 sha256, 用于"发布后有没有被动过"的判定与审计。 */
    @Column(name = "manifest_hash", length = 64)
    private String manifestHash;

    @Column(name = "runtime_type", nullable = false, length = 16)
    private String runtimeType = "NATIVE";

    @Column(nullable = false, length = 32)
    private String status = ApplicationStatus.DRAFT.name();

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public ApplicationStatus statusEnum() {
        return ApplicationStatus.valueOf(status);
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
