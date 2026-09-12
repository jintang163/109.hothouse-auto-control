package com.greenhouse.entity;

import com.greenhouse.enums.CommandSource;
import com.greenhouse.enums.CommandStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 控制指令全生命周期记录（下发/回执/重试/失败可追溯） */
@Data
@Entity
@Table(name = "gh_control_command", indexes = {
        @Index(name = "idx_cmd_gh_status", columnList = "greenhouseId,status")
})
public class ControlCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 全局指令 ID，设备回执按此匹配 */
    @Column(nullable = false, unique = true)
    private String commandId;

    @Column(nullable = false)
    private Long greenhouseId;

    @Column(nullable = false)
    private String deviceSn;

    /** OPEN / CLOSE / ON / OFF / STOP */
    @Column(nullable = false)
    private String action;

    private String params;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommandSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommandStatus status = CommandStatus.PENDING;

    @Column(nullable = false)
    private Integer retryCount = 0;

    @Column(nullable = false)
    private Integer maxRetry = 3;

    private String errorMsg;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime sentAt;

    private LocalDateTime ackedAt;
}
