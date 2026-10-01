package com.ke.service.entry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.KnowledgeAssetEntity;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.KnowledgeAssetMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡片当前版本的授权资料集（FR-N07 白名单的数据面，Task 28 从 {@link EntryDraftService} 抽出共用）：
 * card_version.sources[].assetId → knowledge_asset（license_expire 为空或 ≥ 今天，与
 * RetrievalService 同一授权过滤），LinkedHashMap 保 sources 顺序（id→标题）。
 * 解析失败/缺失资产静默跳过——受限集合只缩不涨。草稿抽取（提示词资料清单 + 校验 allowedAssetScope）
 * 与入口保存（校验 allowedAssetScope，Task 28）共用同一份授权判定，杜绝两处口径漂移。
 */
@Component
public class EntryAuthorizedAssets {

    private final CardVersionMapper cardVersions;
    private final KnowledgeAssetMapper assets;
    private final ObjectMapper objectMapper;

    public EntryAuthorizedAssets(CardVersionMapper cardVersions, KnowledgeAssetMapper assets,
                                 ObjectMapper objectMapper) {
        this.cardVersions = cardVersions;
        this.assets = assets;
        this.objectMapper = objectMapper;
    }

    /**
     * 当前卡版本的授权资产清单（id→标题，保 sources 顺序）：card_version_id 为空/版本缺失/
     * 无挂接一律空集（服务入口将因 assetScope 非空校验被拦，文案引导先去挂接知识单元）。
     */
    public Map<Long, String> of(Long cardVersionId) {
        if (cardVersionId == null) {
            return Map.of();
        }
        CardVersionEntity version = cardVersions.selectById(cardVersionId);
        List<Long> assetIds = assetIdsOf(version == null ? null : version.getSources());
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeAssetEntity> rows = assets.selectList(new LambdaQueryWrapper<KnowledgeAssetEntity>()
                .in(KnowledgeAssetEntity::getId, assetIds)
                .and(w -> w.isNull(KnowledgeAssetEntity::getLicenseExpire)
                        .or().ge(KnowledgeAssetEntity::getLicenseExpire, LocalDate.now())));
        Map<Long, String> titles = new LinkedHashMap<>();
        for (KnowledgeAssetEntity asset : rows) {
            titles.put(asset.getId(), asset.getTitle());
        }
        Map<Long, String> ordered = new LinkedHashMap<>();
        for (Long assetId : assetIds) {
            String title = titles.get(assetId);
            if (title != null) {
                ordered.put(assetId, title);
            }
        }
        return ordered;
    }

    /** sources JSON 数组 → 非空 assetId（去重、保序）；非法 JSON 一律当无挂接 */
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
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
