package com.greenhouse.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 传感数据点（温度/湿度/光照等，按指标分行存储） */
@Data
@Entity
@Table(name = "gh_sensor_record", indexes = {
        @Index(name = "idx_record_gh_metric_time", columnList = "greenhouseId,metric,recordedAt")
})
public class SensorRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long greenhouseId;

    /** temperature / humidity / light / co2 */
    @Column(nullable = false)
    private String metric;

    @Column(name = "metric_value", nullable = false)
    private Double value;

    @Column(nullable = false)
    private LocalDateTime recordedAt = LocalDateTime.now();
}
