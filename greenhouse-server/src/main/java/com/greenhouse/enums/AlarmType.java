package com.greenhouse.enums;

/** 告警类型 */
public enum AlarmType {
    /** 传感器数值越限 */
    THRESHOLD,
    /** 设备离线 */
    DEVICE_OFFLINE,
    /** 控制指令失败 */
    COMMAND_FAILED,
    /** 互锁阻止 */
    INTERLOCK_BLOCKED
}
