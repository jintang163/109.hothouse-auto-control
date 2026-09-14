package com.greenhouse.entity;

import com.greenhouse.enums.RunMode;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 大棚（分区单元，策略按大棚隔离） */
@Data
@Entity
@Table(name = "gh_greenhouse")
public class Greenhouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String location;

    /** 作物类型，如 番茄/黄瓜 */
    private String crop;

    /** 作物品种，如 番茄-佳粉 18 号（农事处方按品种匹配） */
    private String variety;

    /** 当前生育期，如 苗期/开花期/结果期（处方按生育期匹配，可手动推进） */
    private String growthStage;

    /** 当前茬次/批次号，农事任务生成时快照，用于后期产量关联分析 */
    private String currentBatchNo;

    /** 现场网关 SN，数据上报时据此路由到本大棚 */
    @Column(nullable = false, unique = true)
    private String gatewaySn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunMode mode = RunMode.AUTO;

    private LocalDateTime createdAt = LocalDateTime.now();
}
