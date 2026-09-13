package com.greenhouse.dto.maintenance;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/** 故障统计看板数据（近 N 天，按 FAILED 指令聚合；DEVICE_OFFLINE 仅作参考计数） */
public record FaultStatsDto(
        LocalDateTime generatedAt,
        int days,
        int totalFaults,
        long offlineCount,
        java.util.List<ByTypeRow> byType,
        java.util.List<ByGreenhouseRow> byGreenhouse,
        java.util.List<TrendPoint> trend,
        java.util.List<Suggestion> suggestions
) {
    /** key 固定顺序 OVERLOAD/TIMEOUT/OTHER */
    public record ByTypeRow(String deviceType, String deviceTypeName, int total, Map<String, Integer> byCategory) {}

    public record ByGreenhouseRow(Long greenhouseId, String greenhouseName, int total, Map<String, Integer> byCategory) {}

    public record TrendPoint(LocalDate date, int total, Map<String, Integer> byCategory) {}

    public record Suggestion(String deviceSn, String deviceName, String greenhouseName,
                             int faultCount, Map<String, Integer> categories,
                             LocalDateTime lastFaultAt, String message) {}
}
