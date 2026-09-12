package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.ControlCommand;
import com.greenhouse.entity.OperationLog;
import com.greenhouse.repository.OperationLogRepository;
import com.greenhouse.service.ControlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 追溯：操作日志 + 控制指令记录 */
@RestController
@RequestMapping("/api")
public class LogController {

    private final OperationLogRepository logRepository;
    private final ControlService controlService;

    public LogController(OperationLogRepository logRepository, ControlService controlService) {
        this.logRepository = logRepository;
        this.controlService = controlService;
    }

    @GetMapping("/logs")
    public ApiResponse<List<OperationLog>> logs(@RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(greenhouseId != null
                ? logRepository.findTop200ByGreenhouseIdOrderByCreatedAtDesc(greenhouseId)
                : logRepository.findTop200ByOrderByCreatedAtDesc());
    }

    @GetMapping("/commands")
    public ApiResponse<List<ControlCommand>> commands(@RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(controlService.recentCommands(greenhouseId));
    }
}
