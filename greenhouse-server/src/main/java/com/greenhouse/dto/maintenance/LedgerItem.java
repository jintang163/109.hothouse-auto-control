package com.greenhouse.dto.maintenance;

import com.greenhouse.entity.MaintenanceRule;

import java.time.LocalDateTime;

/** 台账 + 保养提醒合并视图行（提醒状态实时计算，不落表） */
public record LedgerItem(
        String deviceSn,
        String deviceName,
        Long greenhouseId,
        String greenhouseName,
        String deviceType,
        String deviceTypeName,
        boolean running,
        long totalRunSeconds,
        /** 含在跑尾段的展示用累计小时 */
        double totalRunHours,
        /** 已结算（已闭合段）小时 */
        double settledRunHours,
        /** 当前在跑尾段秒数 */
        long liveSeconds,
        long startCount,
        LocalDateTime lastStartedAt,
        LocalDateTime lastStoppedAt,
        LocalDateTime settledAt,
        RuleInfo rule,
        LocalDateTime lastDoneAt,
        double lastDoneHours,
        /** 无启用规则时为 null */
        Double dueAtHours,
        /** 距保养剩余小时，负=已超期；无规则为 null */
        Double remainingHours,
        /** 0~1+；无规则为 null */
        Double progress,
        /** OVERDUE / DUE_SOON / OK / NO_RULE */
        String status,
        long recentFaultCount30d,
        String suggestion
) {
    public record RuleInfo(String deviceType, int runIntervalHours, int warnAheadHours, boolean enabled) {
        public static RuleInfo of(MaintenanceRule r) {
            return r == null ? null
                    : new RuleInfo(r.getDeviceType().name(), r.getRunIntervalHours(),
                    r.getWarnAheadHours(), r.getEnabled());
        }
    }
}
