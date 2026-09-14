package com.greenhouse.config;

import com.greenhouse.entity.FarmTask;
import com.greenhouse.entity.Greenhouse;
import com.greenhouse.entity.PestKnowledge;
import com.greenhouse.entity.Prescription;
import com.greenhouse.entity.YieldRecord;
import com.greenhouse.enums.PrescriptionStatus;
import com.greenhouse.enums.TaskExecMode;
import com.greenhouse.enums.TaskStatus;
import com.greenhouse.enums.TaskTriggerType;
import com.greenhouse.enums.TaskType;
import com.greenhouse.repository.FarmTaskRepository;
import com.greenhouse.repository.GreenhouseRepository;
import com.greenhouse.repository.PestKnowledgeRepository;
import com.greenhouse.repository.PrescriptionRepository;
import com.greenhouse.repository.YieldRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 农事处方模块演示数据（在 {@link DataInitializer} 之后执行）：
 * 补齐大棚品种/生育期/茬次；写入番茄结果期处方 v1（归档）+ v2（发布）；
 * 内置病虫害知识库；按茬次回填产量/品质与历史农事任务（闭环日志 + 产量分析）。
 */
@Slf4j
@Component
@Order(20)
public class FarmDataInitializer implements CommandLineRunner {

    private final GreenhouseRepository greenhouseRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final PestKnowledgeRepository pestKnowledgeRepository;
    private final YieldRecordRepository yieldRepository;
    private final FarmTaskRepository taskRepository;

    public FarmDataInitializer(GreenhouseRepository greenhouseRepository,
                               PrescriptionRepository prescriptionRepository,
                               PestKnowledgeRepository pestKnowledgeRepository,
                               YieldRecordRepository yieldRepository,
                               FarmTaskRepository taskRepository) {
        this.greenhouseRepository = greenhouseRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.pestKnowledgeRepository = pestKnowledgeRepository;
        this.yieldRepository = yieldRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    public void run(String... args) {
        if (prescriptionRepository.count() > 0) {
            return;
        }
        Greenhouse gh = greenhouseRepository.findAll().stream().findFirst().orElse(null);
        if (gh == null) {
            return;
        }
        gh.setVariety("番茄-佳粉18号");
        gh.setGrowthStage("结果期");
        gh.setCurrentBatchNo("2026秋茬-1号棚");
        greenhouseRepository.save(gh);
        Long ghId = gh.getId();

        createPrescriptions(gh);
        createPestKnowledge();
        createYields(ghId);
        createTasks(ghId);
        log.info("========== 农事处方模块演示数据已初始化 ==========");
    }

    private void createPrescriptions(Greenhouse gh) {
        String variety = "番茄-佳粉18号";
        String stage = "结果期";

        // v1：历史版本，已归档
        Prescription v1 = new Prescription();
        v1.setVariety(variety);
        v1.setGrowthStage(stage);
        v1.setName("番茄结果期处方（初版）");
        v1.setVersion(1);
        v1.setStatus(PrescriptionStatus.ARCHIVED);
        v1.setEnabled(false);
        v1.setEnvTargetsJson(ENV_TARGETS_V1);
        v1.setOperationsJson(OPERATIONS);
        v1.setRemark("初版阈值偏宽，结果期高温日灼偏多，已被 v2 取代");
        v1.setCreatedBy("agronomist");
        v1.setPublishedAt(LocalDateTime.now().minusDays(60));
        v1.setCreatedAt(LocalDateTime.now().minusDays(60));
        prescriptionRepository.save(v1);

        // v2：当前发布版本（由 v1 复制演进）
        Prescription v2 = new Prescription();
        v2.setVariety(variety);
        v2.setGrowthStage(stage);
        v2.setName("番茄结果期处方（现行）");
        v2.setVersion(2);
        v2.setStatus(PrescriptionStatus.PUBLISHED);
        v2.setEnabled(true);
        v2.setEnvTargetsJson(ENV_TARGETS_V2);
        v2.setOperationsJson(OPERATIONS);
        v2.setCopiedFromId(v1.getId());
        v2.setRemark("收紧温度上限至 28℃，湿度区间 60~80%，新增 CO₂ 目标");
        v2.setCreatedBy("agronomist");
        v2.setPublishedAt(LocalDateTime.now().minusDays(20));
        v2.setCreatedAt(LocalDateTime.now().minusDays(21));
        prescriptionRepository.save(v2);

        // 苗期处方（发布），便于切换生育期演示
        Prescription seed = new Prescription();
        seed.setVariety(variety);
        seed.setGrowthStage("苗期");
        seed.setName("番茄苗期处方");
        seed.setVersion(1);
        seed.setStatus(PrescriptionStatus.PUBLISHED);
        seed.setEnabled(true);
        seed.setEnvTargetsJson(ENV_TARGETS_SEED);
        seed.setOperationsJson(SEED_OPERATIONS);
        seed.setCreatedBy("agronomist");
        seed.setPublishedAt(LocalDateTime.now().minusDays(70));
        seed.setCreatedAt(LocalDateTime.now().minusDays(70));
        prescriptionRepository.save(seed);
    }

    private void createPestKnowledge() {
        knowledge("番茄早疫病", "病害", "番茄,马铃薯,茄子",
                "[\"叶片褐色同心轮纹斑\",\"下部老叶先发病\",\"病斑黑色霉层\",\"茎秆椭圆凹陷斑\"]",
                "叶片出现褐色至深褐色圆形或近圆形病斑，具同心轮纹，边缘多具浅绿色或黄色晕环；潮湿时病斑上生黑色霉层。多从植株下部叶片开始向上发展。",
                "高温高湿、昼夜温差大结露多时易流行；病菌随病残体在土中越冬，借气流、雨水传播。",
                "1. 及时摘除下部老叶病叶并带出棚外销毁；2. 控温控湿，加强通风排湿，避免叶面长时间结露；3. 与非茄科作物轮作；4. 发病初期可用 75% 百菌清可湿性粉剂 600 倍液或 10% 苯醚甲环唑 1500 倍液喷雾。",
                "苯醚甲环唑 10% 水分散粒剂 1500 倍，安全间隔期 7 天；百菌清 75% 600 倍，间隔 7 天。");
        knowledge("番茄晚疫病", "病害", "番茄,马铃薯",
                "[\"叶尖叶缘暗绿色水浸状斑\",\"病健交界处长白色霉层\",\"茎秆黑褐色腐烂\",\"青果硬褐斑块\"]",
                "叶片多从叶尖、叶缘开始，初为暗绿色水浸状不规则斑，扩大后变褐；湿度大时病健交界处长白色稀疏霉层。茎秆病斑黑褐色腐烂，青果出现油渍状硬褐斑块。",
                "低温高湿（18~22℃、相对湿度 95% 以上）、叶面结露时暴发，靠气流和雨水传播，流行性极强。",
                "1. 关闭风口前避免棚内结露，采用膜下滴灌降低湿度；2. 发现中心病株立即拔除并施药保护周围；3. 发病初期用 68.75% 氟菌·霜霉威悬浮剂 800 倍液或烯酰吗啉 80% 2000 倍液，叶背与茎秆均匀着药。",
                "氟菌·霜霉威 68.75% 800 倍，安全间隔期 5 天；烯酰吗啉 80% 2000 倍，间隔 3 天。");
        knowledge("番茄灰霉病", "病害", "番茄,黄瓜,草莓",
                "[\"花瓣残花灰霉腐烂\",\"青果脐部水浸状变褐\",\"灰色霉层\",\"病果软腐脱落\"]",
                "病菌多从残留的花瓣、柱头侵染，再向青果脐部扩展，呈水浸状变褐软腐，病部密生灰褐色霉层；叶片病斑多呈 V 形向内扩展。",
                "温度 20~25℃、持续高湿（90% 以上）及残花滞留时最易发生，花期是关键侵染期。",
                "1. 花期及时摘除残花残叶，番茄沾花后 10~15 天摘除幼果上残留花瓣；2. 控水排湿，上午升温后再放风；3. 发病初期用 50% 啶酰菌胺 1500 倍液或 40% 嘧霉胺 1000 倍液，重点喷花和青果。",
                "啶酰菌胺 50% 1500 倍，安全间隔期 5 天；嘧霉胺 40% 1000 倍，间隔 7 天。");
        knowledge("烟粉虱", "虫害", "番茄,黄瓜,辣椒",
                "[\"叶背白色小虫群集\",\"碰动成虫小白蛾飞起\",\"叶片蜜露煤污\",\"黄化褪绿卷曲\"]",
                "成虫体小淡黄白色，群集于嫩叶叶背刺吸汁液，受惊扰成群飞起；分泌蜜露诱发煤污病，并传播番茄黄化曲叶病毒（TYLCV）。",
                "棚内干热、靠近虫源或杂草多发生重，繁殖快、世代重叠，抗药性强。",
                "1. 风口加装 50 目防虫网，棚内挂黄板诱杀（每亩 30 块）；2. 清除棚内外杂草寄主；3. 若虫盛发期用 22.4% 螺虫乙酯 3000 倍液或 25% 吡蚜酮 1500 倍液，叶背均匀喷雾，轮换用药防抗性。",
                "螺虫乙酯 22.4% 3000 倍，安全间隔期 5 天；吡蚜酮 25% 1500 倍，间隔 7 天。");
    }

    private void knowledge(String name, String category, String crops, String featuresJson,
                           String symptoms, String cause, String treatment, String pesticide) {
        PestKnowledge k = new PestKnowledge();
        k.setName(name);
        k.setCategory(category);
        k.setCrops(crops);
        k.setFeaturesJson(featuresJson);
        k.setSymptoms(symptoms);
        k.setCause(cause);
        k.setTreatment(treatment);
        k.setPesticide(pesticide);
        k.setImageUrl(""); // 图谱以文字症状为主，图片位预留给后续接入图库
        pestKnowledgeRepository.save(k);
    }

    private void createYields(Long ghId) {
        String batch = "2026秋茬-1号棚";
        addYield(ghId, batch, LocalDate.of(2026, 8, 20), 96.0, 88.0, 4.8, "首批转色采收");
        addYield(ghId, batch, LocalDate.of(2026, 8, 27), 132.5, 90.0, 5.0, "");
        addYield(ghId, batch, LocalDate.of(2026, 9, 3), 158.0, 86.0, 4.7, "周初高温，通风时长偏短");
        addYield(ghId, batch, LocalDate.of(2026, 9, 10), 172.5, 91.5, 5.3, "果个均匀，糖度提升");
    }

    private void addYield(Long ghId, String batch, LocalDate date, double weight,
                       double premium, double brix, String remark) {
        YieldRecord r = new YieldRecord();
        r.setGreenhouseId(ghId);
        r.setBatchNo(batch);
        r.setHarvestDate(date);
        r.setWeightKg(weight);
        r.setPremiumRate(premium);
        r.setBrix(brix);
        r.setRemark(remark);
        r.setRecordedBy("agronomist");
        yieldRepository.save(r);
    }

    /** 回填本茬闭环农事任务：设备联动 + 人工反馈各若干，另留一条待办 */
    private void createTasks(Long ghId) {
        String batch = "2026秋茬-1号棚";

        doneDeviceTask(ghId, batch, TaskType.IRRIGATION, "滴灌（水肥一体）",
                LocalDateTime.now().minusDays(2),
                "gh:" + ghId + ":op:drip",
                "[{\"deviceSn\":\"WC-001\",\"action\":\"OPEN\",\"holdMinutes\":10,\"thenAction\":\"CLOSE\"}]",
                200.0, "L", 12, "土壤湿度恢复至 72%，滴灌均匀。");
        doneManualTask(ghId, batch, TaskType.PRUNING, "整枝打杈、疏除下部老叶",
                LocalDateTime.now().minusDays(1), "gh:" + ghId + ":op:prune",
                null, 45, "摘除老叶 32 片，改善通风。", null, null);
        doneManualTask(ghId, batch, TaskType.PLANT_PROTECTION, "灰霉病预防施药",
                LocalDateTime.now().minusDays(3), "gh:" + ghId + ":op:protect",
                null, 40, "啶酰菌胺 1500 倍液 24L，重点喷花与青果。", 1.2, "袋");

        FarmTask pending = new FarmTask();
        pending.setGreenhouseId(ghId);
        pending.setBatchNo(batch);
        pending.setType(TaskType.HARVEST);
        pending.setTriggerType(TaskTriggerType.PERIODIC);
        pending.setExecMode(TaskExecMode.MANUAL);
        pending.setStatus(TaskStatus.PENDING);
        pending.setTitle("采收成熟转色果穗");
        pending.setInstruction("选择全红或转色均匀果实采收，轻拿轻放，剔除裂果日灼果。");
        pending.setDedupeKey("gh:" + ghId + ":op:harvest");
        pending.setAssignee("现场人员");
        taskRepository.save(pending);
    }

    private void doneDeviceTask(Long ghId, String batch, TaskType type, String title,
                                LocalDateTime at, String dedupeKey, String actionsJson,
                                Double used, String unit, Integer minutes, String feedback) {
        FarmTask t = baseDoneTask(ghId, batch, type, title, at, feedback, used, unit, minutes);
        t.setDedupeKey(dedupeKey);
        t.setTriggerType(TaskTriggerType.PERIODIC);
        t.setExecMode(TaskExecMode.DEVICE);
        t.setDeviceActionsJson(actionsJson);
        t.setChainId("seed-" + Math.abs(title.hashCode()));
        taskRepository.save(t);
    }

    private void doneManualTask(Long ghId, String batch, TaskType type, String title,
                                LocalDateTime at, String dedupeKey, String actionsJson, Integer minutes,
                                String feedback, Double used, String unit) {
        FarmTask t = baseDoneTask(ghId, batch, type, title, at, feedback, used, unit, minutes);
        t.setDedupeKey(dedupeKey);
        t.setTriggerType(TaskTriggerType.MANUAL);
        t.setExecMode(TaskExecMode.MANUAL);
        t.setDeviceActionsJson(actionsJson);
        taskRepository.save(t);
    }

    private FarmTask baseDoneTask(Long ghId, String batch, TaskType type, String title,
                                  LocalDateTime at, String feedback, Double used,
                                  String unit, Integer minutes) {
        FarmTask t = new FarmTask();
        t.setGreenhouseId(ghId);
        t.setBatchNo(batch);
        t.setType(type);
        t.setTitle(title);
        t.setStatus(TaskStatus.DONE);
        t.setGeneratedAt(at);
        t.setStartedAt(at);
        t.setFinishedAt(at.plusMinutes(minutes == null ? 0 : minutes));
        t.setMaterialUsed(used);
        t.setMaterialUnit(unit);
        t.setDurationMinutes(minutes);
        t.setFeedback(feedback);
        t.setOperator("现场人员");
        return t;
    }

    // ---- 处方配置快照 ----

    private static final String ENV_TARGETS_V2 = """
            [
              {"metric":"temperature","low":22,"high":28,"tolerance":1,"unit":"℃"},
              {"metric":"humidity","low":60,"high":80,"tolerance":5,"unit":"%"},
              {"metric":"light","low":12000,"high":45000,"tolerance":3000,"unit":"lux"},
              {"metric":"co2","low":800,"high":1200,"tolerance":100,"unit":"ppm"}
            ]
            """;

    private static final String ENV_TARGETS_V1 = """
            [
              {"metric":"temperature","low":20,"high":31,"tolerance":1,"unit":"℃"},
              {"metric":"humidity","low":55,"high":85,"tolerance":5,"unit":"%"},
              {"metric":"light","low":10000,"high":50000,"tolerance":3000,"unit":"lux"}
            ]
            """;

    private static final String ENV_TARGETS_SEED = """
            [
              {"metric":"temperature","low":23,"high":28,"tolerance":1,"unit":"℃"},
              {"metric":"humidity","low":65,"high":80,"tolerance":5,"unit":"%"},
              {"metric":"light","low":10000,"high":30000,"tolerance":2000,"unit":"lux"}
            ]
            """;

    private static final String OPERATIONS = """
            [
              {"key":"drip","type":"IRRIGATION","name":"膜下滴灌","execMode":"DEVICE","deviceType":"WET_CURTAIN",
               "action":"OPEN","thenAction":"CLOSE","holdMinutes":10,"intervalDays":2,"time":"08:00",
               "dose":200,"doseUnit":"L","estimatedMinutes":15,
               "instruction":"结果期每 2 天滴灌一次，单次约 200L，开阀 10 分钟自动关闭，保持土壤湿润不积水。"},
              {"key":"fert","type":"FERTIGATION","name":"高钾水肥追施","execMode":"MANUAL",
               "intervalDays":5,"time":"09:00","dose":5,"doseUnit":"kg","estimatedMinutes":30,
               "instruction":"随水追施高钾水溶肥（N-P-K 15-10-30）每次 5kg，少量多次，采收前 3 天停肥。"},
              {"key":"protect","type":"PLANT_PROTECTION","name":"病虫害巡查与预防","execMode":"MANUAL",
               "intervalDays":7,"time":"15:00","estimatedMinutes":30,
               "instruction":"每周巡查叶背、青果与残花，重点查灰霉病、晚疫病与烟粉虱，见症对症用药。"},
              {"key":"prune","type":"PRUNING","name":"整枝打杈疏老叶","execMode":"MANUAL",
               "intervalDays":3,"estimatedMinutes":45,
               "instruction":"单干整枝，及时抹除侧杈，疏除下部黄化老叶，改善通风透光，减少病害。"},
              {"key":"harvest","type":"HARVEST","name":"成熟果穗采收","execMode":"MANUAL",
               "intervalDays":2,"time":"07:30","estimatedMinutes":60,
               "instruction":"选择全红或转色均匀果实采收，分级装箱，记录产量与优质果率。"}
            ]
            """;

    private static final String SEED_OPERATIONS = """
            [
              {"key":"seed-water","type":"IRRIGATION","name":"苗期轻灌","execMode":"MANUAL",
               "intervalDays":3,"dose":60,"doseUnit":"L","estimatedMinutes":20,
               "instruction":"见干见湿，控水蹲苗促根深扎，避免徒长。"},
              {"key":"seed-check","type":"INSPECTION_TASK","name":"苗情观察","execMode":"MANUAL",
               "intervalDays":2,"estimatedMinutes":15,
               "instruction":"观察真叶展开、茎秆粗度与叶色，发现猝倒、立枯及时拔除。"}
            ]
            """;
}
