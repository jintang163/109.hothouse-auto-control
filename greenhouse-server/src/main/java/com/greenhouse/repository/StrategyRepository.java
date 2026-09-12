package com.greenhouse.repository;

import com.greenhouse.entity.ControlStrategy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StrategyRepository extends JpaRepository<ControlStrategy, Long> {
    Optional<ControlStrategy> findByGreenhouseId(Long greenhouseId);
}
