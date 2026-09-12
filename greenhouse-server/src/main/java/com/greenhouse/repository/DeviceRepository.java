package com.greenhouse.repository;

import com.greenhouse.entity.Device;
import com.greenhouse.enums.DeviceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findBySn(String sn);
    List<Device> findByGreenhouseId(Long greenhouseId);
    List<Device> findByGreenhouseIdAndType(Long greenhouseId, DeviceType type);
}
