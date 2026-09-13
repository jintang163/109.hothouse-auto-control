package com.greenhouse.service.maintenance;

import com.greenhouse.entity.ControlCommand;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.DeviceRuntime;
import com.greenhouse.entity.DeviceRuntimeDaily;
import com.greenhouse.enums.CommandStatus;
import com.greenhouse.enums.DeviceType;
import com.greenhouse.repository.ControlCommandRepository;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.DeviceRuntimeDailyRepository;
import com.greenhouse.repository.DeviceRuntimeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 设备运行台账统计：以 ACKED 控制指令为唯一事实源，全量幂等重算。
 * <ul>
 *   <li>台账：ON/OPEN 与 OFF/CLOSE/STOP 配对成运行段，累计时长只含已闭合段；仍在运行的尾段
 *       只保留 lastStartedAt，由读取方实时叠加；startCount 按 ACKED 启动指令条数计。</li>
 *   <li>日结：滚动 30 天窗口，跨日段按自然日切片；窗口内先删后插，可重复执行。</li>
 *   <li>实时钩子 {@link #onStateEdge} 只刷新最近启停时间戳，不做任何累加，统计异常不影响控制主链路。</li>
 * </ul>
 * 每日 00:07 自动统计前日数据；手动「立即统计」与启动初始化共用 {@link #rebuildAll()}。
 */
@Slf4j
@Service
public class MaintenanceStatsService {

    public static final int WINDOW_DAYS = 30;

    private static final Set<DeviceType> ACTUATORS =
            Set.of(DeviceType.FAN, DeviceType.WET_CURTAIN, DeviceType.SHADE_NET);

    private final DeviceRuntimeRepository runtimeRepository;
    private final DeviceRuntimeDailyRepository dailyRepository;
    private final ControlCommandRepository commandRepository;
    private final DeviceRepository deviceRepository;

    private final ReentrantLock rebuildLock = new ReentrantLock();

    public MaintenanceStatsService(DeviceRuntimeRepository runtimeRepository,
                                   DeviceRuntimeDailyRepository dailyRepository,
                                   ControlCommandRepository commandRepository,
                                   DeviceRepository deviceRepository) {
        this.runtimeRepository = runtimeRepository;
        this.dailyRepository = dailyRepository;
        this.commandRepository = commandRepository;
        this.deviceRepository = deviceRepository;
    }

    public record RebuildResult(LocalDateTime rebuiltAt, int devices, int dailyRows, int windowDays) {}

    /** 每日凌晨统计前日运行数据并更新累计时长 */
    @Scheduled(cron = "0 7 0 * * *")
    public void dailyStats() {
        try {
            RebuildResult r = rebuildAll();
            log.info("[运维统计] 凌晨日结完成：{} 台设备，{} 行日结", r.devices(), r.dailyRows());
        } catch (Exception e) {
            log.error("[运维统计] 凌晨日结失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 全量重算台账与近 30 天日结。幂等：重复执行结果一致。
     */
    @Transactional
    public RebuildResult rebuildAll() {
        rebuildLock.lock();
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDate today = now.toLocalDate();
            LocalDate windowStart = today.minusDays(WINDOW_DAYS - 1L);

            // 1) 拉取全部 ACKED 指令，按设备分组、按回执时间升序
            Map<String, List<ControlCommand>> byDevice = new LinkedHashMap<>();
            commandRepository.findByStatus(CommandStatus.ACKED).stream()
                    .sorted(Comparator.comparing(c -> timeOf(c, now)))
                    .forEach(cmd -> byDevice.computeIfAbsent(cmd.getDeviceSn(), k -> new ArrayList<>()).add(cmd));

            // 设备元数据（SN -> Device），无指令的执行器也建零值台账行
            Map<String, Device> deviceMap = new HashMap<>();
            deviceRepository.findAll().stream()
                    .filter(d -> ACTUATORS.contains(d.getType()))
                    .forEach(d -> deviceMap.put(d.getSn(), d));

            // 2) 窗口内日结暂存：sn -> date -> [runSeconds, startCount]
            Map<String, Map<LocalDate, long[]>> dailyAcc = new LinkedHashMap<>();
            int deviceCount = 0;

            for (Map.Entry<String, Device> entry : deviceMap.entrySet()) {
                String sn = entry.getKey();
                Device device = entry.getValue();
                List<ControlCommand> cmds = byDevice.getOrDefault(sn, List.of());

                long totalSeconds = 0;
                long starts = 0;
                LocalDateTime pendingStart = null;
                LocalDateTime lastStart = null;
                LocalDateTime lastStop = null;
                Map<LocalDate, long[]> perDay = new HashMap<>();

                for (ControlCommand cmd : cmds) {
                    LocalDateTime ts = timeOf(cmd, now);
                    if (isStartAction(device.getType(), cmd.getAction())) {
                        starts++;
                        lastStart = ts;
                        if (!ts.toLocalDate().isBefore(windowStart)) {
                            perDay.computeIfAbsent(ts.toLocalDate(), k -> new long[2])[1]++;
                        }
                        if (pendingStart == null) {
                            pendingStart = ts; // 重复 ON/OPEN 视为同段，不重开
                        }
                    } else if (isStopAction(device.getType(), cmd.getAction())) {
                        if (pendingStart != null) {
                            long secs = Math.max(0, ChronoUnit.SECONDS.between(pendingStart, ts));
                            totalSeconds += secs;
                            sliceIntoDays(perDay, pendingStart, ts, windowStart, today);
                            pendingStart = null;
                        } // 孤儿停止（开机早于数据范围）忽略
                        lastStop = ts;
                    }
                }

                // 仍在运行的尾段：台账不并入累计（读取时实时叠加），日结切到 now
                if (pendingStart != null) {
                    sliceIntoDays(perDay, pendingStart, now, windowStart, today);
                }

                upsertRuntime(device, totalSeconds, starts, lastStart, lastStop, now);
                deviceCount++;
                dailyAcc.put(sn, perDay);
            }

            // 3) 当日故障数（FAILED 指令）并入日结
            Map<String, Map<LocalDate, Integer>> faults = new HashMap<>();
            commandRepository.findByStatusAndCreatedAtBetween(
                            CommandStatus.FAILED, windowStart.atStartOfDay(), today.plusDays(1).atStartOfDay())
                    .forEach(cmd -> faults
                            .computeIfAbsent(cmd.getDeviceSn(), k -> new HashMap<>())
                            .merge(cmd.getCreatedAt().toLocalDate(), 1, Integer::sum));

            // 4) 窗口内日结先删后插（全部设备 × 30 天，零值填充）
            // 显式 flush：Hibernate 同事务默认 insert 先于 delete 落库，不先清空会撞唯一约束
            dailyRepository.deleteByStatDateGreaterThanEqual(windowStart);
            dailyRepository.flush();
            List<DeviceRuntimeDaily> rows = new ArrayList<>();
            for (Map.Entry<String, Device> entry : deviceMap.entrySet()) {
                String sn = entry.getKey();
                Device device = entry.getValue();
                Map<LocalDate, long[]> perDay = dailyAcc.getOrDefault(sn, Map.of());
                Map<LocalDate, Integer> faultPerDay = faults.getOrDefault(sn, Map.of());
                for (LocalDate d = windowStart; !d.isAfter(today); d = d.plusDays(1)) {
                    long[] acc = perDay.get(d);
                    DeviceRuntimeDaily row = new DeviceRuntimeDaily();
                    row.setDeviceSn(sn);
                    row.setGreenhouseId(device.getGreenhouseId());
                    row.setDeviceType(device.getType());
                    row.setStatDate(d);
                    row.setRunSeconds(acc == null ? 0L : acc[0]);
                    row.setStartCount(acc == null ? 0 : (int) acc[1]);
                    row.setFaultCount(faultPerDay.getOrDefault(d, 0));
                    rows.add(row);
                }
            }
            dailyRepository.saveAll(rows);

            log.info("[运维统计] 重算完成：{} 台执行器，{} 行日结（窗口 {} ~ {}）",
                    deviceCount, rows.size(), windowStart, today);
            return new RebuildResult(now, deviceCount, rows.size(), WINDOW_DAYS);
        } finally {
            rebuildLock.unlock();
        }
    }

    /**
     * 设备状态边沿钩子（ACK 回执 / STATE 上报经过 DeviceService.updateState 时调用）。
     * 只刷新最近启/停时间戳，供读取端实时估算在跑尾段；任何异常都不得影响控制主链路。
     */
    public void onStateEdge(Device device, String oldState, String newState) {
        if (device == null || !ACTUATORS.contains(device.getType()) || newState == null) {
            return;
        }
        if (newState.equals(oldState)) {
            return; // 网关重复上报同一状态
        }
        try {
            boolean start = isStartAction(device.getType(), newState);
            boolean stop = isStopAction(device.getType(), newState);
            if (!start && !stop) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            DeviceRuntime runtime = runtimeRepository.findByDeviceSn(device.getSn()).orElseGet(() -> {
                DeviceRuntime r = new DeviceRuntime();
                r.setDeviceSn(device.getSn());
                r.setGreenhouseId(device.getGreenhouseId());
                r.setDeviceType(device.getType());
                return r;
            });
            if (start) {
                runtime.setLastStartedAt(now);
            } else {
                runtime.setLastStoppedAt(now);
            }
            runtime.setUpdatedAt(now);
            runtimeRepository.save(runtime);
        } catch (Exception e) {
            log.warn("[运维统计] 状态边沿记录失败 {} {} -> {}：{}",
                    device.getSn(), oldState, newState, e.getMessage());
        }
    }

    // ==================== 工具 ====================

    /** 把 [start, end) 运行段按自然日切片累加到 perDay[date][0] */
    private void sliceIntoDays(Map<LocalDate, long[]> perDay,
                               LocalDateTime start, LocalDateTime end,
                               LocalDate windowStart, LocalDate today) {
        if (end.isBefore(start)) {
            return;
        }
        LocalDate d = start.toLocalDate().isBefore(windowStart) ? windowStart : start.toLocalDate();
        for (; !d.isAfter(today); d = d.plusDays(1)) {
            LocalDateTime dayStart = d.atStartOfDay();
            LocalDateTime dayEnd = d.plusDays(1).atStartOfDay();
            LocalDateTime segStart = start.isAfter(dayStart) ? start : dayStart;
            LocalDateTime segEnd = end.isBefore(dayEnd) ? end : dayEnd;
            if (!segEnd.isAfter(segStart)) {
                if (!end.isAfter(dayEnd)) {
                    break;
                }
                continue;
            }
            long secs = ChronoUnit.SECONDS.between(segStart, segEnd);
            if (secs > 0) {
                perDay.computeIfAbsent(d, k -> new long[2])[0] += secs;
            }
            if (!end.isAfter(dayEnd)) {
                break;
            }
        }
    }

    private void upsertRuntime(Device device, long totalSeconds, long starts,
                               LocalDateTime lastStart, LocalDateTime lastStop, LocalDateTime now) {
        DeviceRuntime runtime = runtimeRepository.findByDeviceSn(device.getSn()).orElseGet(() -> {
            DeviceRuntime r = new DeviceRuntime();
            r.setDeviceSn(device.getSn());
            r.setGreenhouseId(device.getGreenhouseId());
            r.setDeviceType(device.getType());
            return r;
        });
        runtime.setGreenhouseId(device.getGreenhouseId());
        runtime.setDeviceType(device.getType());
        runtime.setTotalRunSeconds(totalSeconds);
        runtime.setStartCount(starts);
        runtime.setLastStartedAt(lastStart);
        runtime.setLastStoppedAt(lastStop);
        runtime.setSettledAt(now);
        runtime.setUpdatedAt(now);
        runtimeRepository.save(runtime);
    }

    private static LocalDateTime timeOf(ControlCommand cmd, LocalDateTime fallback) {
        LocalDateTime t = cmd.getAckedAt() != null ? cmd.getAckedAt()
                : (cmd.getSentAt() != null ? cmd.getSentAt() : cmd.getCreatedAt());
        return t != null ? t : fallback;
    }

    /** 该动作/状态是否表示设备启动运行 */
    public static boolean isStartAction(DeviceType type, String action) {
        if (action == null) {
            return false;
        }
        return switch (type) {
            case FAN -> "ON".equals(action);
            case WET_CURTAIN, SHADE_NET -> "OPEN".equals(action);
            default -> false;
        };
    }

    /** 该动作/状态是否表示设备停止运行 */
    public static boolean isStopAction(DeviceType type, String action) {
        if (action == null) {
            return false;
        }
        if ("STOP".equals(action)) {
            return true;
        }
        return switch (type) {
            case FAN -> "OFF".equals(action);
            case WET_CURTAIN, SHADE_NET -> "CLOSED".equals(action) || "CLOSE".equals(action);
            default -> false;
        };
    }
}
