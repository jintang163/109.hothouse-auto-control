package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.FarmTask;
import com.greenhouse.entity.PestDiagnosis;
import com.greenhouse.entity.PestKnowledge;
import com.greenhouse.service.farm.PestService;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 病虫害知识库 + 移动端拍照/特征识别 + 处置任务联动 */
@RestController
@RequestMapping("/api/pest")
public class PestController {

    private final PestService service;

    public PestController(PestService service) {
        this.service = service;
    }

    // ---------------- 知识库 ----------------

    @GetMapping("/knowledge")
    public ApiResponse<List<PestKnowledge>> list(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.listKnowledge(keyword));
    }

    @GetMapping("/knowledge/{id}")
    public ApiResponse<PestKnowledge> get(@PathVariable Long id) {
        return ApiResponse.ok(service.getKnowledge(id));
    }

    @PostMapping("/knowledge")
    public ApiResponse<PestKnowledge> create(@RequestBody PestKnowledge k) {
        return ApiResponse.ok(service.saveKnowledge(k));
    }

    @PutMapping("/knowledge/{id}")
    public ApiResponse<PestKnowledge> update(@PathVariable Long id, @RequestBody PestKnowledge k) {
        return ApiResponse.ok(service.updateKnowledge(id, k));
    }

    @DeleteMapping("/knowledge/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        service.deleteKnowledge(id);
        return ApiResponse.ok("已删除");
    }

    /** 全部鉴别特征关键词（移动端勾选清单） */
    @GetMapping("/features")
    public ApiResponse<List<String>> features() {
        return ApiResponse.ok(service.allFeatures());
    }

    // ---------------- 识别 ----------------

    @PostMapping("/diagnose")
    public ApiResponse<PestDiagnosis> diagnose(@RequestBody DiagnoseRequest req) {
        if (req.getGreenhouseId() == null) {
            return ApiResponse.error("greenhouseId 不能为空");
        }
        return ApiResponse.ok(service.diagnose(req.getGreenhouseId(), req.getOperator(),
                req.getPhotoBase64(), req.getFeatures()));
    }

    @GetMapping("/diagnoses")
    public ApiResponse<List<PestDiagnosis>> diagnoses(@RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(service.listDiagnoses(greenhouseId));
    }

    /** 识别后一键生成植保防治任务 */
    @PostMapping("/diagnoses/{id}/create-task")
    public ApiResponse<FarmTask> createTask(@PathVariable Long id,
                                            @RequestParam(required = false, defaultValue = "现场人员") String operator) {
        return ApiResponse.ok(service.createProtectionTask(id, operator));
    }

    @Data
    public static class DiagnoseRequest {
        private Long greenhouseId;
        private String operator;
        /** 现场照片 base64（data URI，可空，仅留档） */
        private String photoBase64;
        /** 勾选的鉴别特征 */
        private List<String> features;
    }
}
