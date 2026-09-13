package com.greenhouse.controller;

import com.greenhouse.dto.ApiResponse;
import com.greenhouse.dto.maintenance.FaultStatsDto;
import com.greenhouse.dto.maintenance.LedgerItem;
import com.greenhouse.entity.MaintenanceRecord;
import com.greenhouse.entity.MaintenanceRule;
import com.greenhouse.service.maintenance.MaintenanceService;
import com.greenhouse.service.maintenance.MaintenanceStatsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 设备运维：运行台账 / 保养提醒与规则 / 保养登记 / 故障统计看板 / 备件建议。
 * 全部为只读统计与运维登记，不涉及设备控制。
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    /** 运行台账 + 保养提醒合并视图 */
    @GetMapping("/ledger")
    public ApiResponse<List<LedgerItem>> ledger(@RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(maintenanceService.ledger(greenhouseId));
    }

    /** 仅待处理提醒（OVERDUE / DUE_SOON），管理端大屏与移动端卡片使用 */
    @GetMapping("/reminders")
    public ApiResponse<List<LedgerItem>> reminders(@RequestParam(required = false) Long greenhouseId,
                                                   @RequestParam(required = false) String status) {
        return ApiResponse.ok(maintenanceService.reminders(greenhouseId, status));
    }

    @GetMapping("/rules")
    public ApiResponse<List<MaintenanceRule>> rules() {
        return ApiResponse.ok(maintenanceService.listRules());
    }

    /** 批量保存保养周期（按设备类型） */
    @PutMapping("/rules")
    public ApiResponse<List<MaintenanceRule>> saveRules(@RequestBody List<MaintenanceRule> rules) {
        return ApiResponse.ok(maintenanceService.saveRules(rules));
    }

    @GetMapping("/records")
    public ApiResponse<List<MaintenanceRecord>> records(@RequestParam(required = false) Long greenhouseId,
                                                        @RequestParam(required = false) String deviceSn) {
        return ApiResponse.ok(maintenanceService.listRecords(greenhouseId, deviceSn));
    }

    /** 登记保养（body: deviceSn/operator/note?/runHoursAtDone?/doneAt?） */
    @PostMapping("/records")
    public ApiResponse<MaintenanceRecord> createRecord(@RequestBody MaintenanceRecord record) {
        return ApiResponse.ok(maintenanceService.createRecord(record));
    }

    /** 故障统计看板：按设备类型/大棚聚合 + 近 N 天趋势 + 备件建议 */
    @GetMapping("/fault-stats")
    public ApiResponse<FaultStatsDto> faultStats(@RequestParam(defaultValue = "30") int days,
                                                 @RequestParam(required = false) Long greenhouseId) {
        return ApiResponse.ok(maintenanceService.faultStats(days, greenhouseId));
    }

    /** 立即执行一次全量统计（凌晨日结的手动触发入口，幂等） */
    @PostMapping("/run-now")
    public ApiResponse<MaintenanceStatsService.RebuildResult> runNow() {
        return ApiResponse.ok(maintenanceService.runNow());
    }
}
