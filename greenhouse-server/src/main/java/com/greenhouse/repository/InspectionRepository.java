package com.greenhouse.repository;

import com.greenhouse.entity.InspectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionRepository extends JpaRepository<InspectionRecord, Long> {
    List<InspectionRecord> findTop100ByGreenhouseIdOrderByCreatedAtDesc(Long greenhouseId);
    List<InspectionRecord> findTop100ByOrderByCreatedAtDesc();
}
