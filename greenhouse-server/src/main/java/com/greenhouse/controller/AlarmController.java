package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.Alarm;
import com.greenhouse.enums.AlarmStatus;
import com.greenhouse.service.AlarmService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    @GetMapping
    public ApiResponse<List<Alarm>> list(@RequestParam(required = false) AlarmStatus status,
                                         @RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(alarmService.list(status, greenhouseId));
    }

    /** 处理告警（移动端/管理端） */
    @PostMapping("/{id}/handle")
    public ApiResponse<Alarm> handle(@PathVariable Long id,
                                     @RequestParam(required = false) String operator) {
        return ApiResponse.ok(alarmService.handle(id, operator));
    }
}
