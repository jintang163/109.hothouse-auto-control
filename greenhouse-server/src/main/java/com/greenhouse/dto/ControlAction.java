package com.greenhouse.dto;

/** 一条待执行的控制动作（联动链中的一环） */
public record ControlAction(String deviceSn, String action, String params) {

    public static ControlAction of(String deviceSn, String action) {
        return new ControlAction(deviceSn, action, null);
    }
}
