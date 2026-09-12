package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.InspectionRecord;
import com.greenhouse.repository.InspectionRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 巡检记录（移动端上报，管理端可查） */
@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final InspectionRepository inspectionRepository;

    public InspectionController(InspectionRepository inspectionRepository) {
        this.inspectionRepository = inspectionRepository;
    }

    @GetMapping
    public ApiResponse<List<InspectionRecord>> list(@RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(greenhouseId != null
                ? inspectionRepository.findTop100ByGreenhouseIdOrderByCreatedAtDesc(greenhouseId)
                : inspectionRepository.findTop100ByOrderByCreatedAtDesc());
    }

    @PostMapping
    public ApiResponse<InspectionRecord> create(@RequestBody InspectionRecord record) {
        record.setId(null);
        if (record.getResult() == null || record.getResult().isBlank()) {
            record.setResult("正常");
        }
        return ApiResponse.ok(inspectionRepository.save(record));
    }
}
