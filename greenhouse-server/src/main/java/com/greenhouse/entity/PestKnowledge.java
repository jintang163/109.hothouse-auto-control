package com.greenhouse.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 病虫害知识库条目（图谱 + 防治处方）。
 * featuresJson 为鉴别特征关键词数组，移动端拍照识别无图像模型时按人工勾选特征加权匹配。
 */
@Data
@Entity
@Table(name = "gh_pest_knowledge")
public class PestKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 名称，如 番茄早疫病 / 烟粉虱 */
    @Column(nullable = false)
    private String name;

    /** 类型：病害 / 虫害 / 生理性病害 */
    @Column(nullable = false)
    private String category;

    /** 危害作物（多个逗号分隔） */
    private String crops;

    /**
     * 鉴别特征关键词 JSON，如
     * {@code ["褐色同心轮纹斑","叶片下部先发病","黑色霉层"]}
     */
    @Lob
    private String featuresJson;

    /** 典型症状描述（图谱文字） */
    @Column(length = 1024)
    private String symptoms;

    /** 发生条件/规律 */
    @Column(length = 1024)
    private String cause;

    /** 防治措施（农业/物理/生物/化学） */
    @Column(length = 2048)
    private String treatment;

    /** 推荐用药（剂量、安全间隔期） */
    @Column(length = 1024)
    private String pesticide;

    /** 图谱图片 URL（内置占位图地址或相对路径） */
    private String imageUrl;

    private LocalDateTime createdAt = LocalDateTime.now();
}
