package com.greenhouse.repository;

import com.greenhouse.entity.Prescription;
import com.greenhouse.enums.PrescriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    /** 某品种+生育期的全部版本，按版本号倒序 */
    List<Prescription> findByVarietyAndGrowthStageOrderByVersionDesc(String variety, String growthStage);

    /** 任务生成用：某品种+生育期已发布且启用的处方（理论上唯一） */
    Optional<Prescription> findFirstByVarietyAndGrowthStageAndStatusAndEnabledTrueOrderByVersionDesc(
            String variety, String growthStage, PrescriptionStatus status);

    /** 某品种+生育期最大版本号（无记录时返回 null） */
    Optional<Prescription> findFirstByVarietyAndGrowthStageOrderByVersionDesc(String variety, String growthStage);

    List<Prescription> findByStatusAndEnabledTrue(PrescriptionStatus status);

    List<Prescription> findAllByOrderByCreatedAtDesc();
}
