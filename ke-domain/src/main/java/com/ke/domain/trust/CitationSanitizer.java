package com.ke.domain.trust;

import com.ke.domain.enums.ClaimType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 引用校验降级器（FR-S05 / R2，Task 19）：对 LLM 讲解输出做落库前的最后一道可信校验。
 *
 * <p>规则：逐段检查 citations，∈ allowedAssetIds（本次受限检索的资料集合）保留，否则剔除；
 * 剔除后 FACT 段若无有效引用 → 降级 SYNTHESIS（FACT 的成立条件就是有可查证出处，
 * 无论原本为空还是被剥离至空）；SYNTHESIS/GEN 带空引用保持原档（本就是综合/生成）。
 *
 * <p>纯函数：零 IO、零 Spring；不修改入参（citations 重组为新列表，入参 list/section 原样）。
 * 调用方（ExplainPipeline）在 LLM 绑定成功后、落 artifact/citation 之前调用，
 * strippedCitations 非空时旁路记入 agent_run.error（不置 FAILED）。
 */
public final class CitationSanitizer {

    private CitationSanitizer() {
    }

    /**
     * @param sections        讲解段落（可为 null，视为无段落）；允许含 null 元素（原位保留，索引不漂移）
     * @param allowedAssetIds 允许引用的 assetId 集合（本次检索 map.keySet()；null 视为空集）
     * @return 报告：sections 与入参同序同长；strippedCitations 全报告级去重（出现序）；
     * downgradedSectionIndexes 为降级段的入参原索引
     */
    public static SanitizeReport sanitize(List<SanitizedSection> sections, Set<Long> allowedAssetIds) {
        if (sections == null || sections.isEmpty()) {
            return new SanitizeReport(List.of(), List.of(), List.of());
        }
        Set<Long> allowed = allowedAssetIds == null ? Set.of() : allowedAssetIds;
        List<SanitizedSection> sanitized = new ArrayList<>(sections.size());
        Set<Long> stripped = new LinkedHashSet<>();
        List<Integer> downgraded = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            SanitizedSection section = sections.get(i);
            if (section == null) {
                sanitized.add(null);
                continue;
            }
            List<Long> kept = new ArrayList<>();
            if (section.citations() != null) {
                for (Long assetId : section.citations()) {
                    if (assetId == null) {
                        continue; // 畸形 null id：静默丢弃（非「越界引用」，不计入 stripped）
                    }
                    if (allowed.contains(assetId)) {
                        kept.add(assetId);
                    } else {
                        stripped.add(assetId);
                    }
                }
            }
            ClaimType claimType = section.claimType();
            if (claimType == ClaimType.FACT && kept.isEmpty()) {
                claimType = ClaimType.SYNTHESIS;
                downgraded.add(i);
            }
            sanitized.add(new SanitizedSection(section.body(), claimType, kept));
        }
        return new SanitizeReport(List.copyOf(sanitized), List.copyOf(stripped), List.copyOf(downgraded));
    }
}
