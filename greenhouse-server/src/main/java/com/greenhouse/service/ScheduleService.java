package com.greenhouse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenhouse.dto.ControlAction;
import com.greenhouse.entity.ControlStrategy;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.enums.CommandSource;
import com.greenhouse.enums.DeviceType;
import com.greenhouse.enums.RunMode;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.repository.StrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时模式调度：每 30 秒扫描一次，SCHEDULE 模式的大棚按策略中的定时计划执行动作。
 * 计划项格式：{"time":"11:30","deviceType":"SHADE_NET","action":"OPEN"}
 */
@Slf4j
@Service
public class ScheduleService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final GreenhouseRepository greenhouseRepository;
    private final StrategyRepository strategyRepository;
    private final DeviceRepository deviceRepository;
    private final ControlService controlService;

    /** 防重复触发：itemKey -> 已执行的分钟 */
    private final Map<String, String> firedMinutes = new ConcurrentHashMap<>();

    public ScheduleService(GreenhouseRepository greenhouseRepository,
                           StrategyRepository strategyRepository,
                           DeviceRepository deviceRepository,
                           ControlService controlService) {
        this.greenhouseRepository = greenhouseRepository;
        this.strategyRepository = strategyRepository;
        this.deviceRepository = deviceRepository;
        this.controlService = controlService;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void tick() {
        String nowMinute = LocalDateTime.now().format(MINUTE_FMT);
        String nowHm = nowMinute.substring(11);

        for (Greenhouse gh : greenhouseRepository.findAll()) {
            if (gh.getMode() != RunMode.SCHEDULE) {
                continue;
            }
            Optional<ControlStrategy> strategyOpt = strategyRepository.findByGreenhouseId(gh.getId());
            if (strategyOpt.isEmpty() || !strategyOpt.get().getEnabled()
                    || strategyOpt.get().getScheduleJson() == null) {
                continue;
            }
            try {
                JsonNode items = MAPPER.readTree(strategyOpt.get().getScheduleJson());
                if (!items.isArray()) {
                    continue;
                }
                for (JsonNode item : items) {
                    String time = item.path("time").asText("");
                    String deviceType = item.path("deviceType").asText("");
                    String action = item.path("action").asText("");
                    if (!nowHm.equals(time) || time.isEmpty() || action.isEmpty()) {
                        continue;
                    }
                    String itemKey = gh.getId() + ":" + time + ":" + deviceType + ":" + action;
                    if (nowMinute.equals(firedMinutes.get(itemKey))) {
                        continue; // 本分钟已执行
                    }
                    DeviceType type = DeviceType.valueOf(deviceType);
                    Optional<Device> device = deviceRepository
                            .findByGreenhouseIdAndType(gh.getId(), type).stream().findFirst();
                    if (device.isEmpty()) {
                        continue;
                    }
                    firedMinutes.put(itemKey, nowMinute);
                    log.info("[定时计划] 大棚「{}」{} 执行 {} {}", gh.getName(), time, deviceType, action);
                    controlService.executeLinked(
                            List.of(ControlAction.of(device.get().getSn(), action)),
                            CommandSource.SCHEDULE, "system", "定时计划 " + time + " " + action);
                }
            } catch (Exception e) {
                log.warn("大棚 {} 定时计划解析失败: {}", gh.getId(), e.getMessage());
            }
        }
    }
}
