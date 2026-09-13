package com.greenhouse.enums;

/** 故障分类（按指令失败原因文本归类，用于运维故障看板） */
public enum FaultCategory {
    /** 电机/执行器过载 */
    OVERLOAD("电机过载"),
    /** 通讯超时无回执 */
    TIMEOUT("通讯超时"),
    /** 其他执行失败 */
    OTHER("执行失败");

    private final String label;

    FaultCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
