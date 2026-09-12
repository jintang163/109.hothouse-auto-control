package com.greenhouse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.entity.SensorRecord;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.repository.SensorRecordRepository;
import com.greenhouse.ws.RealtimePushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传感数据采集：入库 → 刷新最新值缓存 → 实时推送 → 触发规则引擎判定。
 * 即「采集—判定—执行」链路的入口。
 */
@Slf4j
@Service
public class SensorDataService {

    /** 支持的指标 */
    private static final List<String> METRICS = List.of("temperature", "humidity", "light", "co2");

    private final SensorRecordRepository recordRepository;
    private final GreenhouseRepository greenhouseRepository;
    private final RuleEngineService ruleEngineService;
    private final RealtimePushService pushService;

    /** 每个大棚各指标的最新值（内存缓存，供大屏与规则引擎使用） */
    private final Map<Long, Map<String, SensorRecord>> latestCache = new ConcurrentHashMap<>();

    public SensorDataService(SensorRecordRepository recordRepository,
                             GreenhouseRepository greenhouseRepository,
                             RuleEngineService ruleEngineService,
                             RealtimePushService pushService) {
        this.recordRepository = recordRepository;
        this.greenhouseRepository = greenhouseRepository;
        this.ruleEngineService = ruleEngineService;
        this.pushService = pushService;
    }

    public void handleData(String gatewaySn, JsonNode data) {
        Optional<Greenhouse> ghOpt = greenhouseRepository.findByGatewaySn(gatewaySn);
        if (ghOpt.isEmpty()) {
            log.warn("未注册的网关 {} 上报数据，丢弃", gatewaySn);
            return;
        }
        Greenhouse gh = ghOpt.get();
        if (data == null || !data.isObject()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, SensorRecord> latest = latestCache.computeIfAbsent(gh.getId(), k -> new ConcurrentHashMap<>());
        Map<String, Double> values = new HashMap<>();

        for (String metric : METRICS) {
            JsonNode node = data.get(metric);
            if (node == null || !node.isNumber()) {
                continue;
            }
            SensorRecord record = new SensorRecord();
            record.setGreenhouseId(gh.getId());
            record.setMetric(metric);
            record.setValue(node.asDouble());
            record.setRecordedAt(now);
            recordRepository.save(record);
            latest.put(metric, record);
            values.put(metric, record.getValue());
        }
        if (values.isEmpty()) {
            return;
        }

        // 实时推送到管理端大屏
        Map<String, Object> event = new HashMap<>();
        event.put("greenhouseId", gh.getId());
        event.put("values", values);
        event.put("time", now.toString());
        pushService.broadcast("sensor", event);

        // 采集完成 → 触发规则判定（内部自行判断运行模式）
        ruleEngineService.evaluate(gh, values);
    }

    /** 某大棚全部指标最新值 */
    public Map<String, SensorRecord> latest(Long greenhouseId) {
        return latestCache.getOrDefault(greenhouseId, Map.of());
    }

    public List<SensorRecord> history(Long greenhouseId, String metric, LocalDateTime from, LocalDateTime to) {
        return recordRepository.findByGreenhouseIdAndMetricAndRecordedAtBetweenOrderByRecordedAtAsc(
                greenhouseId, metric, from, to);
    }

    public static String metricName(String metric) {
        return switch (metric) {
            case "temperature" -> "温度";
            case "humidity" -> "湿度";
            case "light" -> "光照";
            case "co2" -> "CO₂";
            default -> metric;
        };
    }
}
