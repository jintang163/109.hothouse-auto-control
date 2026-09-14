package com.greenhouse.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 病虫害识别记录（移动端拍照/勾选特征 → 匹配知识库）。
 * 本地演示环境无图像识别模型：图像仅做 base64 留档，识别结论基于特征匹配的候选列表。
 */
@Data
@Entity
@Table(name = "gh_pest_diagnosis", indexes = {
        @Index(name = "idx_diag_gh_time", columnList = "greenhouseId,createdAt")
})
public class PestDiagnosis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long greenhouseId;

    /** 现场照片 base64（缩略，data URI 形式；演示用，可空） */
    @Lob
    private String photoBase64;

    /** 用户勾选的特征关键词 JSON */
    @Lob
    private String selectedFeaturesJson;

    /** 命中的知识库条目（取置信度最高者） */
    private Long matchedKnowledgeId;

    private String matchedName;

    /** 置信度 0~1（命中特征数 / 该条目特征数） */
    private Double confidence;

    /** 候选结果 JSON：[{id,name,confidence}]，供人工复核 */
    @Lob
    private String candidatesJson;

    /** 推送的处置建议（取命中条目 treatment+pesticide 快照） */
    @Column(length = 2048)
    private String advice;

    /** 识别后是否已一键生成防治任务 */
    @Column(nullable = false)
    private Boolean taskCreated = false;

    private Long taskId;

    private String operator;

    private LocalDateTime createdAt = LocalDateTime.now();
}
