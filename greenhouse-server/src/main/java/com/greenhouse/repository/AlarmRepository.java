package com.greenhouse.repository;

import com.greenhouse.entity.Alarm;
import com.greenhouse.enums.AlarmStatus;
import com.greenhouse.enums.AlarmType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {
    List<Alarm> findByStatusOrderByCreatedAtDesc(AlarmStatus status);
    List<Alarm> findTop200ByOrderByCreatedAtDesc();
    List<Alarm> findTop200ByGreenhouseIdOrderByCreatedAtDesc(Long greenhouseId);
    boolean existsByGreenhouseIdAndDeviceSnAndTypeAndStatusAndMessage(
            Long greenhouseId, String deviceSn, AlarmType type, AlarmStatus status, String message);
    long countByStatus(AlarmStatus status);
}
