package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** LAP v1: 应用开发者。当前由管理员创建; 开发者门户前端不在本阶段范围内。 */
@Entity
@Table(name = "developer")
@Getter
@Setter
public class DeveloperRecord {

    @Id
    @Column(length = 36)
    private String id;

    /** 拥有者: users.id。一个自然人可以有多个开发者身份(公司/个人)。 */
    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
