package com.greenhouse.service.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.FarmTask;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.entity.Prescription;
import com.greenhouse.entity.SensorRecord;
import com.greenhouse.enums.DeviceType;
import com.greenhouse.enums.TaskExecMode;
import com.greenhouse.enums.TaskStatus;
import com.greenhouse.enums.TaskTriggerType;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.service.SensorDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 农事任务生成器：定时（默认 60s）扫描所有大棚，取该大棚「品种 + 生育期」对应的
 * 已发布处方，做两件事：
 * <ol>
 *   <li><b>偏差触发</b>：处方环境目标区间 vs 传感器最新值，超出「区间±死区」生成环境调控任务；
 *       同方向开放任务去重，完成后 30 分钟冷却，避免抖动重复生成。</li>
 *   <li><b>周期触发</b>：处方中的水肥/农事操作按 intervalDays（可配当日触发时刻 HH:mm）
 *       到期生成设备联动或人工任务。</li>
 * </ol>
 */
@Slf4j
@Service
public class TaskGenerateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter HM_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** 同方向偏差任务完成后的冷却分钟数（冷却期内偏差仍存在也不重复生成） */
    private static final int ENV_REGEN_COOLDOWN_MIN = 30;

    private final GreenhouseRepository greenhouseRepository;
    private final DeviceRepository deviceRepository;
    private final PrescriptionService prescriptionService;
    private final FarmTaskService farmTaskService;
    private final SensorDataService sensorDataService;

    /** 周期任务当日已触发标记：dedupeKey -> yyyy-MM-dd（与 ScheduleService 同风格防重） */
    private final Map<String, String> firedToday = new ConcurrentHashMap<>();

    @Value("${greenhouse.farm.scan-enabled:true}")
    private boolean scanEnabled;

    public TaskGenerateService(GreenhouseRepository greenhouseRepository,
                               DeviceRepository deviceRepository,
                               PrescriptionService prescriptionService,
                               FarmTaskService farmTaskService,
                               SensorDataService sensorDataService) {
        this.greenhouseRepository = greenhouseRepository;
        this.deviceRepository = deviceRepository;
        this.prescriptionService = prescriptionService;
        this.farmTaskService = farmTaskService;
        this.sensorDataService = sensorDataService;
    }

    @Scheduled(fixedDelayString = "${greenhouse.farm.scan-ms:60000}", initialDelay = 20_000)
    public void scan() {
        if (!scanEnabled) {
            return;
        }
        for (Greenhouse gh : greenhouseRepository.findAll()) {
            try {
                scanOne(gh);
            } catch (Exception e) {
                log.warn("[农事任务生成] 大棚「{}」扫描失败: {}", gh.getName(), e.getMessage());
            }
        }
    }

    private void scanOne(Greenhouse gh) {
        Prescription rx = prescriptionService.active(gh.getVariety(), gh.getGrowthStage());
        if (rx == null) {
            return;
        }
        scanDeviation(gh, rx);
        scanPeriodic(gh, rx);
    }

    // ==================== 偏差触发 ====================

    private void scanDeviation(Greenhouse gh, Prescription rx) {
        if (rx.getEnvTargetsJson() == null || rx.getEnvTargetsJson().isBlank()) {
            return;
        }
        Map<String, SensorRecord> latest = sensorDataService.latest(gh.getId());
        if (latest.isEmpty()) {
            return;
        }
        List<EnvTarget> targets;
        try {
            targets = MAPPER.readValue(rx.getEnvTargetsJson(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, EnvTarget.class));
        } catch (Exception e) {
            log.warn("[农事任务生成] 大棚 {} 处方环境目标 JSON 解析失败: {}", gh.getId(), e.getMessage());
            return;
        }

        List<Device> devices = deviceRepository.findByGreenhouseId(gh.getId());
        for (EnvTarget target : targets) {
            SensorRecord rec = latest.get(target.metric());
            Double value = rec == null ? null : rec.getValue();
            EnvTarget.Deviation dev = target.check(value);
            if (dev == null) {
                continue;
            }
            String dir = dev.direction() == EnvTarget.Direction.HIGH ? "HIGH" : "LOW";
            String dedupeKey = "gh:" + gh.getId() + ":env:" + target.metric() + ":" + dir;
            if (inDedupeWindow(dedupeKey)) {
                continue;
            }
            FarmTask task = buildDeviationTask(gh, rx, dev, rec, dedupeKey, devices);
            farmTaskService.generateIfAbsent(task);
            log.info("[农事任务生成] 大棚「{}」偏差触发：{}", gh.getName(), dev.describe());
        }
    }

    /** 开放任务存在，或最近一个同键任务完成未过冷却期 → 跳过 */
    private boolean inDedupeWindow(String dedupeKey) {
        FarmTask latestTask = farmTaskService.latestByDedupeKey(dedupeKey);
        if (latestTask == null) {
            return false;
        }
        return switch (latestTask.getStatus()) {
            case PENDING, EXECUTING -> true;
            case DONE, FAILED, CANCELLED -> {
                LocalDateTime ref = latestTask.getFinishedAt() != null
                        ? latestTask.getFinishedAt() : latestTask.getGeneratedAt();
                yield ref.plusMinutes(ENV_REGEN_COOLDOWN_MIN).isAfter(LocalDateTime.now());
            }
        };
    }

    private FarmTask buildDeviationTask(Greenhouse gh, Prescription rx, EnvTarget.Deviation dev,
                                        SensorRecord rec, String dedupeKey, List<Device> devices) {
        FarmTask t = new FarmTask();
        t.setGreenhouseId(gh.getId());
        t.setPrescriptionId(rx.getId());
        t.setPrescriptionVersion(rx.getVersion());
        t.setBatchNo(gh.getCurrentBatchNo());
        t.setType(FarmTaskService.envTaskType(dev.metric()));
        t.setTriggerType(TaskTriggerType.DEVIATION);
        t.setDedupeKey(dedupeKey);
        t.setTitle(dev.describe());

        List<TaskDeviceAction> actions = suggestDeviceActions(dev, devices);
        if (actions.isEmpty()) {
            t.setExecMode(TaskExecMode.MANUAL);
            t.setInstruction("无联动设备或不宜自动调控，请人工处置：" + manualAdvice(dev));
        } else {
            t.setExecMode(TaskExecMode.DEVICE);
            t.setDeviceActionsJson(writeJson(actions));
            t.setInstruction("一键联动设备处置；如设备异常可转人工。" + manualAdvice(dev));
        }

        String snapshot = MAPPER.createObjectNode()
                .put("metric", dev.metric())
                .put("direction", dev.direction().name())
                .put("value", dev.value())
                .put("bound", dev.bound())
                .put("low", dev.target().low() == null ? Double.NaN : dev.target().low())
                .put("high", dev.target().high() == null ? Double.NaN : dev.target().high())
                .put("time", rec.getRecordedAt().toString())
                .toString();
        t.setTriggerSnapshot(snapshot);
        return t;
    }

    /**
     * 偏差 → 联动动作建议（动作顺序遵守安全互锁：先湿帘后风机）。
     * 没有合适执行器（增温/补光/CO₂/降湿）时返回空，转人工任务。
     */
    private List<TaskDeviceAction> suggestDeviceActions(EnvTarget.Deviation dev, List<Device> devices) {
        boolean high = dev.direction() == EnvTarget.Direction.HIGH;
        return switch (dev.metric()) {
            case "temperature" -> high
                    ? chain(devices, DeviceType.WET_CURTAIN, "OPEN", null, null,
                            DeviceType.FAN, "ON", null, null)
                    : List.of(); // 增温无设备 → 人工
            case "humidity" -> !high
                    // 低湿加湿：只开湿帘（湿润后由人工/规则引擎关停），给出保持时长则自动关
                    ? chain(devices, DeviceType.WET_CURTAIN, "OPEN", 10, "CLOSE")
                    : List.of(); // 高湿通风受互锁约束，转人工提示
            case "light" -> high
                    ? chain(devices, DeviceType.SHADE_NET, "OPEN", null, null)
                    : chain(devices, DeviceType.SHADE_NET, "CLOSE", null, null) // 光照不足：收拢遮阳网
                    ;
            default -> List.of(); // co2 / 其他 → 人工
        };
    }

    /** 组装有序联动动作，缺少设备的环节跳过 */
    private List<TaskDeviceAction> chain(List<Device> devices, Object... spec) {
        List<TaskDeviceAction> result = new ArrayList<>();
        for (int i = 0; i < spec.length; i += 3) {
            DeviceType type = (DeviceType) spec[i];
            String action = (String) spec[i + 1];
            Integer hold = (Integer) spec[i + 2];
            Device d = devices.stream().filter(x -> x.getType() == type).findFirst().orElse(null);
            if (d == null) {
                continue;
            }
            String then = hold != null ? reverse(action) : null;
            result.add(new TaskDeviceAction(d.getSn(), action, null, hold, then));
        }
        return result;
    }

    private String reverse(String action) {
        return switch (action) {
            case "ON" -> "OFF";
            case "OPEN" -> "CLOSE";
            default -> null;
        };
    }

    private String manualAdvice(EnvTarget.Deviation dev) {
        String high = dev.direction() == EnvTarget.Direction.HIGH ? "过高" : "过低";
        return switch (dev.metric()) {
            case "temperature" -> high.contains("过高")
                    ? "建议启动湿帘风机降温、加强通风或适当遮阳。"
                    : "建议关闭通风、覆盖保温被/二层膜，必要时增温。";
            case "humidity" -> high.contains("过高")
                    ? "建议通风排湿（先开湿帘再启风机，注意互锁），避免叶面结露。"
                    : "建议湿帘加湿、喷雾或地面洒水。";
            case "light" -> high.contains("过高")
                    ? "建议展开遮阳网，防止强光灼伤。"
                    : "建议收拢遮阳网、清洁棚膜，必要时补光。";
            case "co2" -> high.contains("过高")
                    ? "建议加强通风换气。"
                    : "建议增施 CO₂ 气肥，通风前停止。";
            default -> "请按田间实际情况处置。";
        };
    }

    // ==================== 周期触发 ====================

    private void scanPeriodic(Greenhouse gh, Prescription rx) {
        if (rx.getOperationsJson() == null || rx.getOperationsJson().isBlank()) {
            return;
        }
        JsonNode ops;
        try {
            ops = MAPPER.readTree(rx.getOperationsJson());
        } catch (Exception e) {
            log.warn("[农事任务生成] 大棚 {} 处方操作 JSON 解析失败: {}", gh.getId(), e.getMessage());
            return;
        }
        if (!ops.isArray()) {
            return;
        }
        String today = LocalDateTime.now().toLocalDate().toString();
        String nowHm = LocalDateTime.now().format(HM_FMT);

        int index = 0;
        for (JsonNode op : ops) {
            index++;
            int intervalDays = op.path("intervalDays").asInt(0);
            if (intervalDays <= 0) {
                continue;
            }
            String opKey = op.path("key").asText(op.path("type").asText("OTHER") + ":"
                    + op.path("name").asText("op" + index));
            String dedupeKey = "gh:" + gh.getId() + ":op:" + opKey + ":" + today;

            // 指定了当日触发时刻：仅在该分钟触发（扫描间隔 60s）
            String atTime = op.path("time").asText("");
            if (!atTime.isEmpty() && !atTime.equals(nowHm)) {
                continue;
            }
            if (today.equals(firedToday.get(dedupeKey))) {
                continue;
            }
            // 间隔是否到期：取「周期键」（不含日期）的最近一次任务
            String periodKey = "gh:" + gh.getId() + ":op:" + opKey;
            FarmTask last = farmTaskService.latestByDedupeKey(periodKey);
            // 已有未闭环任务（待执行/联动中）→ 不重复生成
            if (last != null && (last.getStatus() == TaskStatus.PENDING
                    || last.getStatus() == TaskStatus.EXECUTING)) {
                continue;
            }
            if (last != null && last.getGeneratedAt().toLocalDate().plusDays(intervalDays)
                    .isAfter(LocalDateTime.now().toLocalDate())) {
                continue;
            }
            firedToday.put(dedupeKey, today);
            farmTaskService.generateIfAbsent(buildPeriodicTask(gh, rx, op, periodKey));
            log.info("[农事任务生成] 大棚「{}」周期触发：{}", gh.getName(), op.path("name").asText());
        }
    }

    private FarmTask buildPeriodicTask(Greenhouse gh, Prescription rx, JsonNode op, String periodKey) {
        FarmTask t = new FarmTask();
        t.setGreenhouseId(gh.getId());
        t.setPrescriptionId(rx.getId());
        t.setPrescriptionVersion(rx.getVersion());
        t.setBatchNo(gh.getCurrentBatchNo());
        String typeStr = op.path("type").asText("OTHER");
        try {
            t.setType(com.greenhouse.enums.TaskType.valueOf(typeStr));
        } catch (Exception e) {
            t.setType(com.greenhouse.enums.TaskType.OTHER);
        }
        t.setTitle(op.path("name").asText("农事操作"));
        t.setTriggerType(TaskTriggerType.PERIODIC);
        t.setDedupeKey(periodKey);
        t.setInstruction(op.path("instruction").asText(""));

        String execMode = op.path("execMode").asText("MANUAL");
        String doseUnit = op.path("doseUnit").asText("");
        if ("DEVICE".equals(execMode)) {
            String deviceType = op.path("deviceType").asText("");
            String action = op.path("action").asText("");
            List<TaskDeviceAction> actions = new ArrayList<>();
            if (!deviceType.isEmpty() && !action.isEmpty()) {
                try {
                    DeviceType dt = DeviceType.valueOf(deviceType);
                    Device d = deviceRepository.findByGreenhouseIdAndType(gh.getId(), dt).stream()
                            .findFirst().orElse(null);
                    if (d != null) {
                        Integer hold = op.has("holdMinutes") && op.get("holdMinutes").asInt() > 0
                                ? op.get("holdMinutes").asInt() : null;
                        String then = op.path("thenAction").asText(hold != null ? reverse(action) : "");
                        actions.add(new TaskDeviceAction(d.getSn(), action,
                                op.path("params").asText(null), hold,
                                then.isEmpty() ? null : then));
                    }
                } catch (IllegalArgumentException ignore) {
                    // 未知设备类型 → 落为人工任务
                }
            }
            if (actions.isEmpty()) {
                t.setExecMode(TaskExecMode.MANUAL);
                t.setInstruction((t.getInstruction() == null ? "" : t.getInstruction())
                        + "（未找到联动设备，请人工执行）");
            } else {
                t.setExecMode(TaskExecMode.DEVICE);
                t.setDeviceActionsJson(writeJson(actions));
            }
        } else {
            t.setExecMode(TaskExecMode.MANUAL);
        }
        return t;
    }

    private String writeJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
