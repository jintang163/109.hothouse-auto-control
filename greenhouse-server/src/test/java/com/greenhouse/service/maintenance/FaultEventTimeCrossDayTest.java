package com.greenhouse.service.maintenance;

import com.greenhouse.dto.maintenance.FaultStatsDto;
import com.greenhouse.dto.maintenance.LedgerItem;
import com.greenhouse.entity.ControlCommand;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.DeviceRuntimeDaily;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.enums.CommandSource;
import com.greenhouse.enums.CommandStatus;
import com.greenhouse.enums.DeviceType;
import com.greenhouse.repository.AlarmRepository;
import com.greenhouse.repository.ControlCommandRepository;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.DeviceRuntimeDailyRepository;
import com.greenhouse.repository.DeviceRuntimeRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.repository.MaintenanceRecordRepository;
import com.greenhouse.repository.MaintenanceRuleRepository;
import com.greenhouse.ws.RealtimePushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 故障事件时间口径一致性测试：FAILED 指令一律按「回执时间」（ackedAt → sentAt → createdAt）
 * 归集，与运行台账的 ACKED 配对口径一致。重点覆盖跨日延迟回执：
 * 指令 23:59 创建、次日 00:00 才失败回执时，故障必须计入回执发生日而非创建日。
 */
@DataJpaTest
class FaultEventTimeCrossDayTest {

    private static final String FAN_SN = "FAN-T1";

    @Autowired
    private ControlCommandRepository commandRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private GreenhouseRepository greenhouseRepository;
    @Autowired
    private DeviceRuntimeRepository runtimeRepository;
    @Autowired
    private DeviceRuntimeDailyRepository dailyRepository;
    @Autowired
    private MaintenanceRuleRepository ruleRepository;
    @Autowired
    private MaintenanceRecordRepository recordRepository;
    @Autowired
    private AlarmRepository alarmRepository;

    private MaintenanceService maintenanceService;
    private MaintenanceStatsService statsService;
    private Long greenhouseId;

    @BeforeEach
    void setUp() {
        statsService = new MaintenanceStatsService(runtimeRepository, dailyRepository,
                commandRepository, deviceRepository);
        maintenanceService = new MaintenanceService(deviceRepository, greenhouseRepository,
                runtimeRepository, ruleRepository, recordRepository, commandRepository,
                alarmRepository, statsService, Mockito.mock(RealtimePushService.class));

        Greenhouse gh = new Greenhouse();
        gh.setName("测试大棚");
        gh.setGatewaySn("GW-T1");
        greenhouseId = greenhouseRepository.save(gh).getId();

        Device fan = new Device();
        fan.setGreenhouseId(greenhouseId);
        fan.setSn(FAN_SN);
        fan.setName("1号风机");
        fan.setType(DeviceType.FAN);
        fan.setOnline(true);
        fan.setState("OFF");
        deviceRepository.save(fan);
    }

    private ControlCommand failedCmd(String commandId, LocalDateTime createdAt,
                                     LocalDateTime sentAt, LocalDateTime ackedAt) {
        ControlCommand cmd = new ControlCommand();
        cmd.setCommandId(commandId);
        cmd.setGreenhouseId(greenhouseId);
        cmd.setDeviceSn(FAN_SN);
        cmd.setAction("ON");
        cmd.setSource(CommandSource.AUTO_RULE);
        cmd.setStatus(CommandStatus.FAILED);
        cmd.setErrorMsg("回执超时，重试 3 次仍无响应");
        cmd.setCreatedAt(createdAt);
        cmd.setSentAt(sentAt);
        cmd.setAckedAt(ackedAt);
        return commandRepository.save(cmd);
    }

    private Map<LocalDate, Integer> trendTotals(FaultStatsDto stats) {
        return stats.trend().stream().collect(Collectors.toMap(
                FaultStatsDto.TrendPoint::date, FaultStatsDto.TrendPoint::total));
    }

    @Test
    void repositoryFiltersByEventTimeNotCreatedAt() {
        LocalDate today = LocalDate.now();
        LocalDateTime windowStart = today.minusDays(MaintenanceStatsService.WINDOW_DAYS - 1L).atStartOfDay();

        // 创建在窗口外（窗口前一日 23:58）、失败回执在窗口内（窗口日 00:02）→ 计入
        failedCmd("C-ACK-IN", windowStart.minusMinutes(2), windowStart.minusMinutes(2), windowStart.plusMinutes(2));
        // 创建与回执都在窗口外 → 不计入
        failedCmd("C-ALL-OUT", windowStart.minusMinutes(10), windowStart.minusMinutes(10), windowStart.minusMinutes(5));
        // 无回执：回退 sentAt，sentAt 在窗口内（createdAt 在窗口外）→ 计入
        failedCmd("C-SENT-IN", windowStart.minusDays(2), today.atStartOfDay(), null);
        // 无回执无下发：回退 createdAt，在窗口内 → 计入
        failedCmd("C-LEGACY-IN", today.atStartOfDay(), null, null);
        // 无回执无下发，createdAt 在窗口外 → 不计入
        failedCmd("C-LEGACY-OUT", windowStart.minusDays(1), null, null);

        List<String> ids = commandRepository.findByStatusAndEventTimeBetween(
                        CommandStatus.FAILED, windowStart, LocalDateTime.now())
                .stream().map(ControlCommand::getCommandId).toList();

        assertThat(ids).containsExactlyInAnyOrder("C-ACK-IN", "C-SENT-IN", "C-LEGACY-IN");
    }

    @Test
    void faultStatsAttributesCrossDayFaultToReceiptDay() {
        LocalDate today = LocalDate.now();
        // 昨日 23:59:30 创建并下发，次日 00:00 才耗尽重试转 FAILED（跨日延迟回执）
        failedCmd("C-CROSS-DAY", today.minusDays(1).atTime(23, 59, 30),
                today.minusDays(1).atTime(23, 59, 30), today.atStartOfDay());

        FaultStatsDto stats = maintenanceService.faultStats(30, null);

        Map<LocalDate, Integer> trend = trendTotals(stats);
        assertThat(trend.get(today)).as("故障计入回执发生日").isEqualTo(1);
        assertThat(trend.get(today.minusDays(1))).as("创建日不计故障").isZero();
        assertThat(stats.totalFaults()).isEqualTo(1);
        assertThat(stats.byType())
                .filteredOn(r -> r.deviceType().equals(DeviceType.FAN.name()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.total()).isEqualTo(1);
                    assertThat(r.byCategory().get("TIMEOUT")).isEqualTo(1);
                });
    }

    @Test
    void faultStatsLastFaultAtUsesReceiptTime() {
        LocalDate today = LocalDate.now();
        // 3 次故障触发备件建议；最近一次为跨日回执（昨日创建、今日 00:00 回执）
        failedCmd("C-1", today.minusDays(2).atTime(23, 58), today.minusDays(2).atTime(23, 58),
                today.minusDays(1).atTime(0, 1));
        failedCmd("C-2", today.minusDays(1).atTime(12, 0), today.minusDays(1).atTime(12, 0),
                today.minusDays(1).atTime(12, 0, 5));
        failedCmd("C-3", today.minusDays(1).atTime(23, 59), today.minusDays(1).atTime(23, 59),
                today.atStartOfDay());

        FaultStatsDto stats = maintenanceService.faultStats(30, null);

        assertThat(stats.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.deviceSn()).isEqualTo(FAN_SN);
            assertThat(s.faultCount()).isEqualTo(3);
            assertThat(s.lastFaultAt()).as("最近故障时间取回执时间").isEqualTo(today.atStartOfDay());
        });
        Map<LocalDate, Integer> trend = trendTotals(stats);
        assertThat(trend.get(today.minusDays(2))).isZero();
        assertThat(trend.get(today.minusDays(1))).isEqualTo(2);
        assertThat(trend.get(today)).isEqualTo(1);
    }

    @Test
    void ledgerCountsFaultsByEventTimeWithin30DayWindow() {
        LocalDate today = LocalDate.now();
        // 31 天前创建（30 天窗口外）、昨日才失败回执（窗口内）：按回执口径应计入近 30 天故障
        failedCmd("C-DELAYED-IN", today.minusDays(31).atTime(23, 59), today.minusDays(31).atTime(23, 59),
                today.minusDays(1).atTime(10, 0));
        // 31 天前创建并回执（窗口外）→ 不计入
        failedCmd("C-OLD-OUT", today.minusDays(31).atTime(1, 0), today.minusDays(31).atTime(1, 0),
                today.minusDays(31).atTime(1, 0, 5));

        List<LedgerItem> ledger = maintenanceService.ledger(null);

        assertThat(ledger).filteredOn(i -> i.deviceSn().equals(FAN_SN))
                .singleElement()
                .satisfies(i -> assertThat(i.recentFaultCount30d()).isEqualTo(1));
    }

    @Test
    void rebuildAllAttributesFaultToReceiptDayAndIsIdempotent() {
        LocalDate today = LocalDate.now();
        // 昨日 23:59:30 创建、今日 00:00 失败回执 → 日结故障数落在今日
        failedCmd("C-CROSS-DAY", today.minusDays(1).atTime(23, 59, 30),
                today.minusDays(1).atTime(23, 59, 30), today.atStartOfDay());

        statsService.rebuildAll();
        Map<LocalDate, Integer> first = faultCountByDate(today);
        assertThat(first.get(today)).as("日结故障计入回执发生日").isEqualTo(1);
        assertThat(first.get(today.minusDays(1))).as("创建日日结无故障").isZero();

        // 幂等：重复全量重算，日结故障数不变
        statsService.rebuildAll();
        Map<LocalDate, Integer> second = faultCountByDate(today);
        assertThat(second).isEqualTo(first);
    }

    private Map<LocalDate, Integer> faultCountByDate(LocalDate today) {
        return dailyRepository.findByDeviceSnAndStatDateBetween(
                        FAN_SN, today.minusDays(MaintenanceStatsService.WINDOW_DAYS - 1L), today)
                .stream().collect(Collectors.toMap(
                        DeviceRuntimeDaily::getStatDate, DeviceRuntimeDaily::getFaultCount));
    }
}
