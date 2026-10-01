package com.ke.service.agent.dto;

import java.util.Map;

/**
 * EXPLAIN artifact 的 content_json 形状（Task 22 结果页渲染依据）：
 * output 为讲解结构化输出，sources 为检索快照（assetId→检索文本，序列化为字符串键）。
 */
public record ExplainResult(ExplainOutput output, Map<Long, String> sources) {
}
