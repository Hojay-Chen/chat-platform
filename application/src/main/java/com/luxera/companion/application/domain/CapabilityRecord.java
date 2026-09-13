package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * LAP v1: 能力目录({@code game.play} / {@code reminder.manage})。
 *
 * <p>它是发现链的第一级, 也是"绝不要把 50000 个 action 全塞给 LLM"的落地方式:
 * 先按意图选能力(个位数), 再选应用, 最后才拿到那一个应用的动作。表里只有个位数行。
 */
@Entity
@Table(name = "capability")
@Getter
@Setter
public class CapabilityRecord {

    /** 如 {@code game.play} —— 人可读、可写进 manifest、可直接给 LLM。 */
    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 512)
    private String description;

    @Column(length = 64)
    private String category;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}
