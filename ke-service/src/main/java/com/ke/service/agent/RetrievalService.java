package com.ke.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.KnowledgeAssetEntity;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.KnowledgeAssetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受限检索（FR-S02，02 §5.2）：讲解只允许引用卡片挂接的知识单元。
 * card_version.sources[].assetId 非空集合 → 批量查 knowledge_asset
 * （授权过滤：license_expire 为空或 ≥ today）→ assetId→检索文本（标题+摘录，
 * 每条截 800 字、总预算 4000 字符）。无挂接资产返回空 map（流水线继续，
 * 由提示词告知「无检索资料」），检索失败不阻断讲解——受限集合只是空集。
 */
@Service
public class RetrievalService {

    /** 单条检索文本上限（标题行之后摘录截断） */
    static final int PER_ASSET_LIMIT = 800;
    /** 全部检索文本的总字符预算，超出即截断（提示词长度护栏） */
    static final int TOTAL_BUDGET = 4000;

    private final CardVersionMapper cardVersions;
    private final KnowledgeAssetMapper assets;
    private final ObjectMapper objectMapper;

    public RetrievalService(CardVersionMapper cardVersions, KnowledgeAssetMapper assets,
                            ObjectMapper objectMapper) {
        this.cardVersions = cardVersions;
        this.assets = assets;
        this.objectMapper = objectMapper;
    }

    /**
     * 给定卡片版本的受限检索文本：assetId → 「《标题》\n摘录（≤800 字）」。
     * 无挂接/授权过期/资产缺失的都不出现；顺序按 sources 出现顺序（LinkedHashMap 保序）。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> retrieve(Long cardVersionId) {
        if (cardVersionId == null) {
            return Map.of();
        }
        CardVersionEntity version = cardVersions.selectById(cardVersionId);
        if (version == null) {
            return Map.of();
        }
        List<Long> assetIds = assetIdsOf(version.getSources());
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        // 授权过滤：license_expire 为空（永久）或 ≥ 今天；缺失的资产静默跳过（受限集合只缩不涨）
        List<KnowledgeAssetEntity> rows = assets.selectList(new LambdaQueryWrapper<KnowledgeAssetEntity>()
                .in(KnowledgeAssetEntity::getId, assetIds)
                .and(w -> w.isNull(KnowledgeAssetEntity::getLicenseExpire)
                        .or().ge(KnowledgeAssetEntity::getLicenseExpire, LocalDate.now())));

        Map<Long, KnowledgeAssetEntity> byId = rows.stream()
                .collect(LinkedHashMap::new, (m, a) -> m.put(a.getId(), a), Map::putAll);
        StringBuilder combined = new StringBuilder();
        Map<Long, String> result = new LinkedHashMap<>();
        for (Long assetId : assetIds) {
            KnowledgeAssetEntity asset = byId.get(assetId);
            if (asset == null) {
                continue;
            }
            int remaining = TOTAL_BUDGET - combined.length();
            if (remaining <= 0) {
                break;
            }
            String text = materialText(asset);
            if (text.length() > remaining) {
                text = text.substring(0, remaining);
            }
            combined.append(text);
            result.put(assetId, text);
        }
        return result;
    }

    /** sources JSON 数组 → 非空 assetId（去重、保序）；非法 JSON 一律当无挂接（检索空集不阻断讲解） */
    private List<Long> assetIdsOf(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode sources = objectMapper.readTree(sourcesJson);
            if (sources == null || !sources.isArray()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            for (JsonNode source : sources) {
                JsonNode assetId = source == null ? null : source.get("assetId");
                if (assetId != null && !assetId.isNull() && assetId.canConvertToLong()) {
                    long id = assetId.longValue();
                    if (id > 0 && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 单条检索文本：标题 + 换行 + 摘录（≤800 字）；摘录为空给占位文案 */
    private static String materialText(KnowledgeAssetEntity asset) {
        String extract = asset.getContentExtract() == null ? "" : asset.getContentExtract();
        if (extract.length() > PER_ASSET_LIMIT) {
            extract = extract.substring(0, PER_ASSET_LIMIT);
        }
        if (extract.isBlank()) {
            extract = "（该资料暂无内容摘录，仅可引用标题）";
        }
        return asset.getTitle() + "\n" + extract;
    }
}
