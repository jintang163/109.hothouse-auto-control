package com.greenhouse.service;

import com.greenhouse.entity.Greenhouse;
import com.greenhouse.entity.OperationLog;
import com.greenhouse.entity.SensorRecord;
import com.greenhouse.enums.AlarmStatus;
import com.greenhouse.enums.CommandSource;
import com.greenhouse.enums.RunMode;
import com.greenhouse.repository.AlarmRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.repository.OperationLogRepository;
import com.greenhouse.repository.StrategyRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GreenhouseService {

    private final GreenhouseRepository greenhouseRepository;
    private final DeviceService deviceService;
    private final SensorDataService sensorDataService;
    private final StrategyRepository strategyRepository;
    private final AlarmRepository alarmRepository;
    private final OperationLogRepository logRepository;

    public GreenhouseService(GreenhouseRepository greenhouseRepository,
                             DeviceService deviceService,
                             SensorDataService sensorDataService,
                             StrategyRepository strategyRepository,
                             AlarmRepository alarmRepository,
                             OperationLogRepository logRepository) {
        this.greenhouseRepository = greenhouseRepository;
        this.deviceService = deviceService;
        this.sensorDataService = sensorDataService;
        this.strategyRepository = strategyRepository;
        this.alarmRepository = alarmRepository;
        this.logRepository = logRepository;
    }

    public List<Greenhouse> list() {
        return greenhouseRepository.findAll();
    }

    /** 监控大屏总览：大棚信息 + 实时值 + 设备 + 未处理告警数 + 策略 */
    public Map<String, Object> overview(Long id) {
        Greenhouse gh = greenhouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("大棚不存在: " + id));
        Map<String, Object> result = new HashMap<>();
        result.put("greenhouse", gh);
        result.put("devices", deviceService.listByGreenhouse(id));

        Map<String, Object> latest = new HashMap<>();
        for (Map.Entry<String, SensorRecord> entry : sensorDataService.latest(id).entrySet()) {
            Map<String, Object> v = new HashMap<>();
            v.put("value", entry.getValue().getValue());
            v.put("time", entry.getValue().getRecordedAt().toString());
            latest.put(entry.getKey(), v);
        }
        result.put("latest", latest);
        result.put("strategy", strategyRepository.findByGreenhouseId(id).orElse(null));
        result.put("openAlarms", alarmRepository.countByGreenhouseIdAndStatus(id, AlarmStatus.OPEN));
        return result;
    }

    /** 切换运行模式（手动/自动/定时），留痕 */
    public Greenhouse setMode(Long id, RunMode mode, String operator) {
        Greenhouse gh = greenhouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("大棚不存在: " + id));
        RunMode old = gh.getMode();
        gh.setMode(mode);
        greenhouseRepository.save(gh);

        OperationLog entry = new OperationLog();
        entry.setGreenhouseId(id);
        entry.setAction("切换模式");
        entry.setSource(CommandSource.MANUAL);
        entry.setOperatorName(operator == null ? "admin" : operator);
        entry.setDetail(String.format("运行模式：%s → %s", old, mode));
        logRepository.save(entry);
        return gh;
    }

    /** 更新品种 / 生育期 / 当前茬次（农事处方按品种+生育期匹配） */
    public Greenhouse updateCropProfile(Long id, String variety, String growthStage, String currentBatchNo) {
        Greenhouse gh = greenhouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("大棚不存在: " + id));
        if (variety != null) gh.setVariety(variety.trim());
        if (growthStage != null) gh.setGrowthStage(growthStage.trim());
        if (currentBatchNo != null) gh.setCurrentBatchNo(currentBatchNo.trim());
        gh = greenhouseRepository.save(gh);

        OperationLog entry = new OperationLog();
        entry.setGreenhouseId(id);
        entry.setAction("更新种植档案");
        entry.setSource(CommandSource.MANUAL);
        entry.setDetail(String.format("品种=%s，生育期=%s，茬次=%s",
                gh.getVariety(), gh.getGrowthStage(), gh.getCurrentBatchNo()));
        logRepository.save(entry);
        return gh;
    }
}
