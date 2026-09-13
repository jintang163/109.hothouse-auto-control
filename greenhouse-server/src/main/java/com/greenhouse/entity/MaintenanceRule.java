package com.greenhouse.entity;

import com.greenhouse.enums.DeviceType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 保养周期规则（按设备类型配置，如风机每运行 500 小时润滑一次） */
@Data
@Entity
@Table(name = "gh_maintenance_rule")
public class MaintenanceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private DeviceType deviceType;

    /** 保养间隔（运行小时） */
    @Column(nullable = false)
    private Integer runIntervalHours;

    /** 提前提醒窗口（运行小时），须小于 runIntervalHours */
    @Column(nullable = false)
    private Integer warnAheadHours;

    @Column(nullable = false)
    private Boolean enabled = true;

    private LocalDateTime updatedAt = LocalDateTime.now();
}
