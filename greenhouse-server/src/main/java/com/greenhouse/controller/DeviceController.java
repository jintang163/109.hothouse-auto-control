package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.Device;
import com.greenhouse.repository.DeviceRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRepository deviceRepository;

    public DeviceController(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @GetMapping
    public ApiResponse<List<Device>> list(@RequestParam(required = false) Long greenhouseId) {
        if (greenhouseId != null) {
            return ApiResponse.ok(deviceRepository.findByGreenhouseId(greenhouseId));
        }
        return ApiResponse.ok(deviceRepository.findAll());
    }

    @PostMapping
    public ApiResponse<Device> create(@RequestBody Device device) {
        device.setId(null);
        return ApiResponse.ok(deviceRepository.save(device));
    }

    @PutMapping("/{id}")
    public ApiResponse<Device> update(@PathVariable Long id, @RequestBody Device device) {
        Device existing = deviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + id));
        existing.setName(device.getName());
        existing.setType(device.getType());
        existing.setGreenhouseId(device.getGreenhouseId());
        return ApiResponse.ok(deviceRepository.save(existing));
    }
}
