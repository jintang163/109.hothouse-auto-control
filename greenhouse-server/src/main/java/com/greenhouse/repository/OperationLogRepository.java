package com.greenhouse.repository;

import com.greenhouse.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    List<OperationLog> findTop200ByOrderByCreatedAtDesc();
    List<OperationLog> findTop200ByGreenhouseIdOrderByCreatedAtDesc(Long greenhouseId);
}
