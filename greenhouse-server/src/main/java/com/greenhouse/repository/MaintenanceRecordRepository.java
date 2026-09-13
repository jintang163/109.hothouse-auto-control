package com.greenhouse.repository;

import com.greenhouse.entity.MaintenanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {
    Optional<MaintenanceRecord> findTopByDeviceSnOrderByDoneAtDesc(String deviceSn);
    List<MaintenanceRecord> findByGreenhouseIdOrderByDoneAtDesc(Long greenhouseId);
    List<MaintenanceRecord> findByDeviceSnOrderByDoneAtDesc(String deviceSn);
    List<MaintenanceRecord> findTop100ByOrderByDoneAtDesc();
}
