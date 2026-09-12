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

    /** 现场网关 SN，数据上报时据此路由到本大棚 */
    @Column(nullable = false, unique = true)
    private String gatewaySn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunMode mode = RunMode.AUTO;

    private LocalDateTime createdAt = LocalDateTime.now();
}
