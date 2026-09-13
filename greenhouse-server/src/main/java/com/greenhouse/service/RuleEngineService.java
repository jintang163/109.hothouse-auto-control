package com.greenhouse.service;

import com.greenhouse.dto.ControlAction;
import com.greenhouse.entity.ControlStrategy;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.enums.AlarmLevel;
import com.greenhouse.enums.AlarmType;
import com.greenhouse.enums.CommandSource;
import com.greenhouse.enums.DeviceType;
import com.greenhouse.enums.RunMode;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.StrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则引擎：采集—判定—执行链路的「判定」环节。
 *
 * <p>核心机制：
 * <ul>
 *   <li>阈值 + 回差（迟滞）：温度 ≥ 上限开降温，回落到恢复点才停，避免边界抖动</li>
 *   <li>防抖：条件需持续 debounceSec 秒才触发</li>
 *   <li>冷却：同一设备两次动作的最小间隔，防频繁启停</li>
 *   <li>三设备联动：降温=湿帘+风机，加湿=湿帘，遮阳=遮阳网；湿帘可被降温/加湿复用</li>
 *   <li>安全互锁：复用 {@link ControlService#checkInterlock} 同一套规则（湿帘未开禁强通风/
 *       风机运行禁关湿帘），计划阶段按链式推演状态预判——冷却窗口错位时（湿帘仍在冷却、
 *       风机冷却已到）不会绕过互锁单独下发风机启动</li>
 * </ul>
 */
@Slf4j
@Service
public class RuleEngineService {

    private final StrategyRepository strategyRepository;
    private final DeviceRepository deviceRepository;
    private final ControlService controlService;
    private final AlarmService alarmService;

    /** 功能状态机：key=大棚:功能(cooling/humidify/shade) */
    private final Map<String, Boolean> funcStates = new ConcurrentHashMap<>();
    /** 条件开始时间（防抖用） */
    private final Map<String, Instant> condSince = new ConcurrentHashMap<>();
    /** 设备动作冷却计时：key=大棚:设备类型 */
    private final Map<String, Instant> lastActionAt = new ConcurrentHashMap<>();

    public RuleEngineService(StrategyRepository strategyRepository,
                             DeviceRepository deviceRepository,
                             ControlService controlService,
                             AlarmService alarmService) {
        this.strategyRepository = strategyRepository;
        this.deviceRepository = deviceRepository;
        this.controlService = controlService;
        this.alarmService = alarmService;
    }

    public void evaluate(Greenhouse gh, Map<String, Double> values) {
        Optional<ControlStrategy> strategyOpt = strategyRepository.findByGreenhouseId(gh.getId());
        if (strategyOpt.isEmpty() || !strategyOpt.get().getEnabled()) {
            return;
        }
        ControlStrategy s = strategyOpt.get();

        // 越限告警：任何模式下都生效
        Double temp = values.get("temperature");
        if (temp != null && temp >= s.getTempCritical()) {
            alarmService.raise(gh.getId(), null, AlarmType.THRESHOLD, AlarmLevel.CRITICAL,
                    String.format("大棚「%s」温度越限：%.1f℃ ≥ 告警线 %.1f℃", gh.getName(), temp, s.getTempCritical()));
        }

        // 仅自动模式参与联动判定
        if (gh.getMode() != RunMode.AUTO) {
            return;
        }

        Double humi = values.get("humidity");
        Double light = values.get("light");
        if (temp == null) {
            return;
        }

        // ---- 功能级状态机（阈值 + 回差 + 防抖）----
        boolean cooling = updateFunc(gh.getId(), "cooling",
                temp >= s.getTempHigh(), temp <= s.getTempRecover(), s.getDebounceSec());
        boolean humidify = humi != null && updateFunc(gh.getId(), "humidify",
                humi <= s.getHumiLow(), humi >= s.getHumiRecover(), s.getDebounceSec());
        boolean shade = light != null && updateFunc(gh.getId(), "shade",
                light >= s.getLightHigh(), light <= s.getLightHigh() * 0.7, s.getDebounceSec());

        // ---- 目标态 → 与设备实际状态比对，生成联动动作 ----
        List<Device> devices = deviceRepository.findByGreenhouseId(gh.getId());
        Device fan = find(devices, DeviceType.FAN);
        Device curtain = find(devices, DeviceType.WET_CURTAIN);
        Device shadeNet = find(devices, DeviceType.SHADE_NET);

        List<ControlAction> actions = new ArrayList<>();
        boolean fanOn = fan != null && "ON".equals(fan.getState());
        boolean curtainOpen = curtain != null && "OPEN".equals(curtain.getState());
        boolean shadeOpen = shadeNet != null && "OPEN".equals(shadeNet.getState());

        boolean curtainDesired = cooling || humidify;

        // 安全互锁复用 ControlService 同一套校验：以当前状态为起点，每计划一个动作即
        // 推进推演状态，后续动作基于「链式执行后」的状态判定（如湿帘 OPEN 已排入本链，
        // 风机 ON 才允许跟随；湿帘因冷却未排入时，风机不得单独启动）
        Map<DeviceType, String> plannedStates = new EnumMap<>(DeviceType.class);
        devices.forEach(d -> plannedStates.putIfAbsent(d.getType(), d.getState()));

        // 启动顺序：先湿帘后风机（互锁）；停止顺序：先风机后湿帘
        if (curtain != null && curtainDesired != curtainOpen && cooldownReady(gh.getId(), DeviceType.WET_CURTAIN, s.getCooldownSec())) {
            planAction(actions, plannedStates, curtain, curtainDesired ? "OPEN" : "CLOSE", gh.getName());
        }
        if (fan != null && cooling != fanOn && cooldownReady(gh.getId(), DeviceType.FAN, s.getCooldownSec())) {
            planAction(actions, plannedStates, fan, cooling ? "ON" : "OFF", gh.getName());
        }
        if (shadeNet != null && shade != shadeOpen && cooldownReady(gh.getId(), DeviceType.SHADE_NET, s.getCooldownSec())) {
            planAction(actions, plannedStates, shadeNet, shade ? "OPEN" : "CLOSE", gh.getName());
        }

        if (actions.isEmpty()) {
            return;
        }

        // 记录冷却时间
        actions.forEach(a -> deviceRepository.findBySn(a.deviceSn()).ifPresent(d ->
                lastActionAt.put(gh.getId() + ":" + d.getType(), Instant.now())));

        String reason = String.format("温度%.1f℃ 湿度%s 光照%s → 联动：降温%s 加湿%s 遮阳%s",
                temp,
                humi == null ? "-" : String.format("%.1f%%", humi),
                light == null ? "-" : String.format("%.0flux", light),
                onOff(cooling), onOff(humidify), onOff(shade));
        log.info("[规则引擎] 大棚「{}」{}", gh.getName(), reason);
        controlService.executeLinked(actions, CommandSource.AUTO_RULE, "system", reason);
    }

    /** 功能状态机：进入/退出条件分别防抖，带迟滞 */
    private boolean updateFunc(Long ghId, String func, boolean enterCond, boolean exitCond, int debounceSec) {
        String key = ghId + ":" + func;
        boolean active = funcStates.getOrDefault(key, false);
        if (!active) {
            if (held(key + ":enter", enterCond, debounceSec)) {
                funcStates.put(key, true);
                return true;
            }
        } else {
            if (held(key + ":exit", exitCond, debounceSec)) {
                funcStates.put(key, false);
                return false;
            }
        }
        return funcStates.getOrDefault(key, false);
    }

    /** 条件持续 debounceSec 秒才返回 true，条件消失则重新计时 */
    private boolean held(String key, boolean cond, int debounceSec) {
        if (!cond) {
            condSince.remove(key);
            return false;
        }
        Instant since = condSince.computeIfAbsent(key, k -> Instant.now());
        if (Duration.between(since, Instant.now()).getSeconds() >= debounceSec) {
            condSince.remove(key);
            return true;
        }
        return false;
    }

    /**
     * 计划一个联动动作：先过安全互锁（与手动控制同一套规则，基于链式推演状态），
     * 通过后追加动作并推进推演状态；被互锁拦截则本轮暂缓，下轮评估重试。
     */
    private void planAction(List<ControlAction> actions, Map<DeviceType, String> plannedStates,
                            Device device, String action, String ghName) {
        String blocked = controlService.checkInterlock(device.getType(), action, plannedStates);
        if (blocked != null) {
            log.info("[规则引擎] 大棚「{}」{} {} 暂缓：{}", ghName, device.getName(), action, blocked);
            return;
        }
        actions.add(ControlAction.of(device.getSn(), action));
        plannedStates.put(device.getType(), deriveState(action));
    }

    /** 动作 → 执行后的设备状态（用于链式推演） */
    private static String deriveState(String action) {
        return switch (action) {
            case "ON" -> "ON";
            case "OFF" -> "OFF";
            case "OPEN" -> "OPEN";
            case "CLOSE" -> "CLOSED";
            default -> action;
        };
    }

    private boolean cooldownReady(Long ghId, DeviceType type, int cooldownSec) {
        Instant last = lastActionAt.get(ghId + ":" + type);
        return last == null || Duration.between(last, Instant.now()).getSeconds() >= cooldownSec;
    }

    private Device find(List<Device> devices, DeviceType type) {
        return devices.stream().filter(d -> d.getType() == type).findFirst().orElse(null);
    }

    private String onOff(boolean b) {
        return b ? "启" : "停";
    }
}
