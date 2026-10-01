package com.ke.service.agent.dto;

import com.ke.domain.enums.ClaimType;

import java.util.List;

/**
 * 成果整理结构化输出（FR-S03 / Task 24）：LLM 返回 JSON 的绑定目标。
 * keyFindings 的 citations 直接引知识单元 assetId（与讲解段同语义），落库前过
 * {@code CitationSanitizer}（allowed=材料资产全集，FACT 空引降级 SYNTHESIS 同规则）；
 * openQuestions 提示词要求对材料里各讲解遗留的开放问题去重合并，服务端落库前再兜底去重一次。
 */
public record SummaryOutput(List<KeyFinding> keyFindings,
                            List<String> openQuestions) {

    /** claimType 取 FACT/SYNTHESIS/GEN（三档可信 ●◐○）；citations 为所引知识单元 assetId */
    public record KeyFinding(String body, ClaimType claimType, List<Long> citations) {
    }
}
