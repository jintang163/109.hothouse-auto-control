package com.greenhouse.repository;

import com.greenhouse.entity.YieldRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface YieldRecordRepository extends JpaRepository<YieldRecord, Long> {

    List<YieldRecord> findByGreenhouseIdOrderByHarvestDateDesc(Long greenhouseId);

    List<YieldRecord> findByGreenhouseIdAndBatchNoOrderByHarvestDateAsc(Long greenhouseId, String batchNo);

    List<YieldRecord> findAllByOrderByHarvestDateDesc();
}
