package com.greenhouse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.enums.AlarmLevel;
import com.greenhouse.enums.AlarmType;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.service.maintenance.MaintenanceStatsService;
import com.greenhouse.ws.RealtimePushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final GreenhouseRepository greenhouseRepository;
    private final AlarmService alarmService;
    private final RealtimePushService pushService;
    private final MaintenanceStatsService maintenanceStatsService;

    public DeviceService(DeviceRepository deviceRepository,
                         GreenhouseRepository greenhouseRepository,
                         AlarmService alarmService,
                         RealtimePushService pushService,
                         MaintenanceStatsService maintenanceStatsService) {
        this.deviceRepository = deviceRepository;
        this.greenhouseRepository = greenhouseRepository;
        this.alarmService = alarmService;
        this.pushService = pushService;
        this.maintenanceStatsService = maintenanceStatsService;
    }

    public List<Device> listByGreenhouse(Long greenhouseId) {
        return deviceRepository.findByGreenhouseId(greenhouseId);
    }

    public Optional<Device> findBySn(String sn) {
        return deviceRepository.findBySn(sn);
    }

    /** 网关上线：其下所有设备标记在线 */
    @Transactional
    public void onGatewayOnline(String gatewaySn) {
        greenhouseRepository.findByGatewaySn(gatewaySn).ifPresent(gh -> {
            List<Device> devices = deviceRepository.findByGreenhouseId(gh.getId());
            LocalDateTime now = LocalDateTime.now();
            devices.forEach(d -> {
                d.setOnline(true);
                d.setLastSeenAt(now);
            });
            deviceRepository.saveAll(devices);
            devices.forEach(d -> pushService.broadcast("device", d));
        });
    }

    /** 网关掉线：设备标记离线并告警 */
    @Transactional
    public void onGatewayOffline(String gatewaySn) {
        greenhouseRepository.findByGatewaySn(gatewaySn).ifPresent(gh -> {
            List<Device> devices = deviceRepository.findByGreenhouseId(gh.getId());
            devices.forEach(d -> d.setOnline(false));
            deviceRepository.saveAll(devices);
            log.warn("网关 {} 掉线，大棚「{}」{} 台设备离线", gatewaySn, gh.getName(), devices.size());
            alarmService.raise(gh.getId(), gatewaySn, AlarmType.DEVICE_OFFLINE, AlarmLevel.WARN,
                    "网关 " + gatewaySn + " 掉线，大棚「" + gh.getName() + "」设备全部离线");
            devices.forEach(d -> pushService.broadcast("device", d));
        });
    }

    /** 心跳：刷新设备活跃时间 */
    @Transactional
    public void onHeartbeat(String gatewaySn) {
        greenhouseRepository.findByGatewaySn(gatewaySn).ifPresent(gh -> {
            List<Device> devices = deviceRepository.findByGreenhouseId(gh.getId());
            LocalDateTime now = LocalDateTime.now();
            devices.forEach(d -> d.setLastSeenAt(now));
            deviceRepository.saveAll(devices);
        });
    }

    /** 网关主动上报各设备状态 {"states":{"FAN-001":"ON",...}} */
    @Transactional
    public void onStateReport(String gatewaySn, JsonNode states) {
        if (states == null || !states.isObject()) {
            return;
        }
        states.fields().forEachRemaining(entry -> updateState(entry.getKey(), entry.getValue().asText()));
    }

    /** 更新单台设备状态（ACK 回执或状态上报时调用） */
    @Transactional
    public void updateState(String deviceSn, String state) {
        deviceRepository.findBySn(deviceSn).ifPresent(device -> {
            String oldState = device.getState();
            device.setState(state);
            device.setLastSeenAt(LocalDateTime.now());
            deviceRepository.save(device);
            pushService.broadcast("device", device);
            // 运维台账：状态边沿只刷新最近启/停时间戳，内部已隔离异常，不影响回执主链路
            maintenanceStatsService.onStateEdge(device, oldState, state);
        });
    }
}
