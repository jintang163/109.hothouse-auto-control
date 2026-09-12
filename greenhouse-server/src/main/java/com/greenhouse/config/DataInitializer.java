package com.greenhouse.config;

import com.greenhouse.entity.ControlStrategy;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.enums.DeviceType;
import com.greenhouse.enums.RunMode;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.repository.StrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** 首次启动写入演示数据：1 号番茄大棚 + 三类执行器 + 默认作物策略 */
@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    private final GreenhouseRepository greenhouseRepository;
    private final DeviceRepository deviceRepository;
    private final StrategyRepository strategyRepository;

    public DataInitializer(GreenhouseRepository greenhouseRepository,
                           DeviceRepository deviceRepository,
                           StrategyRepository strategyRepository) {
        this.greenhouseRepository = greenhouseRepository;
        this.deviceRepository = deviceRepository;
        this.strategyRepository = strategyRepository;
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

        createDevice(gh.getId(), "TH-001", "温湿度光照传感器", DeviceType.SENSOR, null);
        createDevice(gh.getId(), "FAN-001", "1#轴流风机", DeviceType.FAN, "OFF");
        createDevice(gh.getId(), "WC-001", "湿帘水泵", DeviceType.WET_CURTAIN, "CLOSED");
        createDevice(gh.getId(), "SHADE-001", "外遮阳网", DeviceType.SHADE_NET, "CLOSED");

        ControlStrategy strategy = new ControlStrategy();
        strategy.setGreenhouseId(gh.getId());
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

        log.info("========== 演示数据已初始化：{}（网关 {}）==========", gh.getName(), gh.getGatewaySn());
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
