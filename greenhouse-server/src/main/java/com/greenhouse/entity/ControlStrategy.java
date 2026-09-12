package com.greenhouse.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 作物环控策略（每个大棚一份）。
 * 阈值 + 回差 + 防抖 + 冷却 + 定时计划。
 */
@Data
@Entity
@Table(name = "gh_strategy")
public class ControlStrategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long greenhouseId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 温度上限：超过则启动降温（风机+湿帘） */
    @Column(nullable = false)
    private Double tempHigh = 32.0;

    /** 降温回差恢复点：回落到该值以下停止降温 */
    @Column(nullable = false)
    private Double tempRecover = 28.0;

    /** 温度告警线：超过则产生 CRITICAL 告警 */
    @Column(nullable = false)
    private Double tempCritical = 38.0;

    /** 湿度下限：低于则加湿（开湿帘） */
    @Column(nullable = false)
    private Double humiLow = 50.0;

    /** 湿度恢复点 */
    @Column(nullable = false)
    private Double humiRecover = 65.0;

    /** 光照上限(lux)：超过则展开遮阳网 */
    @Column(nullable = false)
    private Double lightHigh = 70000.0;

    /** 防抖秒数：条件需持续该时长才触发，避免瞬时抖动 */
    @Column(nullable = false)
    private Integer debounceSec = 10;

    /** 同一联动动作的最小间隔（秒），防止频繁启停 */
    @Column(nullable = false)
    private Integer cooldownSec = 60;

    /**
     * 定时计划 JSON，SCHEDULE 模式下生效，如：
     * [{"time":"11:30","deviceType":"SHADE_NET","action":"OPEN"},
     *  {"time":"15:00","deviceType":"SHADE_NET","action":"CLOSE"}]
     */
    @Lob
    private String scheduleJson;
}
