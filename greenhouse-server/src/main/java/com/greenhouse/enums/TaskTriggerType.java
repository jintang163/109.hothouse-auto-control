package com.greenhouse.enums;

/** 农事任务触发来源 */
public enum TaskTriggerType {
    /** 环境偏差超阈值自动生成 */
    DEVIATION,
    /** 处方周期计划触发 */
    PERIODIC,
    /** 人工创建 */
    MANUAL,
    /** 病虫害识别推送 */
    PEST
}
