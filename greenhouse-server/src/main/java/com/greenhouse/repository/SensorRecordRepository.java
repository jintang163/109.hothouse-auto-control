package com.greenhouse.repository;

import com.greenhouse.entity.SensorRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SensorRecordRepository extends JpaRepository<SensorRecord, Long> {
    List<SensorRecord> findByGreenhouseIdAndMetricAndRecordedAtBetweenOrderByRecordedAtAsc(
            Long greenhouseId, String metric, LocalDateTime from, LocalDateTime to);
}
