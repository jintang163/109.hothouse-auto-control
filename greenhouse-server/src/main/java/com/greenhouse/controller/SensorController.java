package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.SensorRecord;
import com.greenhouse.service.SensorDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sensor")
public class SensorController {

    private final SensorDataService sensorDataService;

    public SensorController(SensorDataService sensorDataService) {
        this.sensorDataService = sensorDataService;
    }

    /** 某大棚各指标最新值 */
    @GetMapping("/latest")
    public ApiResponse<Map<String, SensorRecord>> latest(@RequestParam Long greenhouseId) {
        return ApiResponse.ok(sensorDataService.latest(greenhouseId));
    }

    /** 历史曲线数据，from/to 为 epoch 毫秒，默认最近 6 小时 */
    @GetMapping("/history")
    public ApiResponse<List<SensorRecord>> history(@RequestParam Long greenhouseId,
                                                   @RequestParam String metric,
                                                   @RequestParam(required = false) Long from,
                                                   @RequestParam(required = false) Long to) {
        long toMs = to != null ? to : System.currentTimeMillis();
        long fromMs = from != null ? from : toMs - 6 * 3600_000L;
        LocalDateTime fromTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMs), ZoneId.systemDefault());
        LocalDateTime toTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(toMs), ZoneId.systemDefault());
        return ApiResponse.ok(sensorDataService.history(greenhouseId, metric, fromTime, toTime));
    }
}
