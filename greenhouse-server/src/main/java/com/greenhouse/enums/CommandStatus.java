package com.greenhouse.enums;

/** 控制指令状态机 */
public enum CommandStatus {
    /** 待下发 */
    PENDING,
    /** 已下发，等待回执 */
    SENT,
    /** 设备已回执确认 */
    ACKED,
    /** 设备离线，已缓存待补发 */
    QUEUED_OFFLINE,
    /** 重试耗尽，失败 */
    FAILED
}
