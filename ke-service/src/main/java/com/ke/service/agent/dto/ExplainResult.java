package com.ke.service.agent.dto;

import com.ke.service.agent.post.RunMetrics;

import java.util.Map;

/**
 * EXPLAIN artifact 的 content_json 形状（Task 22 结果页渲染依据）：
 * output 为讲解结构化输出，sources 为检索快照（assetId→检索文本，序列化为字符串键）；
 * disclaimer 为 AI 生成内容标识（R8 合规，Task 20 后处理链追加），audit 为审计旁注
 * {stripped 越界引用剥离数, filtered 敏感词命中数}（Task 20，RunMetrics.Audit）。
 */
public record ExplainResult(ExplainOutput output, Map<Long, String> sources,
                            String disclaimer, RunMetrics.Audit audit) {
}
