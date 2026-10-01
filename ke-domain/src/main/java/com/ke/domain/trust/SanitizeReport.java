package com.ke.domain.trust;

import java.util.List;

/**
 * 引用校验报告（Task 19）：sections 为校验后的段落（不可变视图，与入参同序同长，
 * **保留 null 段**——索引语义以入参为准）；strippedCitations 为全报告级去重后的越界引用 id，
 * downgradedSectionIndexes 为被降级段落的入参原索引（FACT 空引用 → SYNTHESIS）。
 * 展示层（调用方）自行决定是否过滤 null 段——信任层只报告事实。
 */
public record SanitizeReport(List<SanitizedSection> sections,
                             List<Long> strippedCitations,
                             List<Integer> downgradedSectionIndexes) {
}
