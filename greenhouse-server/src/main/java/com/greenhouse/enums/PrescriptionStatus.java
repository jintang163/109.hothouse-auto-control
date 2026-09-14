package com.greenhouse.enums;

/** 处方版本状态：草稿可编辑，发布后只读，归档后不再用于自动生成任务 */
public enum PrescriptionStatus {
    /** 草稿（可编辑、可删除） */
    DRAFT,
    /** 已发布（只读，供任务生成匹配） */
    PUBLISHED,
    /** 已归档（历史版本留存） */
    ARCHIVED
}
