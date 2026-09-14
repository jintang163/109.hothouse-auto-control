package com.greenhouse.enums;

/** 农事任务状态机：PENDING → EXECUTING(设备联动中) → DONE / FAILED，可人工取消 */
public enum TaskStatus {
    /** 待执行 */
    PENDING,
    /** 设备联动进行中（指令已下发，等待回执链完成） */
    EXECUTING,
    /** 已完成（设备联动成功或人工回填反馈） */
    DONE,
    /** 设备联动失败（指令重试耗尽/互锁中止） */
    FAILED,
    /** 已取消 */
    CANCELLED
}
