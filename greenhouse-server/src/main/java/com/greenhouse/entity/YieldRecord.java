package com.greenhouse.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 产量与品质记录（按茬次/批次）。
 * 农事日志可关联批次，分析处方/农事操作对产量、品质的影响。
 */
@Data
@Entity
@Table(name = "gh_yield_record", indexes = {
        @Index(name = "idx_yield_gh_batch", columnList = "greenhouseId,batchNo")
})
public class YieldRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long greenhouseId;

    /** 茬次/批次号，如 2026秋茬-1号棚 */
    @Column(nullable = false)
    private String batchNo;

    /** 采收日期 */
    @Column(nullable = false)
    private LocalDate harvestDate;

    /** 本次采收产量（kg） */
    @Column(nullable = false)
    private Double weightKg;

    /** 优质果率（%），品质数据 */
    private Double premiumRate;

    /** 可溶性固形物（糖度 °Brix），品质数据 */
    private Double brix;

    /** 备注（天气、异常等） */
    @Column(length = 512)
    private String remark;

    private String recordedBy = "admin";

    private LocalDateTime createdAt = LocalDateTime.now();
}
