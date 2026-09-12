package com.greenhouse.enums;

/** 指令来源 */
public enum CommandSource {
    /** 规则引擎自动联动 */
    AUTO_RULE,
    /** 人工手动 */
    MANUAL,
    /** 定时计划 */
    SCHEDULE,
    /** 离线缓存补发 */
    OFFLINE_RETRY
}
