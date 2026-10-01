package com.ke.domain.trust;

import java.util.List;

/**
 * 引用校验报告（Task 19）：sections 为校验后的段落（顺序与入参一致），
 * strippedCitations 为全报告级去重后的越界引用 id，downgradedSectionIndexes 为
 * 被降级段落的入参原索引（FACT 空引用 → SYNTHESIS）。
 */
public record SanitizeReport(List<SanitizedSection> sections,
                             List<Long> strippedCitations,
                             List<Integer> downgradedSectionIndexes) {
}
