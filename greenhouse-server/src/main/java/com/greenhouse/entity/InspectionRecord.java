package com.greenhouse.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 巡检记录（移动端上报） */
@Data
@Entity
@Table(name = "gh_inspection")
public class InspectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long greenhouseId;

    @Column(nullable = false)
    private String inspector;

    /** 巡检内容/发现问题 */
    @Column(nullable = false, length = 1024)
    private String content;

    /** 结论：正常 / 异常 */
    @Column(nullable = false)
    private String result;

    private LocalDateTime createdAt = LocalDateTime.now();
}
