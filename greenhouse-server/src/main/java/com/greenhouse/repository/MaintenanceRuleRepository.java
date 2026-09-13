package com.greenhouse.repository;

import com.greenhouse.entity.MaintenanceRule;
import com.greenhouse.enums.DeviceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaintenanceRuleRepository extends JpaRepository<MaintenanceRule, Long> {
    Optional<MaintenanceRule> findByDeviceType(DeviceType deviceType);
    List<MaintenanceRule> findAllByOrderByDeviceType();
}
