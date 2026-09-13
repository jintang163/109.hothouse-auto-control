package com.greenhouse.entity;

import com.greenhouse.enums.DeviceType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备运行台账（每台执行器一行，由指令记录全量重算生成）。
 * totalRunSeconds 只含已闭合的运行段；设备仍在运行的尾段在读取时按 lastStartedAt 实时叠加。
 */
@Data
@Entity
@Table(name = "gh_device_runtime", indexes = {
        @Index(name = "idx_runtime_gh", columnList = "greenhouseId")
})
public class DeviceRuntime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String deviceSn;

    @Column(nullable = false)
    private Long greenhouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceType deviceType;

    /** 累计运行秒数（截至 settledAt，仅已闭合运行段） */
    @Column(nullable = false)
    private Long totalRunSeconds = 0L;

    /** 累计启动次数（ACKED 的 ON/OPEN 指令条数） */
    @Column(nullable = false)
    private Long startCount = 0L;

    private LocalDateTime lastStartedAt;

    private LocalDateTime lastStoppedAt;

    /** 最近一次全量重算时间 */
    private LocalDateTime settledAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();
}
