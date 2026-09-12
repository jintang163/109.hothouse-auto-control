package com.greenhouse.repository;

import com.greenhouse.entity.Greenhouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GreenhouseRepository extends JpaRepository<Greenhouse, Long> {
    Optional<Greenhouse> findByGatewaySn(String gatewaySn);
}
