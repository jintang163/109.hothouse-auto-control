package com.greenhouse.repository;

import com.greenhouse.entity.DeviceRuntime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRuntimeRepository extends JpaRepository<DeviceRuntime, Long> {
    Optional<DeviceRuntime> findByDeviceSn(String deviceSn);
    List<DeviceRuntime> findByGreenhouseIdOrderByDeviceSn(Long greenhouseId);
    List<DeviceRuntime> findAllByOrderByDeviceSn();
}
