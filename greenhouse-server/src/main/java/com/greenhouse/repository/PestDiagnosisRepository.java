package com.greenhouse.repository;

import com.greenhouse.entity.PestDiagnosis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PestDiagnosisRepository extends JpaRepository<PestDiagnosis, Long> {

    List<PestDiagnosis> findTop100ByGreenhouseIdOrderByCreatedAtDesc(Long greenhouseId);

    List<PestDiagnosis> findTop100ByOrderByCreatedAtDesc();
}
