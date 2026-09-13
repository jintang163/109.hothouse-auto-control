package com.greenhouse.repository;

import com.greenhouse.entity.Alarm;
import com.greenhouse.enums.AlarmStatus;
import com.greenhouse.enums.AlarmType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {
    List<Alarm> findByStatusOrderByCreatedAtDesc(AlarmStatus status);
    List<Alarm> findTop200ByOrderByCreatedAtDesc();
    List<Alarm> findTop200ByGreenhouseIdOrderByCreatedAtDesc(Long greenhouseId);
    List<Alarm> findByTypeAndCreatedAtBetween(AlarmType type, LocalDateTime from, LocalDateTime to);
    long countByTypeAndCreatedAtBetween(AlarmType type, LocalDateTime from, LocalDateTime to);
    boolean existsByGreenhouseIdAndDeviceSnAndTypeAndStatusAndMessage(
            Long greenhouseId, String deviceSn, AlarmType type, AlarmStatus status, String message);
    long countByStatus(AlarmStatus status);
    long countByGreenhouseIdAndStatus(Long greenhouseId, AlarmStatus status);
}
