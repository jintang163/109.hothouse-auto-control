package com.greenhouse.entity;

import com.greenhouse.enums.DeviceType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * 设备运行日结（重算产物，滚动 30 天窗口，可幂等重建）。
 */
@Data
@Entity
@Table(name = "gh_device_runtime_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_daily_sn_date",
                columnNames = {"deviceSn", "statDate"}),
        indexes = @Index(name = "idx_daily_date", columnList = "statDate"))
public class DeviceRuntimeDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String deviceSn;

    @Column(nullable = false)
    private Long greenhouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceType deviceType;

    @Column(nullable = false)
    private LocalDate statDate;

    @Column(nullable = false)
    private Long runSeconds = 0L;

    @Column(nullable = false)
    private Integer startCount = 0;

    /** 当日 FAILED 指令条数 */
    @Column(nullable = false)
    private Integer faultCount = 0;
}
