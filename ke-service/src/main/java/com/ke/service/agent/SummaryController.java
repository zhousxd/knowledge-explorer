package com.ke.service.agent;

import com.ke.service.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 成果整理端点（FR-S03/E07 / Task 24）：恒需认证（匿名 401 由 SecurityConfig 兜底）。
 * POST /api/agent/summarize {sessionId, nodeIds[]} → 202 {runId}（异步执行）；轮询复用既有
 * GET /api/agent/runs/{id}（契约不变，artifact 透传——REPORT 形状由前端 Task 25 分流渲染）。
 */
@RestController
public class SummaryController {

    private final SummaryService summary;

    public SummaryController(SummaryService summary) {
        this.summary = summary;
    }

    public record SummarizeRequest(Long sessionId, List<Long> nodeIds) {
    }

    /** 提交整理运行 → 202 {runId}；前置校验在 SummaryService.summarize（会话属主/节点归属/勾选上限/配额） */
    @PostMapping("/api/agent/summarize")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> summarize(@RequestBody SummarizeRequest req) {
        Long runId = summary.summarize(currentUserId(), req.sessionId(), req.nodeIds());
        return ApiResponse.ok(Map.of("runId", runId));
    }

    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
