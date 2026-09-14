package com.greenhouse.enums;

/**
 * 农事任务类型。
 * ENV_* 由处方环境目标对比传感器自动生成；其余由处方农事操作周期生成或人工/病虫害创建。
 */
public enum TaskType {
    /** 环境偏差：温度越限 */
    ENV_TEMP("环境调控-温度"),
    /** 环境偏差：湿度越限 */
    ENV_HUMIDITY("环境调控-湿度"),
    /** 环境偏差：光照越限 */
    ENV_LIGHT("环境调控-光照"),
    /** 环境偏差：CO₂ 越限 */
    ENV_CO2("环境调控-CO₂"),
    /** 灌溉 */
    IRRIGATION("灌溉"),
    /** 施肥 */
    FERTIGATION("施肥"),
    /** 植保（打药/防治，常由病虫害识别生成） */
    PLANT_PROTECTION("植保"),
    /** 整枝打杈等农事操作 */
    PRUNING("整枝农事"),
    /** 采收 */
    HARVEST("采收"),
    /** 巡检/观察 */
    INSPECTION_TASK("观察巡检"),
    /** 其他 */
    OTHER("其他");

    private final String label;

    TaskType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
