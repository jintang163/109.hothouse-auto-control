package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.YieldRecord;
import com.greenhouse.service.farm.YieldService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 产量/品质录入与按茬次农事闭环分析 */
@RestController
@RequestMapping("/api/yields")
public class YieldController {

    private final YieldService service;

    public YieldController(YieldService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<YieldRecord>> list(@RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(service.list(greenhouseId));
    }

    @PostMapping
    public ApiResponse<YieldRecord> add(@RequestBody YieldRecord record) {
        return ApiResponse.ok(service.add(record));
    }

    /** 批次分析：产量品质 + 农事任务执行情况 + 处方优化线索 */
    @GetMapping("/analysis")
    public ApiResponse<List<Map<String, Object>>> analysis(@RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(service.batchAnalysis(greenhouseId));
    }
}
