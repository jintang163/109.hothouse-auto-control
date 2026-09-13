package com.greenhouse.repository;

import com.greenhouse.entity.DeviceRuntimeDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DeviceRuntimeDailyRepository extends JpaRepository<DeviceRuntimeDaily, Long> {
    List<DeviceRuntimeDaily> findByStatDateBetween(LocalDate from, LocalDate to);
    List<DeviceRuntimeDaily> findByDeviceSnAndStatDateBetween(String deviceSn, LocalDate from, LocalDate to);
    void deleteByStatDateGreaterThanEqual(LocalDate from);
}
