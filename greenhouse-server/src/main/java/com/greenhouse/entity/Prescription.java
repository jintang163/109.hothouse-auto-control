package com.greenhouse.entity;

import com.greenhouse.enums.PrescriptionStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 农事处方（按作物品种 + 生育期配置）。
 *
 * <p>处方主体是两份 JSON 配置：
 * <ul>
 *   <li>{@link #envTargetsJson} 环境目标区间，任务生成器定时与传感器对比，越界生成环境调控任务：
 *       {@code [{"metric":"temperature","low":22,"high":28,"unit":"℃"}, ...]}</li>
 *   <li>{@link #operationsJson} 水肥与农事操作（支持周期触发 / 设备联动）：
 *       {@code [{"type":"IRRIGATION","name":"滴灌","execMode":"DEVICE",
 *       "intervalDays":2,"deviceType":"WET_CURTAIN","action":"OPEN",
 *       "dose":200,"doseUnit":"L","estimatedMinutes":15,"instruction":"..."}]}</li>
 * </ul>
 *
 * <p>版本控制：同一品种+生育期下 version 递增；发布后只读，修改需「复制为新版本」。
 */
@Data
@Entity
@Table(name = "gh_prescription", uniqueConstraints = {
        @UniqueConstraint(name = "uk_rx_variety_stage_ver",
                columnNames = {"variety", "growthStage", "version"})
})
public class Prescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 作物品种，如 番茄-佳粉18号 */
    @Column(nullable = false)
    private String variety;

    /** 生育期，如 苗期/开花期/结果期 */
    @Column(nullable = false)
    private String growthStage;

    @Column(nullable = false)
    private String name;

    /** 版本号，同一品种+生育期内递增，从 1 开始 */
    @Column(nullable = false)
    private Integer version = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PrescriptionStatus status = PrescriptionStatus.DRAFT;

    /** 是否参与自动任务生成（仅 PUBLISHED 且 enabled=true 生效） */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 环境目标区间 JSON，见类注释 */
    @Lob
    private String envTargetsJson;

    /** 水肥 / 农事操作 JSON，见类注释 */
    @Lob
    private String operationsJson;

    private String remark;

    /** 复制来源处方 id（版本溯源） */
    private Long copiedFromId;

    private String createdBy = "admin";

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime publishedAt;
}
