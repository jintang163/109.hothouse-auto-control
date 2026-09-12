package com.greenhouse.entity;

import com.greenhouse.enums.CommandSource;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 操作日志（手动/自动/定时动作留痕） */
@Data
@Entity
@Table(name = "gh_operation_log", indexes = {
        @Index(name = "idx_log_gh_time", columnList = "greenhouseId,createdAt")
})
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long greenhouseId;

    private String deviceSn;

    /** 动作描述，如 开风机 / 关湿帘 / 切换自动模式 */
    @Column(nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommandSource source;

    /** 操作人；自动/定时为 system */
    @Column(nullable = false)
    private String operatorName = "system";

    @Column(length = 512)
    private String detail;

    private LocalDateTime createdAt = LocalDateTime.now();
}
