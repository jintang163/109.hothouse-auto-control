package com.greenhouse.service.farm;

/**
 * 农事任务设备联动动作（deviceActionsJson 的一项）。
 *
 * @param deviceSn    目标设备 SN
 * @param action      ON/OFF/OPEN/CLOSE
 * @param params      下发参数（可空）
 * @param holdMinutes 动作保持时长：非空时，联动链成功后延迟该分钟数自动下发 thenAction
 *                    （如滴灌 OPEN 保持 10 分钟后自动 CLOSE）
 * @param thenAction  保持结束后的反向动作（可空）
 */
public record TaskDeviceAction(String deviceSn, String action, String params,
                               Integer holdMinutes, String thenAction) {
}
