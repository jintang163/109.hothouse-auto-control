package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.enums.RunMode;
import com.greenhouse.service.GreenhouseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/greenhouses")
public class GreenhouseController {

    private final GreenhouseService greenhouseService;

    public GreenhouseController(GreenhouseService greenhouseService) {
        this.greenhouseService = greenhouseService;
    }

    @GetMapping
    public ApiResponse<List<Greenhouse>> list() {
        return ApiResponse.ok(greenhouseService.list());
    }

    /** 监控大屏总览：实时值 + 设备 + 策略 + 未处理告警数 */
    @GetMapping("/{id}/overview")
    public ApiResponse<Map<String, Object>> overview(@PathVariable Long id) {
        return ApiResponse.ok(greenhouseService.overview(id));
    }

    /** 切换运行模式：AUTO / MANUAL / SCHEDULE */
    @PutMapping("/{id}/mode")
    public ApiResponse<Greenhouse> setMode(@PathVariable Long id,
                                           @RequestParam RunMode mode,
                                           @RequestParam(required = false) String operator) {
        return ApiResponse.ok(greenhouseService.setMode(id, mode, operator));
    }
}
