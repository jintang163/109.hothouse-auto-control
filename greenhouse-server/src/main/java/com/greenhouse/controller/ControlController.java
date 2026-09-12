package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.service.ControlService;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 手动控制入口（管理端/移动端共用），内含安全互锁校验 */
@RestController
@RequestMapping("/api/control")
public class ControlController {

    private final ControlService controlService;

    public ControlController(ControlService controlService) {
        this.controlService = controlService;
    }

    @Data
    public static class ControlRequest {
        private String deviceSn;
        /** ON / OFF / OPEN / CLOSE / STOP */
        private String action;
        private String params;
        private String operator;
    }

    @PostMapping
    public ApiResponse<String> control(@RequestBody ControlRequest req) {
        if (req.getDeviceSn() == null || req.getAction() == null) {
            return ApiResponse.error("deviceSn 与 action 不能为空");
        }
        String blocked = controlService.manualControl(
                req.getDeviceSn(), req.getAction(), req.getParams(), req.getOperator());
        if (blocked != null) {
            return ApiResponse.error(blocked);
        }
        return ApiResponse.ok("指令已受理，等待设备回执");
    }
}
