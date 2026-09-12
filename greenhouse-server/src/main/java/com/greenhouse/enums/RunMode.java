package com.greenhouse.enums;

/** 大棚运行模式 */
public enum RunMode {
    /** 自动：规则引擎依据阈值与作物策略联动 */
    AUTO,
    /** 手动：仅响应人工指令 */
    MANUAL,
    /** 定时：按策略中的定时计划执行 */
    SCHEDULE
}
