package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.Prescription;
import com.greenhouse.service.farm.PrescriptionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 农事处方库：按品种+生育期配置，复制 / 版本发布 / 归档 */
@RestController
@RequestMapping("/api/prescriptions")
public class PrescriptionController {

    private final PrescriptionService service;

    public PrescriptionController(PrescriptionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Prescription>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Prescription> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    /** 某品种+生育期的版本历史 */
    @GetMapping("/versions")
    public ApiResponse<List<Prescription>> versions(@RequestParam String variety,
                                                     @RequestParam String growthStage) {
        return ApiResponse.ok(service.versions(variety, growthStage));
    }

    @PostMapping
    public ApiResponse<Prescription> create(@RequestBody Prescription p,
                                            @RequestParam(required = false, defaultValue = "admin") String operator) {
        p.setCreatedBy(operator);
        return ApiResponse.ok(service.create(p));
    }

    @PutMapping("/{id}")
    public ApiResponse<Prescription> update(@PathVariable Long id, @RequestBody Prescription p) {
        return ApiResponse.ok(service.update(id, p));
    }

    /** 复制为新版本（草稿） */
    @PostMapping("/{id}/copy")
    public ApiResponse<Prescription> copy(@PathVariable Long id,
                                          @RequestParam(required = false, defaultValue = "admin") String operator) {
        return ApiResponse.ok(service.copy(id, operator));
    }

    /** 发布（旧发布版自动归档） */
    @PostMapping("/{id}/publish")
    public ApiResponse<Prescription> publish(@PathVariable Long id) {
        return ApiResponse.ok(service.publish(id));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<Prescription> archive(@PathVariable Long id) {
        return ApiResponse.ok(service.archive(id));
    }

    /** 品种/生育期目录（前端下拉） */
    @GetMapping("/catalog")
    public ApiResponse<Map<String, java.util.List<String>>> catalog() {
        Map<String, java.util.List<String>> map = new java.util.TreeMap<>();
        service.list().forEach(p -> map.computeIfAbsent(p.getVariety(), k -> new java.util.ArrayList<>()));
        service.list().forEach(p -> {
            java.util.List<String> stages = map.get(p.getVariety());
            if (!stages.contains(p.getGrowthStage())) {
                stages.add(p.getGrowthStage());
            }
        });
        return ApiResponse.ok(map);
    }
}
