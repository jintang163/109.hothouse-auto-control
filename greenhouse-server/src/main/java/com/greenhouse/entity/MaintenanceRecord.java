package com.greenhouse.entity;

import com.greenhouse.enums.DeviceType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 保养登记记录（一台设备多条；最近一条的 runHoursAtDone 作为下一轮保养周期的起点） */
@Data
@Entity
@Table(name = "gh_maintenance_record", indexes = {
        @Index(name = "idx_maint_sn", columnList = "deviceSn,doneAt")
})
public class MaintenanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String deviceSn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceType deviceType;

    @Column(nullable = false)
    private Long greenhouseId;

    /** 登记时该设备的累计运行小时 */
    @Column(nullable = false)
    private Double runHoursAtDone;

    @Column(nullable = false)
    private String operator;

    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private LocalDateTime doneAt = LocalDateTime.now();

    private LocalDateTime createdAt = LocalDateTime.now();
}
