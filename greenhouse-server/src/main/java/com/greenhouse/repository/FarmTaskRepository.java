package com.greenhouse.repository;

import com.greenhouse.entity.FarmTask;
import com.greenhouse.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FarmTaskRepository extends JpaRepository<FarmTask, Long> {

    List<FarmTask> findTop200ByGreenhouseIdOrderByGeneratedAtDesc(Long greenhouseId);

    List<FarmTask> findTop200ByOrderByGeneratedAtDesc();

    List<FarmTask> findByGreenhouseIdAndStatus(Long greenhouseId, TaskStatus status);

    /** 幂等：同一大棚同一幂等键是否存在开放中的任务（PENDING/EXECUTING） */
    Optional<FarmTask> findFirstByDedupeKeyAndStatusIn(String dedupeKey, List<TaskStatus> statuses);

    /** 某幂等键最近生成的任务（周期任务据此推算下次到期时间），任意状态 */
    Optional<FarmTask> findFirstByDedupeKeyOrderByGeneratedAtDesc(String dedupeKey);

    List<FarmTask> findByStatusIn(List<TaskStatus> statuses);

    /** 闭环日志：某大棚某茬次的全部任务（关联产量/品质分析） */
    List<FarmTask> findByGreenhouseIdAndBatchNoOrderByGeneratedAtDesc(Long greenhouseId, String batchNo);
}
