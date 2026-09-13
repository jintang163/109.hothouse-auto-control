package com.greenhouse.config;

import com.greenhouse.entity.Alarm;
import com.greenhouse.entity.ControlCommand;
import com.greenhouse.entity.ControlStrategy;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.entity.MaintenanceRule;
import com.greenhouse.enums.AlarmLevel;
import com.greenhouse.enums.AlarmStatus;
import com.greenhouse.enums.AlarmType;
import com.greenhouse.enums.CommandSource;
import com.greenhouse.enums.CommandStatus;
import com.greenhouse.enums.DeviceType;
import com.greenhouse.enums.RunMode;
import com.greenhouse.repository.AlarmRepository;
import com.greenhouse.repository.ControlCommandRepository;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.repository.MaintenanceRuleRepository;
import com.greenhouse.repository.StrategyRepository;
import com.greenhouse.service.maintenance.MaintenanceStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 首次启动写入演示数据：1 号番茄大棚 + 三类执行器 + 默认作物策略，
 * 并回填近 30 天指令/故障数据用于设备运维台账与故障看板，最后执行一次全量重算。
 */
@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    private final GreenhouseRepository greenhouseRepository;
    private final DeviceRepository deviceRepository;
    private final StrategyRepository strategyRepository;
    private final ControlCommandRepository commandRepository;
    private final AlarmRepository alarmRepository;
    private final MaintenanceRuleRepository maintenanceRuleRepository;
    private final MaintenanceStatsService maintenanceStatsService;

    public DataInitializer(GreenhouseRepository greenhouseRepository,
                           DeviceRepository deviceRepository,
                           StrategyRepository strategyRepository,
                           ControlCommandRepository commandRepository,
                           AlarmRepository alarmRepository,
                           MaintenanceRuleRepository maintenanceRuleRepository,
                           MaintenanceStatsService maintenanceStatsService) {
        this.greenhouseRepository = greenhouseRepository;
        this.deviceRepository = deviceRepository;
        this.strategyRepository = strategyRepository;
        this.commandRepository = commandRepository;
        this.alarmRepository = alarmRepository;
        this.maintenanceRuleRepository = maintenanceRuleRepository;
        this.maintenanceStatsService = maintenanceStatsService;
    }

    @Override
    public void run(String... args) {
        if (greenhouseRepository.count() > 0) {
            return;
        }
        Greenhouse gh = new Greenhouse();
        gh.setName("1号番茄大棚");
        gh.setLocation("园区东区 A-01");
        gh.setCrop("番茄");
        gh.setGatewaySn("GW-001");
        gh.setMode(RunMode.AUTO);
        gh = greenhouseRepository.save(gh);
        Long ghId = gh.getId();

        createDevice(ghId, "TH-001", "温湿度光照传感器", DeviceType.SENSOR, null);
        createDevice(ghId, "FAN-001", "1#轴流风机", DeviceType.FAN, "OFF");
        createDevice(ghId, "WC-001", "湿帘水泵", DeviceType.WET_CURTAIN, "CLOSED");
        createDevice(ghId, "SHADE-001", "外遮阳网", DeviceType.SHADE_NET, "CLOSED");

        ControlStrategy strategy = new ControlStrategy();
        strategy.setGreenhouseId(ghId);
        strategy.setName("番茄-结果期环控策略");
        strategy.setEnabled(true);
        strategy.setTempHigh(32.0);
        strategy.setTempRecover(28.0);
        strategy.setTempCritical(38.0);
        strategy.setHumiLow(50.0);
        strategy.setHumiRecover(65.0);
        strategy.setLightHigh(70000.0);
        strategy.setDebounceSec(10);
        strategy.setCooldownSec(60);
        strategy.setScheduleJson("[{\"time\":\"11:30\",\"deviceType\":\"SHADE_NET\",\"action\":\"OPEN\"},"
                + "{\"time\":\"15:00\",\"deviceType\":\"SHADE_NET\",\"action\":\"CLOSE\"}]");
        strategyRepository.save(strategy);

        createDefaultRules();
        seedHistory(ghId);
        maintenanceStatsService.rebuildAll();

        log.info("========== 演示数据已初始化：{}（网关 {}）==========", gh.getName(), gh.getGatewaySn());
    }

    /** 默认保养周期：风机/湿帘水泵每 500h（提前 50h 提醒），遮阳网电机每 200h（提前 20h） */
    private void createDefaultRules() {
        createRule(DeviceType.FAN, 500, 50);
        createRule(DeviceType.WET_CURTAIN, 500, 50);
        createRule(DeviceType.SHADE_NET, 200, 20);
    }

    private void createRule(DeviceType type, int intervalHours, int warnAheadHours) {
        MaintenanceRule rule = new MaintenanceRule();
        rule.setDeviceType(type);
        rule.setRunIntervalHours(intervalHours);
        rule.setWarnAheadHours(warnAheadHours);
        rule.setEnabled(true);
        maintenanceRuleRepository.save(rule);
    }

    /**
     * 回填近 30 天历史（所有运行段均已闭合，与设备初始 OFF/CLOSED 状态一致）：
     * <ul>
     *   <li>FAN-001：30×16.5h（05:30-22:00）+ 近 3 天每晚跨日 2h40m ≈ 503h / 33 次启动 → 已到保养期</li>
     *   <li>WC-001：30×15.4h（06:30-21:54）= 462h → 临近保养（阈值 450h）</li>
     *   <li>SHADE-001：30×0.5h = 15h → 正常</li>
     * </ul>
     * 故障：FAN 4 次（过载 3 + 超时 1，触发备件建议）、WC 2 次、SHADE 1 次；另 2 次网关离线。
     */
    private void seedHistory(Long ghId) {
        LocalDate today = LocalDate.now();

        for (int daysAgo = 1; daysAgo <= 30; daysAgo++) {
            LocalDate day = today.minusDays(daysAgo);
            // 风机 05:30 - 22:00（16.5h）
            addAcked(ghId, "FAN-001", "ON", day.atTime(5, 30));
            addAcked(ghId, "FAN-001", "OFF", day.atTime(22, 0));
            // 湿帘水泵 06:30 - 21:54（15.4h）
            addAcked(ghId, "WC-001", "OPEN", day.atTime(6, 30));
            addAcked(ghId, "WC-001", "CLOSE", day.atTime(21, 54));
            // 遮阳网 10:00 - 10:30（0.5h）
            addAcked(ghId, "SHADE-001", "OPEN", day.atTime(10, 0));
            addAcked(ghId, "SHADE-001", "CLOSE", day.atTime(10, 30));

            // 近 3 天风机晚间额外运行段 22:20 - 次日 01:00（跨日，2h40m）
            if (daysAgo <= 3) {
                addAcked(ghId, "FAN-001", "ON", day.atTime(22, 20));
                addAcked(ghId, "FAN-001", "OFF", day.plusDays(1).atTime(1, 0));
            }
        }

        // FAILED 指令（每次失败独立落库，是故障统计的事实源）
        addFailed(ghId, "FAN-001", "ON", today.minusDays(6).atTime(9, 15), "设备执行失败: 执行器过载（模拟）");
        addFailed(ghId, "FAN-001", "ON", today.minusDays(4).atTime(14, 42), "设备执行失败: 执行器过载（模拟）");
        addFailed(ghId, "FAN-001", "ON", today.minusDays(1).atTime(21, 14), "设备执行失败: 执行器过载（模拟）");
        addFailed(ghId, "FAN-001", "ON", today.minusDays(12).atTime(3, 5), "回执超时，重试 3 次仍无响应");
        addFailed(ghId, "WC-001", "OPEN", today.minusDays(8).atTime(11, 20), "设备执行失败: 执行器过载（模拟）");
        addFailed(ghId, "WC-001", "OPEN", today.minusDays(3).atTime(16, 48), "回执超时，重试 3 次仍无响应");
        addFailed(ghId, "SHADE-001", "OPEN", today.minusDays(5).atTime(10, 2), "回执超时，重试 3 次仍无响应");

        // 历史网关离线告警（已处理，仅作看板参考计数）
        addOfflineAlarm(ghId, today.minusDays(20).atTime(2, 31));
        addOfflineAlarm(ghId, today.minusDays(5).atTime(18, 7));
    }

    private void addAcked(Long ghId, String sn, String action, LocalDateTime t) {
        ControlCommand cmd = baseCommand(ghId, sn, action);
        cmd.setStatus(CommandStatus.ACKED);
        cmd.setCreatedAt(t);
        cmd.setSentAt(t);
        cmd.setAckedAt(t);
        commandRepository.save(cmd);
    }

    private void addFailed(Long ghId, String sn, String action, LocalDateTime t, String errorMsg) {
        ControlCommand cmd = baseCommand(ghId, sn, action);
        cmd.setStatus(CommandStatus.FAILED);
        cmd.setRetryCount(3);
        cmd.setErrorMsg(errorMsg);
        cmd.setCreatedAt(t);
        cmd.setSentAt(t);
        commandRepository.save(cmd);
    }

    private ControlCommand baseCommand(Long ghId, String sn, String action) {
        ControlCommand cmd = new ControlCommand();
        cmd.setCommandId(UUID.randomUUID().toString());
        cmd.setGreenhouseId(ghId);
        cmd.setDeviceSn(sn);
        cmd.setAction(action);
        cmd.setSource(CommandSource.AUTO_RULE);
        cmd.setRetryCount(0);
        cmd.setMaxRetry(3);
        return cmd;
    }

    private void addOfflineAlarm(Long ghId, LocalDateTime t) {
        Alarm alarm = new Alarm();
        alarm.setGreenhouseId(ghId);
        alarm.setDeviceSn("GW-001");
        alarm.setType(AlarmType.DEVICE_OFFLINE);
        alarm.setLevel(AlarmLevel.WARN);
        alarm.setMessage("网关 GW-001 掉线，大棚「" + "1号番茄大棚" + "」设备全部离线");
        alarm.setStatus(AlarmStatus.HANDLED);
        alarm.setCreatedAt(t);
        alarm.setHandledAt(t.plusMinutes(8));
        alarm.setHandledBy("system");
        alarmRepository.save(alarm);
    }

    private void createDevice(Long ghId, String sn, String name, DeviceType type, String state) {
        Device device = new Device();
        device.setGreenhouseId(ghId);
        device.setSn(sn);
        device.setName(name);
        device.setType(type);
        device.setState(state);
        deviceRepository.save(device);
    }
}
