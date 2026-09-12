package com.greenhouse.service;

import com.greenhouse.entity.Alarm;
import com.greenhouse.enums.AlarmLevel;
import com.greenhouse.enums.AlarmStatus;
import com.greenhouse.enums.AlarmType;
import com.greenhouse.repository.AlarmRepository;
import com.greenhouse.ws.RealtimePushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final RealtimePushService pushService;

    public AlarmService(AlarmRepository alarmRepository, RealtimePushService pushService) {
        this.alarmRepository = alarmRepository;
        this.pushService = pushService;
    }

    /** 产生告警（同类未处理告警去重，避免刷屏） */
    public Alarm raise(Long greenhouseId, String deviceSn, AlarmType type, AlarmLevel level, String message) {
        boolean dup = alarmRepository.existsByGreenhouseIdAndDeviceSnAndTypeAndStatusAndMessage(
                greenhouseId, deviceSn, type, AlarmStatus.OPEN, message);
        if (dup) {
            return null;
        }
        Alarm alarm = new Alarm();
        alarm.setGreenhouseId(greenhouseId);
        alarm.setDeviceSn(deviceSn);
        alarm.setType(type);
        alarm.setLevel(level);
        alarm.setMessage(message);
        alarm = alarmRepository.save(alarm);
        log.warn("[告警][{}] 大棚{} {} - {}", level, greenhouseId, type, message);
        pushService.broadcast("alarm", alarm);
        return alarm;
    }

    public Alarm handle(Long id, String operator) {
        Alarm alarm = alarmRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("告警不存在: " + id));
        alarm.setStatus(AlarmStatus.HANDLED);
        alarm.setHandledAt(LocalDateTime.now());
        alarm.setHandledBy(operator == null || operator.isBlank() ? "admin" : operator);
        return alarmRepository.save(alarm);
    }

    public List<Alarm> list(AlarmStatus status, Long greenhouseId) {
        if (status != null) {
            return alarmRepository.findByStatusOrderByCreatedAtDesc(status);
        }
        if (greenhouseId != null) {
            return alarmRepository.findTop200ByGreenhouseIdOrderByCreatedAtDesc(greenhouseId);
        }
        return alarmRepository.findTop200ByOrderByCreatedAtDesc();
    }

    public long countOpen() {
        return alarmRepository.countByStatus(AlarmStatus.OPEN);
    }
}
