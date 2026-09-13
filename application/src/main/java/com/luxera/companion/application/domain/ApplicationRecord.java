package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * LAP v1: 一个应用(稳定身份)。<b>id 就是 manifest 的 {@code identity.id}</b>
 * (如 {@code com.luxera.tictactoe}), 不是 UUID —— 它要出现在 URI、日志、LLM 上下文里,
 * 得是个人能读、也能写进 manifest 的东西。
 *
 * <p>manifest 不在这张表上, 在 {@link ApplicationVersionRecord} 上: 同一个应用的两个版本
 * 各有各的 manifest, 于是"1.0.0 的动作集合"与"1.0.1 的动作集合"都解释得清。
 */
@Entity
@Table(name = "application")
@Getter
@Setter
public class ApplicationRecord {

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "developer_id", length = 36)
    private String developerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 64)
    private String category;

    @Column(nullable = false, length = 32)
    private String status = ApplicationStatus.DRAFT.name();

    /** 发现链看到的版本。发布新版本时更新, 指向 application_version.version。 */
    @Column(name = "latest_version", length = 32)
    private String latestVersion;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ApplicationStatus statusEnum() {
        return ApplicationStatus.valueOf(status);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
