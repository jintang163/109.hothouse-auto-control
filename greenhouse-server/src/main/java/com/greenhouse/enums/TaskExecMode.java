package com.greenhouse.enums;

/** 任务执行方式：一键调用 Netty 联动设备，或人工线下完成后回填反馈 */
public enum TaskExecMode {
    /** 设备联动（走 ControlService 联动链，经 Netty 下发） */
    DEVICE,
    /** 人工执行 */
    MANUAL,
    /** 设备联动失败后转人工兜底 */
    DEVICE_THEN_MANUAL
}
