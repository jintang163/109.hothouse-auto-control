package com.greenhouse.service.maintenance;

import com.greenhouse.entity.ControlCommand;

import java.time.LocalDateTime;

/**
 * 指令事件时间的唯一口径：<b>回执时间 ackedAt 优先</b>，缺失时依次回退 sentAt、createdAt。
 * <p>
 * 运行台账（ACKED 指令配对启停段）与故障统计（FAILED 指令归集）必须使用同一口径，
 * 否则当指令创建时间与回执时间跨日（或回执延迟较大）时，运行统计与故障统计会落入不同日期。
 * 所有统计查询（窗口过滤、按日归集、最近故障时间）一律走 {@link #of} 与
 * {@link com.greenhouse.repository.ControlCommandRepository#findByStatusAndEventTimeBetween}，
 * 二者使用完全相同的回退链（COALESCE(acked_at, sent_at, created_at)）。
 */
public final class CommandEventTimes {

    private CommandEventTimes() {
    }

    /** 指令事件时间：ackedAt → sentAt → createdAt → fallback */
    public static LocalDateTime of(ControlCommand cmd, LocalDateTime fallback) {
        LocalDateTime t = cmd.getAckedAt() != null ? cmd.getAckedAt()
                : (cmd.getSentAt() != null ? cmd.getSentAt() : cmd.getCreatedAt());
        return t != null ? t : fallback;
    }
}
