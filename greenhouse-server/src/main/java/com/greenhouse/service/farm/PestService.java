package com.greenhouse.service.farm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenhouse.entity.FarmTask;
import com.greenhouse.entity.PestDiagnosis;
import com.greenhouse.entity.PestKnowledge;
import com.greenhouse.enums.TaskExecMode;
import com.greenhouse.enums.TaskTriggerType;
import com.greenhouse.enums.TaskType;
import com.greenhouse.repository.PestDiagnosisRepository;
import com.greenhouse.repository.PestKnowledgeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病虫害识别服务：知识库维护 + 基于鉴别特征的加权匹配诊断 + 推送处置建议。
 *
 * <p>本地演示环境无云端图像模型：移动端拍照后由用户勾选肉眼可见特征，
 * 服务端按「命中特征数 / 条目特征总数」计算置信度并给出候选列表；
 * 照片 base64 随记录留档，便于后续对接图像识别 API（替换 diagnose 内部实现即可）。
 */
@Slf4j
@Service
public class PestService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PestKnowledgeRepository knowledgeRepository;
    private final PestDiagnosisRepository diagnosisRepository;
    private final FarmTaskService farmTaskService;

    public PestService(PestKnowledgeRepository knowledgeRepository,
                       PestDiagnosisRepository diagnosisRepository,
                       FarmTaskService farmTaskService) {
        this.knowledgeRepository = knowledgeRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.farmTaskService = farmTaskService;
    }

    // ==================== 知识库 ====================

    public List<PestKnowledge> listKnowledge(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return knowledgeRepository.findAllByOrderByIdAsc();
        }
        return knowledgeRepository.findByNameContainingOrCropsContaining(keyword.trim(), keyword.trim());
    }

    public PestKnowledge getKnowledge(Long id) {
        return knowledgeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库条目不存在: " + id));
    }

    @Transactional
    public PestKnowledge saveKnowledge(PestKnowledge k) {
        k.setId(null);
        return knowledgeRepository.save(k);
    }

    @Transactional
    public PestKnowledge updateKnowledge(Long id, PestKnowledge in) {
        PestKnowledge k = getKnowledge(id);
        k.setName(in.getName());
        k.setCategory(in.getCategory());
        k.setCrops(in.getCrops());
        k.setFeaturesJson(in.getFeaturesJson());
        k.setSymptoms(in.getSymptoms());
        k.setCause(in.getCause());
        k.setTreatment(in.getTreatment());
        k.setPesticide(in.getPesticide());
        k.setImageUrl(in.getImageUrl());
        return knowledgeRepository.save(k);
    }

    @Transactional
    public void deleteKnowledge(Long id) {
        knowledgeRepository.deleteById(id);
    }

    /** 知识库中全部特征关键词（移动端勾选清单，去重） */
    public List<String> allFeatures() {
        Map<String, Boolean> uniq = new LinkedHashMap<>();
        for (PestKnowledge k : knowledgeRepository.findAllByOrderByIdAsc()) {
            for (String f : parseFeatures(k.getFeaturesJson())) {
                uniq.putIfAbsent(f, true);
            }
        }
        return new ArrayList<>(uniq.keySet());
    }

    // ==================== 诊断 ====================

    public record Match(Long id, String name, String category, double confidence,
                        int hit, int total, String advice, String pesticide, String imageUrl) {}

    /**
     * 诊断主入口。
     *
     * @param photoBase64 现场照片（可空，仅留档）
     * @param features    用户勾选的鉴别特征
     * @return 落库的诊断记录（含候选列表与处置建议快照）
     */
    @Transactional
    public PestDiagnosis diagnose(Long greenhouseId, String operator, String photoBase64, List<String> features) {
        List<PestKnowledge> all = knowledgeRepository.findAllByOrderByIdAsc();
        List<Match> candidates = new ArrayList<>();
        List<String> selected = features == null ? List.of() : features.stream()
                .filter(f -> f != null && !f.isBlank()).map(String::trim).distinct().toList();

        for (PestKnowledge k : all) {
            List<String> kf = parseFeatures(k.getFeaturesJson());
            if (kf.isEmpty()) {
                continue;
            }
            int hit = 0;
            for (String f : selected) {
                if (kf.stream().anyMatch(x -> x.contains(f) || f.contains(x))) {
                    hit++;
                }
            }
            if (hit == 0) {
                continue;
            }
            double confidence = Math.min(1.0, (double) hit / kf.size());
            candidates.add(new Match(k.getId(), k.getName(), k.getCategory(),
                    round2(confidence), hit, kf.size(),
                    k.getTreatment(), k.getPesticide(), k.getImageUrl()));
        }
        candidates.sort(Comparator.comparingDouble(Match::confidence).reversed());

        PestDiagnosis d = new PestDiagnosis();
        d.setGreenhouseId(greenhouseId);
        d.setPhotoBase64(truncatePhoto(photoBase64));
        d.setSelectedFeaturesJson(writeJson(selected));
        d.setCandidatesJson(writeJson(candidates));
        d.setOperator(operator);
        if (!candidates.isEmpty()) {
            Match best = candidates.get(0);
            d.setMatchedKnowledgeId(best.id());
            d.setMatchedName(best.name());
            d.setConfidence(best.confidence());
            d.setAdvice(buildAdvice(best));
        } else {
            d.setMatchedName(selected.isEmpty() ? "待人工诊断" : "未匹配到已知条目");
            d.setConfidence(0.0);
            d.setAdvice("特征不足或为未知病虫害，请结合图谱人工复核，必要时采样送检并加强巡检。");
        }
        return diagnosisRepository.save(d);
    }

    private String buildAdvice(Match m) {
        StringBuilder sb = new StringBuilder();
        sb.append("【识别】").append(m.name()).append("（").append(m.category())
                .append("，置信度 ").append(Math.round(m.confidence() * 100)).append("%）\n");
        if (m.advice() != null && !m.advice().isBlank()) {
            sb.append("【防治措施】").append(m.advice().trim()).append('\n');
        }
        if (m.pesticide() != null && !m.pesticide().isBlank()) {
            sb.append("【推荐用药】").append(m.pesticide().trim());
        }
        return sb.toString();
    }

    /** 识别后一键生成植保任务（人工执行：打药/处置，回填用量耗时） */
    @Transactional
    public FarmTask createProtectionTask(Long diagnosisId, String operator) {
        PestDiagnosis d = diagnosisRepository.findById(diagnosisId)
                .orElseThrow(() -> new IllegalArgumentException("识别记录不存在: " + diagnosisId));
        if (d.getTaskCreated()) {
            return farmTaskService.get(d.getTaskId());
        }
        FarmTask t = new FarmTask();
        t.setGreenhouseId(d.getGreenhouseId());
        t.setType(TaskType.PLANT_PROTECTION);
        t.setTriggerType(TaskTriggerType.PEST);
        t.setExecMode(TaskExecMode.MANUAL);
        t.setDiagnosisId(d.getId());
        t.setTitle("植保防治：" + (d.getMatchedName() == null ? "疑似病虫害" : d.getMatchedName()));
        t.setInstruction(d.getAdvice());
        t.setAssignee(operator);
        FarmTask saved = farmTaskService.generateIfAbsent(t);
        d.setTaskCreated(true);
        d.setTaskId(saved.getId());
        diagnosisRepository.save(d);
        return saved;
    }

    public List<PestDiagnosis> listDiagnoses(Long greenhouseId) {
        return greenhouseId != null
                ? diagnosisRepository.findTop100ByGreenhouseIdOrderByCreatedAtDesc(greenhouseId)
                : diagnosisRepository.findTop100ByOrderByCreatedAtDesc();
    }

    // ==================== 工具 ====================

    private List<String> parseFeatures(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 照片仅留档缩略，限制 H2 内存库占用（约 200KB 文本上限） */
    private String truncatePhoto(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 200_000 ? s.substring(0, 200_000) : s;
    }
}
