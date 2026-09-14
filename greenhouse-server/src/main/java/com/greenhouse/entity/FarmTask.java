package com.greenhouse.entity;

import com.greenhouse.enums.TaskExecMode;
import com.greenhouse.enums.TaskStatus;
import com.greenhouse.enums.TaskTriggerType;
import com.greenhouse.enums.TaskType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 农事任务。由处方环境对比（偏差）/周期计划/人工/病虫害识别生成，
 * 可一键调用 Netty 联动设备，或人工完成并回填用量、耗时，形成闭环。
 */
@Data
@Entity
@Table(name = "gh_farm_task", indexes = {
        @Index(name = "idx_task_gh_status", columnList = "greenhouseId,status"),
        @Index(name = "idx_task_gh_time", columnList = "greenhouseId,generatedAt")
})
public class FarmTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long greenhouseId;

    /** 来源处方（人工/病虫害创建时可空） */
    private Long prescriptionId;

    /** 生成时的处方版本快照，便于追溯 */
    private Integer prescriptionVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskType type;

    @Column(nullable = false)
    private String title;

    /** 执行说明（目标值、操作要点、防治建议等） */
    @Column(length = 1024)
    private String instruction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskExecMode execMode = TaskExecMode.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskTriggerType triggerType = TaskTriggerType.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status = TaskStatus.PENDING;

    /** 指派人/负责人（移动端按人筛选预留） */
    private String assignee;

    /**
     * 设备联动动作计划 JSON：
     * {@code [{"deviceSn":"WC-001","action":"OPEN","params":null}, ...]}
     * 按序经 ControlService 联动链经 Netty 下发（复用互锁/重试/离线补发）。
     */
    @Lob
    private String deviceActionsJson;

    /** 联动链 id（一键执行后回填） */
    private String chainId;

    /** 触发快照 JSON：偏差任务记录当时传感值与越界区间，病虫害任务记录识别结论 */
    @Lob
    private String triggerSnapshot;

    /** 防重复生成的幂等键（如 gh:1:ENV_TEMP:HIGH），开放任务存在则不重复生成 */
    private String dedupeKey;

    /** 关联病虫害识别记录 */
    private Long diagnosisId;

    /** 茬次/批次快照（生成时取自大棚当前茬次），用于关联后期产量、品质分析 */
    private String batchNo;

    // ---- 人工执行反馈（闭环） ----

    /** 实际用量（水肥/药剂等） */
    private Double materialUsed;

    /** 用量单位，如 L / kg / 袋 */
    private String materialUnit;

    /** 实际耗时（分钟） */
    private Integer durationMinutes;

    @Column(length = 1024)
    private String feedback;

    private String operator;

    private LocalDateTime generatedAt = LocalDateTime.now();

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
