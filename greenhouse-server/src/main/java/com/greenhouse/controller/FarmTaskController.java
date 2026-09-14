package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.FarmTask;
import com.greenhouse.service.farm.FarmTaskService;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 农事任务：查询 / 人工创建 / 一键设备联动 / 人工反馈闭环 / 取消 */
@RestController
@RequestMapping("/api/farm-tasks")
public class FarmTaskController {

    private final FarmTaskService service;

    public FarmTaskController(FarmTaskService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<FarmTask>> list(@RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(service.list(greenhouseId));
    }

    @GetMapping("/{id}")
    public ApiResponse<FarmTask> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<FarmTask> create(@RequestBody FarmTask task,
                                        @RequestParam(required = false, defaultValue = "admin") String operator) {
        return ApiResponse.ok(service.createManual(task, operator));
    }

    /** 一键调用 Netty 联动设备（复用互锁/重试/离线补发） */
    @PostMapping("/{id}/run-devices")
    public ApiResponse<FarmTask> runDevices(@PathVariable Long id,
                                            @RequestParam(required = false, defaultValue = "system") String operator) {
        return ApiResponse.ok(service.runDevices(id, operator));
    }

    /** 人工完成并回填用量、耗时、反馈 */
    @PostMapping("/{id}/complete-manual")
    public ApiResponse<FarmTask> completeManual(@PathVariable Long id, @RequestBody CompleteRequest req) {
        return ApiResponse.ok(service.completeManually(id,
                req.getOperator(), req.getMaterialUsed(), req.getMaterialUnit(),
                req.getDurationMinutes(), req.getFeedback()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<FarmTask> cancel(@PathVariable Long id,
                                        @RequestParam(required = false, defaultValue = "admin") String operator) {
        return ApiResponse.ok(service.cancel(id, operator));
    }

    @Data
    public static class CompleteRequest {
        private String operator;
        private Double materialUsed;
        private String materialUnit;
        private Integer durationMinutes;
        private String feedback;
    }
}
