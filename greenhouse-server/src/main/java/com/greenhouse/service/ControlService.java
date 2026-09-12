package com.greenhouse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greenhouse.dto.ControlAction;
import com.greenhouse.entity.ControlCommand;
import com.greenhouse.entity.Device;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.entity.OperationLog;
import com.greenhouse.enums.AlarmLevel;
import com.greenhouse.enums.AlarmType;
import com.greenhouse.enums.CommandSource;
import com.greenhouse.enums.CommandStatus;
import com.greenhouse.enums.DeviceType;
import com.greenhouse.iot.DeviceSessionManager;
import com.greenhouse.repository.ControlCommandRepository;
import com.greenhouse.repository.DeviceRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.repository.OperationLogRepository;
import com.greenhouse.ws.RealtimePushService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 控制指令服务：
 * <ul>
 *   <li>指令下发与回执跟踪（超时未回执自动重试，耗尽转失败并告警）</li>
 *   <li>设备离线时指令缓存，网关重连后补发</li>
 *   <li>联动链：一组动作按序执行，上一环回执成功才执行下一环（互锁保护的基础）</li>
 *   <li>手动控制的安全互锁校验（湿帘未开禁强通风 / 风机运行禁关湿帘）</li>
 * </ul>
 */
@Slf4j
@Service
public class ControlService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ControlCommandRepository commandRepository;
    private final DeviceRepository deviceRepository;
    private final GreenhouseRepository greenhouseRepository;
    private final OperationLogRepository logRepository;
    private final DeviceSessionManager sessionManager;
    private final DeviceService deviceService;
    private final AlarmService alarmService;
    private final RealtimePushService pushService;

    @Value("${greenhouse.control.ack-timeout-seconds:5}")
    private int ackTimeoutSeconds;

    @Value("${greenhouse.control.max-retry:3}")
    private int maxRetry;

    /** 回执超时定时器 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "control-timeout");
        t.setDaemon(true);
        return t;
    });

    /** commandId -> 超时任务 */
    private final Map<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    /** 联动链上下文：chainId -> 剩余动作 */
    private final Map<String, ChainContext> chains = new ConcurrentHashMap<>();

    /** commandId -> chainId */
    private final Map<String, String> commandChain = new ConcurrentHashMap<>();

    private record ChainContext(Deque<ControlAction> remaining, CommandSource source,
                                String operator, String reason) {}

    public ControlService(ControlCommandRepository commandRepository,
                          DeviceRepository deviceRepository,
                          GreenhouseRepository greenhouseRepository,
                          OperationLogRepository logRepository,
                          DeviceSessionManager sessionManager,
                          DeviceService deviceService,
                          AlarmService alarmService,
                          RealtimePushService pushService) {
        this.commandRepository = commandRepository;
        this.deviceRepository = deviceRepository;
        this.greenhouseRepository = greenhouseRepository;
        this.logRepository = logRepository;
        this.sessionManager = sessionManager;
        this.deviceService = deviceService;
        this.alarmService = alarmService;
        this.pushService = pushService;
    }

    // ==================== 联动链执行 ====================

    /**
     * 按序执行一组联动动作：前一个动作回执成功后才下发下一个；
     * 任一环节失败则中止后续动作并告警（安全互锁：如湿帘未开成功，风机不会启动）。
     *
     * @return chainId
     */
    public String executeLinked(List<ControlAction> actions, CommandSource source, String operator, String reason) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        String chainId = UUID.randomUUID().toString().substring(0, 8);
        chains.put(chainId, new ChainContext(new ArrayDeque<>(actions), source, operator, reason));
        log.info("[联动链 {}] 启动，共 {} 个动作，原因：{}", chainId, actions.size(), reason);
        writeLog(null, null, "联动启动", source, operator,
                String.format("链 %s：%s，共 %d 个动作", chainId, reason, actions.size()));
        dispatchNext(chainId);
        return chainId;
    }

    private void dispatchNext(String chainId) {
        ChainContext ctx = chains.get(chainId);
        if (ctx == null) {
            return;
        }
        ControlAction next = ctx.remaining().poll();
        if (next == null) {
            log.info("[联动链 {}] 全部动作执行完毕", chainId);
            chains.remove(chainId);
            return;
        }
        ControlCommand cmd = createCommand(next, ctx.source());
        commandChain.put(cmd.getCommandId(), chainId);
        dispatch(cmd);
    }

    private void abortChain(String chainId, String reason) {
        ChainContext ctx = chains.remove(chainId);
        if (ctx != null && !ctx.remaining().isEmpty()) {
            log.warn("[联动链 {}] 中止：{}，剩余 {} 个动作不再执行", chainId, reason, ctx.remaining().size());
            writeLog(null, null, "联动中止", ctx.source(), ctx.operator(),
                    String.format("链 %s 中止：%s，剩余 %d 个动作未执行", chainId, reason, ctx.remaining().size()));
        }
    }

    // ==================== 指令生命周期 ====================

    private ControlCommand createCommand(ControlAction action, CommandSource source) {
        Device device = deviceRepository.findBySn(action.deviceSn())
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + action.deviceSn()));
        ControlCommand cmd = new ControlCommand();
        cmd.setCommandId(UUID.randomUUID().toString());
        cmd.setGreenhouseId(device.getGreenhouseId());
        cmd.setDeviceSn(device.getSn());
        cmd.setAction(action.action());
        cmd.setParams(action.params());
        cmd.setSource(source);
        cmd.setMaxRetry(maxRetry);
        return commandRepository.save(cmd);
    }

    /** 下发指令：设备离线则缓存，在线则立即发送并启动回执超时计时 */
    private void dispatch(ControlCommand cmd) {
        Optional<Greenhouse> ghOpt = greenhouseRepository.findById(cmd.getGreenhouseId());
        if (ghOpt.isEmpty() || !sessionManager.isOnline(ghOpt.get().getGatewaySn())) {
            cmd.setStatus(CommandStatus.QUEUED_OFFLINE);
            commandRepository.save(cmd);
            log.info("[指令 {}] 网关离线，已缓存待补发：{} {}", cmd.getCommandId(), cmd.getDeviceSn(), cmd.getAction());
            pushService.broadcast("command", cmd);
            return;
        }
        doSend(cmd, ghOpt.get().getGatewaySn());
    }

    private void doSend(ControlCommand cmd, String gatewaySn) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("type", "CONTROL");
        msg.put("commandId", cmd.getCommandId());
        msg.put("deviceSn", cmd.getDeviceSn());
        msg.put("action", cmd.getAction());
        if (cmd.getParams() != null) {
            msg.put("params", cmd.getParams());
        }
        boolean sent = sessionManager.send(gatewaySn, msg.toString());
        if (!sent) {
            cmd.setStatus(CommandStatus.QUEUED_OFFLINE);
            commandRepository.save(cmd);
            return;
        }
        cmd.setStatus(CommandStatus.SENT);
        cmd.setSentAt(LocalDateTime.now());
        commandRepository.save(cmd);
        log.info("[指令 {}] 已下发：{} {}（第 {} 次）", cmd.getCommandId(), cmd.getDeviceSn(), cmd.getAction(), cmd.getRetryCount() + 1);
        pushService.broadcast("command", cmd);

        // 回执超时 → 重试或失败
        ScheduledFuture<?> task = scheduler.schedule(
                () -> onAckTimeout(cmd.getCommandId()), ackTimeoutSeconds, TimeUnit.SECONDS);
        timeoutTasks.put(cmd.getCommandId(), task);
    }

    private void onAckTimeout(String commandId) {
        timeoutTasks.remove(commandId);
        commandRepository.findByCommandId(commandId).ifPresent(cmd -> {
            if (cmd.getStatus() != CommandStatus.SENT) {
                return;
            }
            if (cmd.getRetryCount() < cmd.getMaxRetry()) {
                cmd.setRetryCount(cmd.getRetryCount() + 1);
                commandRepository.save(cmd);
                log.warn("[指令 {}] 回执超时，重试 {}/{}", commandId, cmd.getRetryCount(), cmd.getMaxRetry());
                greenhouseRepository.findById(cmd.getGreenhouseId())
                        .ifPresent(gh -> doSend(cmd, gh.getGatewaySn()));
            } else {
                failCommand(cmd, "回执超时，重试 " + cmd.getMaxRetry() + " 次仍无响应");
            }
        });
    }

    /** 网关回执：成功推进联动链，失败走重试 */
    public void handleAck(String commandId, boolean success, String state, String error) {
        Optional.ofNullable(timeoutTasks.remove(commandId)).ifPresent(t -> t.cancel(false));
        Optional<ControlCommand> cmdOpt = commandRepository.findByCommandId(commandId);
        if (cmdOpt.isEmpty()) {
            log.warn("收到未知指令回执: {}", commandId);
            return;
        }
        ControlCommand cmd = cmdOpt.get();
        if (cmd.getStatus() != CommandStatus.SENT) {
            return; // 已终结（超时失败等），忽略迟到回执
        }

        if (success) {
            cmd.setStatus(CommandStatus.ACKED);
            cmd.setAckedAt(LocalDateTime.now());
            commandRepository.save(cmd);
            String newState = state != null && !state.isBlank() ? state : deriveState(cmd.getAction());
            deviceService.updateState(cmd.getDeviceSn(), newState);
            log.info("[指令 {}] 回执成功：{} {} -> {}", commandId, cmd.getDeviceSn(), cmd.getAction(), newState);
            writeLog(cmd.getGreenhouseId(), cmd.getDeviceSn(), actionName(cmd.getAction()),
                    cmd.getSource(), operatorOf(cmd), "指令回执成功，状态=" + newState);
            pushService.broadcast("command", cmd);
            // 推进联动链
            String chainId = commandChain.remove(commandId);
            if (chainId != null) {
                dispatchNext(chainId);
            }
        } else {
            if (cmd.getRetryCount() < cmd.getMaxRetry()) {
                cmd.setRetryCount(cmd.getRetryCount() + 1);
                commandRepository.save(cmd);
                log.warn("[指令 {}] 设备返回失败({})，重试 {}/{}", commandId, error, cmd.getRetryCount(), cmd.getMaxRetry());
                greenhouseRepository.findById(cmd.getGreenhouseId())
                        .ifPresent(gh -> doSend(cmd, gh.getGatewaySn()));
            } else {
                failCommand(cmd, "设备执行失败: " + error);
            }
        }
    }

    private void failCommand(ControlCommand cmd, String errorMsg) {
        cmd.setStatus(CommandStatus.FAILED);
        cmd.setErrorMsg(errorMsg);
        commandRepository.save(cmd);
        log.error("[指令 {}] 失败：{} {} - {}", cmd.getCommandId(), cmd.getDeviceSn(), cmd.getAction(), errorMsg);
        alarmService.raise(cmd.getGreenhouseId(), cmd.getDeviceSn(), AlarmType.COMMAND_FAILED,
                AlarmLevel.CRITICAL,
                String.format("控制指令失败：%s %s，%s", cmd.getDeviceSn(), cmd.getAction(), errorMsg));
        pushService.broadcast("command", cmd);
        // 互锁保护：前置动作失败，中止整条联动链（如湿帘未开成功，风机不会启动）
        String chainId = commandChain.remove(cmd.getCommandId());
        if (chainId != null) {
            abortChain(chainId, "指令 " + cmd.getDeviceSn() + " " + cmd.getAction() + " 失败");
        }
    }

    /** 网关重连后补发离线期间缓存的指令 */
    public void flushOfflineQueue(String gatewaySn) {
        greenhouseRepository.findByGatewaySn(gatewaySn).ifPresent(gh -> {
            List<ControlCommand> queued = commandRepository.findByStatus(CommandStatus.QUEUED_OFFLINE)
                    .stream()
                    .filter(c -> c.getGreenhouseId().equals(gh.getId()))
                    .toList();
            if (queued.isEmpty()) {
                return;
            }
            log.info("网关 {} 重连，补发 {} 条离线缓存指令", gatewaySn, queued.size());
            queued.forEach(cmd -> {
                cmd.setSource(CommandSource.OFFLINE_RETRY);
                commandRepository.save(cmd);
                doSend(cmd, gatewaySn);
            });
        });
    }

    // ==================== 手动控制（含安全互锁校验） ====================

    /**
     * 手动控制单台设备。
     * 互锁规则：开风机前湿帘必须已开（禁强通风）；关湿帘前风机必须已停。
     *
     * @return null 表示已受理；否则返回被拒绝的原因
     */
    public String manualControl(String deviceSn, String action, String params, String operator) {
        Device device = deviceRepository.findBySn(deviceSn)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceSn));
        List<Device> devices = deviceRepository.findByGreenhouseId(device.getGreenhouseId());

        String blocked = checkInterlock(device, action, devices);
        if (blocked != null) {
            alarmService.raise(device.getGreenhouseId(), deviceSn, AlarmType.INTERLOCK_BLOCKED,
                    AlarmLevel.WARN, blocked);
            writeLog(device.getGreenhouseId(), deviceSn, actionName(action), CommandSource.MANUAL,
                    operator, "被互锁阻止：" + blocked);
            return blocked;
        }
        executeLinked(List.of(new ControlAction(deviceSn, action, params)),
                CommandSource.MANUAL, operator, "手动控制 " + device.getName());
        return null;
    }

    /** 互锁校验，返回 null 表示通过 */
    private String checkInterlock(Device device, String action, List<Device> devices) {
        if (device.getType() == DeviceType.FAN && "ON".equals(action)) {
            Optional<Device> curtain = devices.stream()
                    .filter(d -> d.getType() == DeviceType.WET_CURTAIN).findFirst();
            if (curtain.isPresent() && !"OPEN".equals(curtain.get().getState())) {
                return "安全互锁：湿帘未开启，禁止启动风机强通风（请先开湿帘）";
            }
        }
        if (device.getType() == DeviceType.WET_CURTAIN && "CLOSE".equals(action)) {
            Optional<Device> fan = devices.stream()
                    .filter(d -> d.getType() == DeviceType.FAN).findFirst();
            if (fan.isPresent() && "ON".equals(fan.get().getState())) {
                return "安全互锁：风机运行中，禁止关闭湿帘（请先停风机）";
            }
        }
        return null;
    }

    // ==================== 工具 ====================

    private String deriveState(String action) {
        return switch (action) {
            case "ON" -> "ON";
            case "OFF" -> "OFF";
            case "OPEN" -> "OPEN";
            case "CLOSE" -> "CLOSED";
            default -> action;
        };
    }

    private String actionName(String action) {
        return switch (action) {
            case "ON" -> "开启";
            case "OFF" -> "关闭";
            case "OPEN" -> "开启";
            case "CLOSE" -> "关闭";
            case "STOP" -> "停止";
            default -> action;
        };
    }

    private String operatorOf(ControlCommand cmd) {
        return cmd.getSource() == CommandSource.MANUAL ? "manual" : "system";
    }

    private void writeLog(Long greenhouseId, String deviceSn, String action,
                          CommandSource source, String operator, String detail) {
        OperationLog logEntry = new OperationLog();
        logEntry.setGreenhouseId(greenhouseId);
        logEntry.setDeviceSn(deviceSn);
        logEntry.setAction(action);
        logEntry.setSource(source);
        logEntry.setOperatorName(operator == null ? "system" : operator);
        logEntry.setDetail(detail);
        logRepository.save(logEntry);
    }

    public List<ControlCommand> recentCommands(Long greenhouseId) {
        return greenhouseId != null
                ? commandRepository.findTop100ByGreenhouseIdOrderByCreatedAtDesc(greenhouseId)
                : commandRepository.findTop100ByOrderByCreatedAtDesc();
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
