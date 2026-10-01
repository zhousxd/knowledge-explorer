package com.ke.service.agent.post;

import com.ke.service.agent.dto.ExplainOutput;
import com.ke.service.agent.dto.ExplainResult;

import java.util.Map;

/**
 * 生成内容显著标识（R8 合规）：在落 artifact 前给 EXPLAIN content_json 顶层追加
 * {@link AgentLabels#DISCLAIMER}（后处理链最后一步，前端 Task 22 渲染）。
 */
public final class ClaimLabelAppender {

    private ClaimLabelAppender() {
    }

    /** 组装带生成标识与审计旁注的 artifact content_json 形状 */
    public static ExplainResult label(ExplainOutput output, Map<Long, String> sources, RunMetrics.Audit audit) {
        return new ExplainResult(output, sources, AgentLabels.DISCLAIMER, audit);
    }
}
