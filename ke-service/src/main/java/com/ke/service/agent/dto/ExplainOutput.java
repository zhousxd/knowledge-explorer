package com.ke.service.agent.dto;

import com.ke.domain.enums.ClaimType;

import java.util.List;

/**
 * 讲解服务结构化输出（FR-S02/FR-E09/FR-E12）：LLM 返回 JSON 的绑定目标，也是
 * EXPLAIN artifact content_json 里 output 字段的形状。
 * 注意 citations 直接引知识单元 assetId（与卡片 sources 的 1-based 索引不同）——
 * Task 19 校验器按检索返回的 allowedAssetIds 校验；开放问题 openQuestions 落库复用 FR-E09。
 */
public record ExplainOutput(String summary,
                            List<Section> sections,
                            List<String> openQuestions,
                            List<String> evidenceGaps) {

    /** claimType 取 FACT/SYNTHESIS/GEN（三档可信 ●◐○）；citations 为所引知识单元 assetId */
    public record Section(String body, ClaimType claimType, List<Long> citations) {
    }
}
