package com.greenhouse.repository;

import com.greenhouse.entity.ControlCommand;
import com.greenhouse.enums.CommandStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ControlCommandRepository extends JpaRepository<ControlCommand, Long> {
    Optional<ControlCommand> findByCommandId(String commandId);
    List<ControlCommand> findByStatus(CommandStatus status);
    List<ControlCommand> findTop100ByGreenhouseIdOrderByCreatedAtDesc(Long greenhouseId);
    List<ControlCommand> findTop100ByOrderByCreatedAtDesc();
}
