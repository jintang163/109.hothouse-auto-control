package com.greenhouse.entity;

import com.greenhouse.enums.AlarmLevel;
import com.greenhouse.enums.AlarmStatus;
import com.greenhouse.enums.AlarmType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 告警记录 */
@Data
@Entity
@Table(name = "gh_alarm", indexes = {
        @Index(name = "idx_alarm_status", columnList = "status,createdAt")
})
public class Alarm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long greenhouseId;

    private String deviceSn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlarmType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlarmLevel level;

    @Column(nullable = false, length = 512)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlarmStatus status = AlarmStatus.OPEN;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime handledAt;

    private String handledBy;
}
