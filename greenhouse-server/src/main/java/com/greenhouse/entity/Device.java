package com.greenhouse.entity;

import com.greenhouse.enums.DeviceType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备（传感器 / 风机 / 湿帘 / 遮阳网） */
@Data
@Entity
@Table(name = "gh_device")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long greenhouseId;

    /** 设备 SN，控制指令按 SN 寻址 */
    @Column(nullable = false, unique = true)
    private String sn;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceType type;

    /** 是否在线（随网关连接状态变化） */
    @Column(nullable = false)
    private Boolean online = false;

    /** 执行器当前状态：ON/OFF/OPEN/CLOSED/STOP；传感器为 null */
    private String state;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createdAt = LocalDateTime.now();
}
