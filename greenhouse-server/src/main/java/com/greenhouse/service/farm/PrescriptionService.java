package com.greenhouse.service.farm;

import com.greenhouse.entity.Prescription;
import com.greenhouse.enums.PrescriptionStatus;
import com.greenhouse.repository.PrescriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 农事处方库服务：按「品种 + 生育期」管理，支持复制与版本控制。
 *
 * <p>规则：
 * <ul>
 *   <li>DRAFT 可编辑/删除；PUBLISHED 只读，改动需「复制为新版本」</li>
 *   <li>每个品种+生育期至多一个 PUBLISHED 版本，发布新版本自动归档旧发布版</li>
 * </ul>
 */
@Service
public class PrescriptionService {

    private final PrescriptionRepository repository;

    public PrescriptionService(PrescriptionRepository repository) {
        this.repository = repository;
    }

    public List<Prescription> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Prescription get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("处方不存在: " + id));
    }

    /** 某品种+生育期的全部历史版本（版本号倒序） */
    public List<Prescription> versions(String variety, String growthStage) {
        return repository.findByVarietyAndGrowthStageOrderByVersionDesc(variety, growthStage);
    }

    /** 任务生成用：取某品种+生育期当前生效（已发布且启用）的处方 */
    public Prescription active(String variety, String growthStage) {
        if (variety == null || growthStage == null) {
            return null;
        }
        return repository
                .findFirstByVarietyAndGrowthStageAndStatusAndEnabledTrueOrderByVersionDesc(
                        variety, growthStage, PrescriptionStatus.PUBLISHED)
                .orElse(null);
    }

    /** 新建处方（强制 DRAFT，版本号自动取该品种+生育期最大值 +1） */
    @Transactional
    public Prescription create(Prescription p) {
        p.setId(null);
        p.setStatus(PrescriptionStatus.DRAFT);
        p.setVersion(nextVersion(p.getVariety(), p.getGrowthStage()));
        if (p.getCreatedBy() == null) {
            p.setCreatedBy("admin");
        }
        return repository.save(p);
    }

    /** 编辑：仅草稿可改；发布版需先复制为新版本 */
    @Transactional
    public Prescription update(Long id, Prescription in) {
        Prescription existing = get(id);
        if (existing.getStatus() != PrescriptionStatus.DRAFT) {
            throw new IllegalStateException("已发布/归档处方只读，请「复制为新版本」后修改");
        }
        existing.setName(in.getName());
        existing.setEnvTargetsJson(in.getEnvTargetsJson());
        existing.setOperationsJson(in.getOperationsJson());
        existing.setRemark(in.getRemark());
        existing.setEnabled(in.getEnabled() == null ? existing.getEnabled() : in.getEnabled());
        return repository.save(existing);
    }

    /** 复制：源处方（任意状态）→ 同品种+生育期的一份新草稿，版本号递增 */
    @Transactional
    public Prescription copy(Long sourceId, String createdBy) {
        Prescription src = get(sourceId);
        Prescription dst = new Prescription();
        dst.setVariety(src.getVariety());
        dst.setGrowthStage(src.getGrowthStage());
        dst.setName(src.getName() + "（副本 v" + (nextVersion(src.getVariety(), src.getGrowthStage())) + "）");
        dst.setVersion(nextVersion(src.getVariety(), src.getGrowthStage()));
        dst.setStatus(PrescriptionStatus.DRAFT);
        dst.setEnabled(false); // 新副本默认不启用，配置确认后再发布
        dst.setEnvTargetsJson(src.getEnvTargetsJson());
        dst.setOperationsJson(src.getOperationsJson());
        dst.setRemark("复制自 v" + src.getVersion() + (src.getRemark() == null ? "" : "；" + src.getRemark()));
        dst.setCopiedFromId(src.getId());
        dst.setCreatedBy(createdBy == null ? "admin" : createdBy);
        return repository.save(dst);
    }

    /** 发布：草稿 → 已发布；同品种+生育期的旧发布版自动归档（同时只允许一个生效版本） */
    @Transactional
    public Prescription publish(Long id) {
        Prescription p = get(id);
        if (p.getStatus() != PrescriptionStatus.DRAFT) {
            throw new IllegalStateException("仅草稿可发布");
        }
        for (Prescription old : repository.findByVarietyAndGrowthStageOrderByVersionDesc(
                p.getVariety(), p.getGrowthStage())) {
            if (old.getStatus() == PrescriptionStatus.PUBLISHED && !old.getId().equals(id)) {
                old.setStatus(PrescriptionStatus.ARCHIVED);
                old.setEnabled(false);
                repository.save(old);
            }
        }
        p.setStatus(PrescriptionStatus.PUBLISHED);
        p.setEnabled(true);
        p.setPublishedAt(java.time.LocalDateTime.now());
        return repository.save(p);
    }

    /** 归档（停用），历史版本留存可查 */
    @Transactional
    public Prescription archive(Long id) {
        Prescription p = get(id);
        p.setStatus(PrescriptionStatus.ARCHIVED);
        p.setEnabled(false);
        return repository.save(p);
    }

    private int nextVersion(String variety, String growthStage) {
        return repository.findFirstByVarietyAndGrowthStageOrderByVersionDesc(variety, growthStage)
                .map(x -> x.getVersion() + 1)
                .orElse(1);
    }
}
