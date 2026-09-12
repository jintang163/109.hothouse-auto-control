package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.entity.ControlStrategy;
import com.greenhouse.repository.StrategyRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private final StrategyRepository strategyRepository;

    public StrategyController(StrategyRepository strategyRepository) {
        this.strategyRepository = strategyRepository;
    }

    @GetMapping("/{greenhouseId}")
    public ApiResponse<ControlStrategy> get(@PathVariable Long greenhouseId) {
        return ApiResponse.ok(strategyRepository.findByGreenhouseId(greenhouseId).orElse(null));
    }

    /** 保存（不存在则新建） */
    @PutMapping("/{greenhouseId}")
    public ApiResponse<ControlStrategy> save(@PathVariable Long greenhouseId,
                                             @RequestBody ControlStrategy strategy) {
        ControlStrategy existing = strategyRepository.findByGreenhouseId(greenhouseId).orElse(null);
        if (existing != null) {
            strategy.setId(existing.getId());
        } else {
            strategy.setId(null);
        }
        strategy.setGreenhouseId(greenhouseId);
        return ApiResponse.ok(strategyRepository.save(strategy));
    }
}
