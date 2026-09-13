package com.greenhouse.service.maintenance;

import com.greenhouse.dto.maintenance.FaultStatsDto;
import com.greenhouse.dto.maintenance.LedgerItem;
import com.greenhouse.entity.ControlCommand;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.DeviceRuntime;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.entity.MaintenanceRecord;
import com.greenhouse.entity.MaintenanceRule;
import com.greenhouse.enums.AlarmType;
import com.greenhouse.enums.CommandStatus;
import com.greenhouse.enums.DeviceType;
import com.greenhouse.enums.FaultCategory;
import com.greenhouse.repository.AlarmRepository;
import com.greenhouse.repository.ControlCommandRepository;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.DeviceRuntimeRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.repository.MaintenanceRecordRepository;
import com.greenhouse.repository.MaintenanceRuleRepository;
import com.greenhouse.ws.RealtimePushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 设备运维业务：台账视图、保养提醒（实时计算）、保养规则与登记、故障统计与备件建议。
 * 只读取指令/告警/设备数据做统计分析，不产生任何控制指令。
 */
@Slf4j
@Service
public class MaintenanceService {

    public static final int SPARE_ADVICE_THRESHOLD = 3;

    private static final Set<DeviceType> ACTUATORS =
            Set.of(DeviceType.FAN, DeviceType.WET_CURTAIN, DeviceType.SHADE_NET);
    private static final List<FaultCategory> CATEGORY_ORDER =
            List.of(FaultCategory.OVERLOAD, FaultCategory.TIMEOUT, FaultCategory.OTHER);

    private final DeviceRepository deviceRepository;
    private final GreenhouseRepository greenhouseRepository;
    private final DeviceRuntimeRepository runtimeRepository;
    private final MaintenanceRuleRepository ruleRepository;
    private final MaintenanceRecordRepository recordRepository;
    private final ControlCommandRepository commandRepository;
    private final AlarmRepository alarmRepository;
    private final MaintenanceStatsService statsService;
    private final RealtimePushService pushService;

    public MaintenanceService(DeviceRepository deviceRepository,
                              GreenhouseRepository greenhouseRepository,
                              DeviceRuntimeRepository runtimeRepository,
                              MaintenanceRuleRepository ruleRepository,
                              MaintenanceRecordRepository recordRepository,
                              ControlCommandRepository commandRepository,
                              AlarmRepository alarmRepository,
                              MaintenanceStatsService statsService,
                              RealtimePushService pushService) {
        this.deviceRepository = deviceRepository;
        this.greenhouseRepository = greenhouseRepository;
        this.runtimeRepository = runtimeRepository;
        this.ruleRepository = ruleRepository;
        this.recordRepository = recordRepository;
        this.commandRepository = commandRepository;
        this.alarmRepository = alarmRepository;
        this.statsService = statsService;
        this.pushService = pushService;
    }

    // ==================== 台账 + 提醒 ====================

    public List<LedgerItem> ledger(Long greenhouseId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = LocalDate.now().minusDays(MaintenanceStatsService.WINDOW_DAYS - 1L).atStartOfDay();

        Map<Long, String> ghNames = new HashMap<>();
        greenhouseRepository.findAll().forEach(g -> ghNames.put(g.getId(), g.getName()));
        Map<DeviceType, MaintenanceRule> rules = new EnumMap<>(DeviceType.class);
        ruleRepository.findAll().forEach(r -> rules.put(r.getDeviceType(), r));
        Map<String, Integer> fault30d = new HashMap<>();
        Map<String, EnumMap<FaultCategory, Integer>> faultCats = new HashMap<>();
        commandRepository.findByStatusAndCreatedAtBetween(CommandStatus.FAILED, windowStart, now)
                .forEach(cmd -> {
                    fault30d.merge(cmd.getDeviceSn(), 1, Integer::sum);
                    faultCats.computeIfAbsent(cmd.getDeviceSn(), k -> new EnumMap<>(FaultCategory.class))
                            .merge(categorize(cmd.getErrorMsg()), 1, Integer::sum);
                });

        List<Device> devices = deviceRepository.findAll().stream()
                .filter(d -> ACTUATORS.contains(d.getType()))
                .filter(d -> greenhouseId == null || greenhouseId.equals(d.getGreenhouseId()))
                .sorted(Comparator.comparing(Device::getSn))
                .toList();

        List<LedgerItem> items = new ArrayList<>();
        for (Device device : devices) {
            DeviceRuntime rt = runtimeRepository.findByDeviceSn(device.getSn()).orElse(null);
            long settledSeconds = rt == null ? 0L : rt.getTotalRunSeconds();
            boolean running = isRunning(device);
            long liveSeconds = 0L;
            if (running && rt != null && rt.getLastStartedAt() != null) {
                liveSeconds = Math.max(0, ChronoUnit.SECONDS.between(rt.getLastStartedAt(), now));
            }
            double settledHours = settledSeconds / 3600.0;
            double liveHours = liveSeconds / 3600.0;
            double totalHours = round1(settledHours + liveHours);
            long startCount = rt == null ? 0L : rt.getStartCount();

            MaintenanceRule rule = rules.get(device.getType());
            MaintenanceRecord lastDone = recordRepository.findTopByDeviceSnOrderByDoneAtDesc(device.getSn())
                    .orElse(null);
            double lastDoneHours = lastDone == null ? 0.0 : round1(lastDone.getRunHoursAtDone());

            String status = "NO_RULE";
            Double dueAtHours = null;
            Double remainingHours = null;
            Double progress = null;
            if (rule != null && Boolean.TRUE.equals(rule.getEnabled())) {
                dueAtHours = (double) lastDoneHours + rule.getRunIntervalHours();
                remainingHours = round1(dueAtHours - totalHours);
                progress = Math.round(totalHours / dueAtHours * 1000.0) / 1000.0;
                if (totalHours >= dueAtHours) {
                    status = "OVERDUE";
                } else if (totalHours >= dueAtHours - rule.getWarnAheadHours()) {
                    status = "DUE_SOON";
                } else {
                    status = "OK";
                }
            }

            int faults = fault30d.getOrDefault(device.getSn(), 0);
            String suggestion = buildSuggestion(device, status, totalHours, dueAtHours, remainingHours,
                    faults, faultCats.get(device.getSn()));

            items.add(new LedgerItem(
                    device.getSn(), device.getName(), device.getGreenhouseId(),
                    ghNames.getOrDefault(device.getGreenhouseId(), "-"),
                    device.getType().name(), typeName(device.getType()),
                    running, settledSeconds + liveSeconds, totalHours, round1(settledHours), liveSeconds,
                    startCount,
                    rt == null ? null : rt.getLastStartedAt(),
                    rt == null ? null : rt.getLastStoppedAt(),
                    rt == null ? null : rt.getSettledAt(),
                    LedgerItem.RuleInfo.of(rule),
                    lastDone == null ? null : lastDone.getDoneAt(),
                    lastDoneHours, dueAtHours, remainingHours, progress, status,
                    faults, suggestion));
        }
        // 到期在前、临近其后，同状态按剩余小时升序
        items.sort(Comparator
                .comparingInt((LedgerItem i) -> "OVERDUE".equals(i.status()) ? 0 : "DUE_SOON".equals(i.status()) ? 1 : 2)
                .thenComparing(i -> i.remainingHours() == null ? Double.MAX_VALUE : i.remainingHours()));
        return items;
    }

    /** 保养提醒：非 OK/NO_RULE 的设备 */
    public List<LedgerItem> reminders(Long greenhouseId, String status) {
        return ledger(greenhouseId).stream()
                .filter(i -> !"OK".equals(i.status()) && !"NO_RULE".equals(i.status()))
                .filter(i -> status == null || status.isBlank() || status.equals(i.status()))
                .toList();
    }

    private String buildSuggestion(Device device, String status, double totalHours, Double dueAtHours,
                                   Double remainingHours, int faults,
                                   EnumMap<FaultCategory, Integer> cats) {
        StringBuilder sb = new StringBuilder();
        if (faults >= SPARE_ADVICE_THRESHOLD) {
            sb.append("该").append(typeName(device.getType())).append("近期故障").append(faults).append("次");
            if (cats != null) {
                List<String> parts = new ArrayList<>();
                CATEGORY_ORDER.stream()
                        .filter(c -> cats.getOrDefault(c, 0) > 0)
                        .forEach(c -> parts.add(c.getLabel() + cats.get(c) + "次"));
                if (!parts.isEmpty()) {
                    sb.append("（").append(String.join("、", parts)).append("）");
                }
            }
            sb.append("，建议检修或更换");
        }
        if ("OVERDUE".equals(status)) {
            sb.append(sb.isEmpty() ? "" : "；")
                    .append("已超保养周期").append(Math.abs(remainingHours == null ? 0 : round1(-remainingHours)))
                    .append("小时（累计").append(totalHours).append("h/周期").append(dueAtHours).append("h），建议立即保养");
        } else if ("DUE_SOON".equals(status)) {
            sb.append(sb.isEmpty() ? "" : "；")
                    .append("累计运行").append(totalHours).append("小时，临近保养周期（")
                    .append(dueAtHours).append("h），建议提前安排保养");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    // ==================== 规则 ====================

    public List<MaintenanceRule> listRules() {
        return ruleRepository.findAllByOrderByDeviceType();
    }

    @Transactional
    public List<MaintenanceRule> saveRules(List<MaintenanceRule> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            throw new IllegalArgumentException("规则不能为空");
        }
        List<MaintenanceRule> result = new ArrayList<>();
        for (MaintenanceRule req : incoming) {
            if (req.getDeviceType() == null || !ACTUATORS.contains(req.getDeviceType())) {
                throw new IllegalArgumentException("非法设备类型: " + req.getDeviceType());
            }
            Integer interval = req.getRunIntervalHours();
            Integer ahead = req.getWarnAheadHours();
            if (interval == null || interval <= 0) {
                throw new IllegalArgumentException("保养间隔必须大于 0");
            }
            if (ahead == null || ahead <= 0 || ahead >= interval) {
                throw new IllegalArgumentException("提前提醒小时须为正且小于保养间隔");
            }
            MaintenanceRule rule = ruleRepository.findByDeviceType(req.getDeviceType()).orElseGet(() -> {
                MaintenanceRule r = new MaintenanceRule();
                r.setDeviceType(req.getDeviceType());
                return r;
            });
            rule.setRunIntervalHours(interval);
            rule.setWarnAheadHours(ahead);
            rule.setEnabled(req.getEnabled() == null || req.getEnabled());
            rule.setUpdatedAt(LocalDateTime.now());
            result.add(ruleRepository.save(rule));
        }
        return result;
    }

    // ==================== 保养登记 ====================

    public List<MaintenanceRecord> listRecords(Long greenhouseId, String deviceSn) {
        if (deviceSn != null && !deviceSn.isBlank()) {
            return recordRepository.findByDeviceSnOrderByDoneAtDesc(deviceSn);
        }
        if (greenhouseId != null) {
            return recordRepository.findByGreenhouseIdOrderByDoneAtDesc(greenhouseId);
        }
        return recordRepository.findTop100ByOrderByDoneAtDesc();
    }

    @Transactional
    public MaintenanceRecord createRecord(MaintenanceRecord req) {
        if (req.getDeviceSn() == null || req.getDeviceSn().isBlank()) {
            throw new IllegalArgumentException("设备 SN 不能为空");
        }
        Device device = deviceRepository.findBySn(req.getDeviceSn())
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + req.getDeviceSn()));

        MaintenanceRecord record = new MaintenanceRecord();
        record.setDeviceSn(device.getSn());
        record.setDeviceType(device.getType());
        record.setGreenhouseId(device.getGreenhouseId());
        record.setOperator(req.getOperator() == null || req.getOperator().isBlank() ? "admin" : req.getOperator());
        record.setNote(req.getNote());
        record.setDoneAt(req.getDoneAt() != null ? req.getDoneAt() : LocalDateTime.now());
        if (req.getRunHoursAtDone() != null) {
            record.setRunHoursAtDone(round1(req.getRunHoursAtDone()));
        } else {
            // 默认取登记时台账累计小时（含在跑尾段）
            double current = ledger(device.getGreenhouseId()).stream()
                    .filter(i -> i.deviceSn().equals(device.getSn()))
                    .findFirst().map(LedgerItem::totalRunHours).orElse(0.0);
            record.setRunHoursAtDone(current);
        }
        MaintenanceRecord saved = recordRepository.save(record);
        log.info("[保养登记] {} 由 {} 登记，累计 {}h", device.getSn(), record.getOperator(),
                record.getRunHoursAtDone());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "RECORD_CREATED");
        payload.put("deviceSn", saved.getDeviceSn());
        payload.put("deviceName", device.getName());
        payload.put("operator", saved.getOperator());
        pushService.broadcast("maintenance", payload);
        return saved;
    }

    // ==================== 故障统计看板 ====================

    public FaultStatsDto faultStats(int days, Long greenhouseId) {
        if (days <= 0 || days > 365) {
            days = MaintenanceStatsService.WINDOW_DAYS;
        }
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(days - 1L);
        LocalDateTime from = startDate.atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        Map<String, Device> deviceMap = new HashMap<>();
        deviceRepository.findAll().forEach(d -> deviceMap.put(d.getSn(), d));
        Map<Long, Greenhouse> ghMap = new HashMap<>();
        greenhouseRepository.findAll().forEach(g -> ghMap.put(g.getId(), g));

        // 维度 -> [OVERLOAD, TIMEOUT, OTHER]
        Map<DeviceType, int[]> byType = new EnumMap<>(DeviceType.class);
        Map<Long, int[]> byGh = new HashMap<>();
        Map<LocalDate, int[]> byDay = new HashMap<>();
        // 设备维度（备件建议用）
        Map<String, int[]> byDevice = new HashMap<>();
        Map<String, LocalDateTime> lastFaultAt = new HashMap<>();
        int total = 0;

        for (ControlCommand cmd : commandRepository.findByStatusAndCreatedAtBetween(CommandStatus.FAILED, from, now)) {
            Device d = deviceMap.get(cmd.getDeviceSn());
            if (d == null || !ACTUATORS.contains(d.getType())) {
                continue;
            }
            if (greenhouseId != null && !greenhouseId.equals(d.getGreenhouseId())) {
                continue;
            }
            int cat = categorize(cmd.getErrorMsg()).ordinal();
            byType.computeIfAbsent(d.getType(), k -> new int[3])[cat]++;
            byGh.computeIfAbsent(d.getGreenhouseId(), k -> new int[3])[cat]++;
            byDay.computeIfAbsent(cmd.getCreatedAt().toLocalDate(), k -> new int[3])[cat]++;
            byDevice.computeIfAbsent(cmd.getDeviceSn(), k -> new int[3])[cat]++;
            lastFaultAt.merge(cmd.getDeviceSn(), cmd.getCreatedAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
            total++;
        }

        List<FaultStatsDto.ByTypeRow> typeRows = new ArrayList<>();
        for (DeviceType t : List.of(DeviceType.FAN, DeviceType.WET_CURTAIN, DeviceType.SHADE_NET)) {
            int[] c = byType.getOrDefault(t, new int[3]);
            typeRows.add(new FaultStatsDto.ByTypeRow(t.name(), typeName(t), sum(c), categoryMap(c)));
        }

        List<FaultStatsDto.ByGreenhouseRow> ghRows = byGh.entrySet().stream()
                .sorted((a, b) -> sum(b.getValue()) - sum(a.getValue()))
                .map(e -> {
                    Greenhouse g = ghMap.get(e.getKey());
                    return new FaultStatsDto.ByGreenhouseRow(e.getKey(),
                            g == null ? "#" + e.getKey() : g.getName(),
                            sum(e.getValue()), categoryMap(e.getValue()));
                })
                .toList();

        List<FaultStatsDto.TrendPoint> trend = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(today); d = d.plusDays(1)) {
            int[] c = byDay.getOrDefault(d, new int[3]);
            trend.add(new FaultStatsDto.TrendPoint(d, sum(c), categoryMap(c)));
        }

        List<FaultStatsDto.Suggestion> suggestions = new ArrayList<>();
        byDevice.entrySet().stream()
                .filter(e -> sum(e.getValue()) >= SPARE_ADVICE_THRESHOLD)
                .sorted((a, b) -> sum(b.getValue()) - sum(a.getValue()))
                .forEach(e -> {
                    Device d = deviceMap.get(e.getKey());
                    if (d == null) {
                        return;
                    }
                    int[] c = e.getValue();
                    StringBuilder msg = new StringBuilder("该")
                            .append(typeName(d.getType())).append("近期故障").append(sum(c)).append("次");
                    String lead = "";
                    int leadCount = 0;
                    for (FaultCategory cat : CATEGORY_ORDER) {
                        if (c[cat.ordinal()] > leadCount) {
                            leadCount = c[cat.ordinal()];
                            lead = cat.getLabel();
                        }
                    }
                    if (!lead.isEmpty()) {
                        msg.append("（").append(lead).append(leadCount).append("次）");
                    }
                    msg.append("，建议检修或更换");
                    Greenhouse g = ghMap.get(d.getGreenhouseId());
                    suggestions.add(new FaultStatsDto.Suggestion(
                            d.getSn(), d.getName(), g == null ? "-" : g.getName(),
                            sum(c), categoryMap(c), lastFaultAt.get(e.getKey()), msg.toString()));
                });

        long offlineCount = alarmRepository.countByTypeAndCreatedAtBetween(AlarmType.DEVICE_OFFLINE, from, now);

        return new FaultStatsDto(now, days, total, offlineCount, typeRows, ghRows, trend, suggestions);
    }

    /** 手动触发全量重算（演示/补算用），并广播刷新事件 */
    public MaintenanceStatsService.RebuildResult runNow() {
        MaintenanceStatsService.RebuildResult result = statsService.rebuildAll();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "REBUILT");
        payload.put("devices", result.devices());
        payload.put("dailyRows", result.dailyRows());
        pushService.broadcast("maintenance", payload);
        return result;
    }

    // ==================== 工具 ====================

    /** 故障分类：先判通讯超时，再判电机过载，其余归执行失败 */
    public static FaultCategory categorize(String errorMsg) {
        if (errorMsg == null) {
            return FaultCategory.OTHER;
        }
        if (errorMsg.contains("超时")) {
            return FaultCategory.TIMEOUT;
        }
        if (errorMsg.contains("过载")) {
            return FaultCategory.OVERLOAD;
        }
        return FaultCategory.OTHER;
    }

    public static String typeName(DeviceType type) {
        return switch (type) {
            case FAN -> "风机";
            case WET_CURTAIN -> "湿帘水泵";
            case SHADE_NET -> "遮阳网电机";
            case SENSOR -> "传感器";
        };
    }

    private boolean isRunning(Device device) {
        return MaintenanceStatsService.isStartAction(device.getType(), device.getState());
    }

    private static int sum(int[] c) {
        return c[0] + c[1] + c[2];
    }

    private static Map<String, Integer> categoryMap(int[] c) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (FaultCategory cat : CATEGORY_ORDER) {
            m.put(cat.name(), c[cat.ordinal()]);
        }
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 供 DataInitializer 初始化默认规则 */
    @Transactional
    public MaintenanceRule upsertDefaultRule(DeviceType type, int intervalHours, int warnAheadHours) {
        MaintenanceRule rule = ruleRepository.findByDeviceType(type).orElseGet(() -> {
            MaintenanceRule r = new MaintenanceRule();
            r.setDeviceType(type);
            return r;
        });
        if (rule.getRunIntervalHours() == null) {
            rule.setRunIntervalHours(intervalHours);
            rule.setWarnAheadHours(warnAheadHours);
            rule.setEnabled(true);
            rule.setUpdatedAt(LocalDateTime.now());
            return ruleRepository.save(rule);
        }
        return rule;
    }

    public Optional<DeviceRuntime> runtimeOf(String sn) {
        return runtimeRepository.findByDeviceSn(sn);
    }
}
