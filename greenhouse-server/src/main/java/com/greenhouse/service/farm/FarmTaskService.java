package com.greenhouse.service.farm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenhouse.dto.ControlAction;
import com.greenhouse.entity.FarmTask;
import com.greenhouse.entity.OperationLog;
import com.greenhouse.enums.CommandSource;
import com.greenhouse.enums.TaskExecMode;
import com.greenhouse.enums.TaskStatus;
import com.greenhouse.enums.TaskTriggerType;
import com.greenhouse.enums.TaskType;
import com.greenhouse.repository.FarmTaskRepository;
import com.greenhouse.repository.OperationLogRepository;
import com.greenhouse.service.ControlService;
import com.greenhouse.ws.RealtimePushService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 农事任务服务：任务生成落库、一键设备联动（经 Netty 下发）、人工反馈闭环。
 *
 * <p>设备联动复用 {@link ControlService} 联动链（互锁/重试/离线补发同一套机制），
 * 通过 {@code ChainCallback} 回执全部成功后自动闭环；失败转 FAILED，可改人工兜底。
 */
@Slf4j
@Service
public class FarmTaskService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FarmTaskRepository taskRepository;
    private final OperationLogRepository logRepository;
    private final ControlService controlService;
    private final RealtimePushService pushService;

    /** 动作保持时长到期后的反向动作调度（如滴灌 10 分钟后自动关阀） */
    private final ScheduledExecutorService holdScheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "farm-task-hold");
        t.setDaemon(true);
        return t;
    });

    public FarmTaskService(FarmTaskRepository taskRepository,
                           OperationLogRepository logRepository,
                           ControlService controlService,
                           RealtimePushService pushService) {
        this.taskRepository = taskRepository;
        this.logRepository = logRepository;
        this.controlService = controlService;
        this.pushService = pushService;
    }

    // ==================== 查询 ====================

    public List<FarmTask> list(Long greenhouseId) {
        return greenhouseId != null
                ? taskRepository.findTop200ByGreenhouseIdOrderByGeneratedAtDesc(greenhouseId)
                : taskRepository.findTop200ByOrderByGeneratedAtDesc();
    }

    public FarmTask get(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    // ==================== 生成 / 创建 ====================

    /** 调度器/病虫害等系统入口：已存在同幂等键的开放任务时不重复生成 */
    @Transactional
    public FarmTask generateIfAbsent(FarmTask task) {
        if (task.getDedupeKey() != null) {
            if (taskRepository.findFirstByDedupeKeyAndStatusIn(
                    task.getDedupeKey(), List.of(TaskStatus.PENDING, TaskStatus.EXECUTING)).isPresent()) {
                return null;
            }
        }
        task.setId(null);
        if (task.getStatus() == null) {
            task.setStatus(TaskStatus.PENDING);
        }
        FarmTask saved = taskRepository.save(task);
        push("created", saved);
        writeLog(saved, "农事任务生成",
                String.format("%s（%s）", saved.getTitle(), triggerLabel(saved.getTriggerType())));
        return saved;
    }

    /** 人工创建任务 */
    @Transactional
    public FarmTask createManual(FarmTask task, String operator) {
        task.setId(null);
        task.setTriggerType(TaskTriggerType.MANUAL);
        task.setStatus(TaskStatus.PENDING);
        task.setOperator(operator);
        if (task.getAssignee() == null) {
            task.setAssignee(operator);
        }
        FarmTask saved = taskRepository.save(task);
        push("created", saved);
        writeLog(saved, "农事任务创建", "人工创建：" + saved.getTitle());
        return saved;
    }

    // ==================== 执行：一键设备联动 ====================

    /** 一键调用 Netty 联动设备；返回受理提示 */
    @Transactional
    public FarmTask runDevices(Long taskId, String operator) {
        FarmTask task = get(taskId);
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.FAILED) {
            throw new IllegalStateException("当前状态不允许设备联动：" + task.getStatus());
        }
        List<TaskDeviceAction> planned = parseActions(task.getDeviceActionsJson());
        if (planned.isEmpty()) {
            throw new IllegalStateException("该任务未配置设备联动动作，请走人工执行");
        }
        task.setStatus(TaskStatus.EXECUTING);
        task.setStartedAt(LocalDateTime.now());
        if (operator != null) {
            task.setOperator(operator);
        }
        task = taskRepository.save(task);
        push("updated", task);

        String reason = "农事任务#" + task.getId() + " " + task.getTitle();
        String operatorName = operator == null ? "system" : operator;
        List<ControlAction> actions = planned.stream()
                .map(a -> new ControlAction(a.deviceSn(), a.action(), a.params()))
                .toList();
        Long id = task.getId();
        String chainId = controlService.executeLinked(actions, CommandSource.FARM_TASK,
                operatorName, reason, (success, detail) -> {
                    handleChainFinished(id, success, detail);
                    if (success) {
                        scheduleHoldThenActions(id, planned, operatorName);
                    }
                });
        task.setChainId(chainId);
        return taskRepository.save(task);
    }

    /** 主动作链全部成功后，按各动作的 holdMinutes 调度反向动作（如开阀灌溉 10 分钟后自动关阀） */
    private void scheduleHoldThenActions(Long taskId, List<TaskDeviceAction> planned, String operator) {
        for (TaskDeviceAction a : planned) {
            if (a.holdMinutes() == null || a.holdMinutes() <= 0 || a.thenAction() == null) {
                continue;
            }
            int delayMin = Math.min(a.holdMinutes(), 600);
            holdScheduler.schedule(() -> {
                FarmTask latest = taskRepository.findById(taskId).orElse(null);
                if (latest == null || latest.getStatus() == TaskStatus.CANCELLED) {
                    return;
                }
                log.info("[农事任务#{}] 保持 {}min 到期，自动下发反向动作 {} {}",
                        taskId, delayMin, a.deviceSn(), a.thenAction());
                controlService.executeLinked(
                        List.of(new ControlAction(a.deviceSn(), a.thenAction(), null)),
                        CommandSource.FARM_TASK, operator,
                        "农事任务#" + taskId + " 保持" + delayMin + "分钟后自动" + a.thenAction());
            }, delayMin, TimeUnit.MINUTES);
        }
    }

    /** 联动链终结回调（设备线程上下文，独立事务落库） */
    private void handleChainFinished(Long taskId, boolean success, String detail) {
        try {
            finishChain(taskId, success, detail);
        } catch (Exception e) {
            log.error("[农事任务#{}] 联动回调处理失败", taskId, e);
        }
    }

    @Transactional
    public void finishChain(Long taskId, boolean success, String detail) {
        FarmTask task = get(taskId);
        if (task.getStatus() != TaskStatus.EXECUTING) {
            return; // 已被人工取消/改人工处理
        }
        task.setFinishedAt(LocalDateTime.now());
        if (success) {
            task.setStatus(TaskStatus.DONE);
            task.setFeedback((task.getFeedback() == null ? "" : task.getFeedback() + "；")
                    + "设备联动完成：" + detail);
            writeLog(task, "农事任务完成", "设备联动成功（链 " + task.getChainId() + "）");
        } else {
            // 联动失败：转人工兜底，现场人员仍可线下处置后回填反馈
            task.setStatus(TaskStatus.FAILED);
            task.setFeedback((task.getFeedback() == null ? "" : task.getFeedback() + "；")
                    + "设备联动失败：" + detail + "，可改人工执行");
            writeLog(task, "农事任务联动失败", detail);
        }
        taskRepository.save(task);
        push("updated", task);
    }

    // ==================== 执行：人工回填反馈 ====================

    /** 人工完成：回填实际用量、耗时、反馈说明，闭环 */
    @Transactional
    public FarmTask completeManually(Long taskId, String operator, Double materialUsed, String materialUnit,
                                     Integer durationMinutes, String feedback) {
        FarmTask task = get(taskId);
        if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IllegalStateException("任务已终结（" + task.getStatus() + "），不可重复反馈");
        }
        boolean deviceWasRunning = task.getStatus() == TaskStatus.EXECUTING && task.getChainId() != null;
        task.setStatus(TaskStatus.DONE);
        task.setExecMode(deviceWasRunning
                ? TaskExecMode.DEVICE_THEN_MANUAL : task.getExecMode());
        if (task.getStartedAt() == null) {
            task.setStartedAt(LocalDateTime.now());
        }
        task.setFinishedAt(LocalDateTime.now());
        task.setOperator(operator);
        task.setMaterialUsed(materialUsed);
        task.setMaterialUnit(materialUnit);
        task.setDurationMinutes(durationMinutes);
        task.setFeedback(feedback);
        task = taskRepository.save(task);
        push("updated", task);
        writeLog(task, "农事任务完成", String.format("人工执行：用量=%s%s，耗时=%s分钟。%s",
                materialUsed == null ? "-" : materialUsed,
                materialUnit == null ? "" : materialUnit,
                durationMinutes == null ? "-" : durationMinutes,
                feedback == null ? "" : feedback));
        return task;
    }

    @Transactional
    public FarmTask cancel(Long taskId, String operator) {
        FarmTask task = get(taskId);
        if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IllegalStateException("任务已终结，不可取消");
        }
        task.setStatus(TaskStatus.CANCELLED);
        task.setFinishedAt(LocalDateTime.now());
        task.setOperator(operator);
        task = taskRepository.save(task);
        push("updated", task);
        writeLog(task, "农事任务取消", operator == null ? "" : "操作人：" + operator);
        return task;
    }

    // ==================== 工具 ====================

    private List<TaskDeviceAction> parseActions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<TaskDeviceAction>>() {});
        } catch (Exception e) {
            log.warn("任务设备动作 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void push(String action, FarmTask task) {
        pushService.broadcast("farmtask", Map.of("action", action, "task", task));
    }

    private void writeLog(FarmTask task, String action, String detail) {
        OperationLog entry = new OperationLog();
        entry.setGreenhouseId(task.getGreenhouseId());
        entry.setAction(action);
        entry.setSource(CommandSource.MANUAL);
        entry.setOperatorName(task.getOperator() == null ? "system" : task.getOperator());
        entry.setDetail("#" + task.getId() + " " + task.getTitle() + "：" + detail);
        logRepository.save(entry);
    }

    private String triggerLabel(TaskTriggerType t) {
        return switch (t) {
            case DEVIATION -> "环境偏差";
            case PERIODIC -> "周期计划";
            case PEST -> "病虫害识别";
            case MANUAL -> "人工";
        };
    }

    /** 暴露给任务生成器：查询某幂等键最近生成的任务（周期任务推算下次到期用） */
    public FarmTask latestByDedupeKey(String key) {
        return taskRepository.findFirstByDedupeKeyOrderByGeneratedAtDesc(key).orElse(null);
    }

    public static TaskType envTaskType(String metric) {
        return switch (metric) {
            case "temperature" -> TaskType.ENV_TEMP;
            case "humidity" -> TaskType.ENV_HUMIDITY;
            case "light" -> TaskType.ENV_LIGHT;
            case "co2" -> TaskType.ENV_CO2;
            default -> TaskType.OTHER;
        };
    }

    @PreDestroy
    public void shutdown() {
        holdScheduler.shutdownNow();
    }
}
