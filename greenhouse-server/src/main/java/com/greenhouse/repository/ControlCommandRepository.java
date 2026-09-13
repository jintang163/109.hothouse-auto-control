package com.greenhouse.repository;

import com.greenhouse.entity.ControlCommand;
import com.greenhouse.enums.CommandStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ControlCommandRepository extends JpaRepository<ControlCommand, Long> {
    Optional<ControlCommand> findByCommandId(String commandId);
    List<ControlCommand> findByStatus(CommandStatus status);
    List<ControlCommand> findTop100ByGreenhouseIdOrderByCreatedAtDesc(Long greenhouseId);
    List<ControlCommand> findTop100ByOrderByCreatedAtDesc();

    /**
     * 按统一事件时间口径（回执优先：COALESCE(ackedAt, sentAt, createdAt)）查询状态区间内的指令。
     * 故障/运行统计专用，与 {@code CommandEventTimes.of} 的回退链保持一致。
     */
    @Query("select c from ControlCommand c where c.status = :status "
            + "and coalesce(c.ackedAt, c.sentAt, c.createdAt) between :from and :to")
    List<ControlCommand> findByStatusAndEventTimeBetween(@Param("status") CommandStatus status,
                                                         @Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to);
}
