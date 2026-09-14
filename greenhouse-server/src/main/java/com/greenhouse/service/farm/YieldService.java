package com.greenhouse.service.farm;

import com.greenhouse.entity.FarmTask;
import com.greenhouse.entity.YieldRecord;
import com.greenhouse.enums.TaskStatus;
import com.greenhouse.repository.FarmTaskRepository;
import com.greenhouse.repository.YieldRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产量/品质数据与农事闭环日志分析：
 * 按茬次（批次）聚合产量、品质与该茬农事任务执行情况，给出处方优化线索。
 */
@Service
public class YieldService {

    private final YieldRecordRepository yieldRepository;
    private final FarmTaskRepository taskRepository;

    public YieldService(YieldRecordRepository yieldRepository, FarmTaskRepository taskRepository) {
        this.yieldRepository = yieldRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public YieldRecord add(YieldRecord r) {
        r.setId(null);
        if (r.getHarvestDate() == null) {
            r.setHarvestDate(LocalDate.now());
        }
        return yieldRepository.save(r);
    }

    public List<YieldRecord> list(Long greenhouseId) {
        return greenhouseId != null
                ? yieldRepository.findByGreenhouseIdOrderByHarvestDateDesc(greenhouseId)
                : yieldRepository.findAllByOrderByHarvestDateDesc();
    }

    /**
     * 批次分析：每个有产量记录的批次聚合
     * 产量合计/均值、优质果率均值、糖度均值，以及该茬农事任务完成/失败/偏差统计。
     */
    public List<Map<String, Object>> batchAnalysis(Long greenhouseId) {
        List<YieldRecord> records = greenhouseId != null
                ? yieldRepository.findByGreenhouseIdOrderByHarvestDateDesc(greenhouseId)
                : yieldRepository.findAllByOrderByHarvestDateDesc();

        Map<String, List<YieldRecord>> byBatch = new LinkedHashMap<>();
        for (YieldRecord r : records) {
            byBatch.computeIfAbsent(r.getBatchNo(), k -> new ArrayList<>()).add(r);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<YieldRecord>> e : byBatch.entrySet()) {
            String batch = e.getKey();
            List<YieldRecord> rs = e.getValue();
            Long ghId = rs.get(0).getGreenhouseId();
            List<FarmTask> tasks = taskRepository
                    .findByGreenhouseIdAndBatchNoOrderByGeneratedAtDesc(ghId, batch);
            result.add(summarize(batch, ghId, rs, tasks));
        }
        return result;
    }

    private Map<String, Object> summarize(String batch, Long ghId,
                                          List<YieldRecord> rs, List<FarmTask> tasks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchNo", batch);
        m.put("greenhouseId", ghId);

        double totalWeight = rs.stream().mapToDouble(YieldRecord::getWeightKg).sum();
        m.put("harvestCount", rs.size());
        m.put("totalWeightKg", round1(totalWeight));
        m.put("avgWeightKg", round1(totalWeight / rs.size()));

        double avgPremium = rs.stream().filter(r -> r.getPremiumRate() != null)
                .mapToDouble(YieldRecord::getPremiumRate).average().orElse(Double.NaN);
        double avgBrix = rs.stream().filter(r -> r.getBrix() != null)
                .mapToDouble(YieldRecord::getBrix).average().orElse(Double.NaN);
        m.put("avgPremiumRate", Double.isNaN(avgPremium) ? null : round1(avgPremium));
        m.put("avgBrix", Double.isNaN(avgBrix) ? null : round2(avgBrix));
        m.put("firstHarvest", rs.stream().map(YieldRecord::getHarvestDate).min(Comparator.naturalOrder()).orElse(null));
        m.put("lastHarvest", rs.stream().map(YieldRecord::getHarvestDate).max(Comparator.naturalOrder()).orElse(null));

        // 农事执行情况
        long done = tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
        long failed = tasks.stream().filter(t -> t.getStatus() == TaskStatus.FAILED).count();
        long pending = tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.EXECUTING).count();
        long deviceTasks = tasks.stream().filter(t -> t.getDeviceActionsJson() != null).count();
        long totalDuration = tasks.stream()
                .filter(t -> t.getDurationMinutes() != null).mapToLong(FarmTask::getDurationMinutes).sum();
        m.put("taskTotal", tasks.size());
        m.put("taskDone", done);
        m.put("taskFailed", failed);
        m.put("taskPending", pending);
        m.put("taskDeviceCount", deviceTasks);
        m.put("manualTotalMinutes", totalDuration);
        m.put("completionRate", tasks.isEmpty() ? null
                : Math.round(done * 1000.0 / tasks.size()) / 10.0);

        // 按任务类型统计（处方优化线索）
        Map<String, Long> byType = new LinkedHashMap<>();
        for (FarmTask t : tasks) {
            byType.merge(t.getType().name(), 1L, Long::sum);
        }
        m.put("tasksByType", byType);
        m.put("insights", buildInsights(tasks, failed, pending, avgPremium, avgBrix));
        return m;
    }

    /** 简单的处方优化线索（规则式，非统计建模） */
    private List<String> buildInsights(List<FarmTask> tasks, long failed, long pending,
                                       double avgPremium, double avgBrix) {
        List<String> tips = new ArrayList<>();
        long envDeviation = tasks.stream()
                .filter(t -> switch (t.getType()) {
                    case ENV_TEMP, ENV_HUMIDITY, ENV_LIGHT, ENV_CO2 -> true;
                    default -> false;
                }).count();
        if (envDeviation >= 5) {
            tips.add("本茬环境偏差任务 " + envDeviation + " 次，建议复盘温湿度/光照目标区间与设备能力，校准处方阈值。");
        }
        if (failed > 0) {
            tips.add("有 " + failed + " 个任务设备联动失败，故障设备可能影响农事时效，建议结合设备运维看板检修。");
        }
        if (pending > 0) {
            tips.add(pending + " 个任务未闭环，请及时执行或取消，避免任务积压。");
        }
        if (!Double.isNaN(avgBrix) && avgBrix < 5.0) {
            tips.add("平均糖度偏低（" + round2(avgBrix) + "°Brix），可在结果期处方中适度控水控氮、增施磷钾肥。");
        }
        if (!Double.isNaN(avgPremium) && avgPremium < 80) {
            tips.add("优质果率偏低（" + round1(avgPremium) + "%），建议核对植保任务执行率与环境均匀性。");
        }
        if (tips.isEmpty()) {
            tips.add("本茬农事闭环完整、品质指标正常，可将当前处方版本固化为标准处方。");
        }
        return tips;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
