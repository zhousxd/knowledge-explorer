package com.ke.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.infra.entity.AgentRunEntity;
import com.ke.infra.entity.ArtifactEntity;
import com.ke.infra.entity.PathNodeEntity;
import com.ke.infra.entity.SessionEntity;
import com.ke.infra.mapper.AgentRunMapper;
import com.ke.infra.mapper.ArtifactMapper;
import com.ke.infra.mapper.PathNodeMapper;
import com.ke.infra.mapper.SessionMapper;
import com.ke.service.common.ApiResponse;
import com.ke.service.common.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体运行端点族（/api/agent/runs，FR-S02/S04）。恒需认证（匿名 401 由 SecurityConfig 兜底）：
 * POST 提交讲解（202 {runId}，异步执行）、GET {id} 轮询（属主校验：有会话走会话属主，
 * 无会话 run 解析 input_json.userId 对比——不冗余建列，钉子②决策；DONE 响应附 submitContext
 * =input_json 白名单投影，供前端刷新后重建追问/重试，review P5-FIX）、GET ?nodeId= 节点 run
 * 简版列表（Task 22 结果页消费）。属主不符 403，不存在 404。
 */
@RestController
public class RunController {

    private final AgentRunMapper runs;
    private final SessionMapper sessions;
    private final PathNodeMapper pathNodes;
    private final ArtifactMapper artifacts;
    private final ObjectMapper objectMapper;
    private final ExplainService explain;

    public RunController(AgentRunMapper runs, SessionMapper sessions, PathNodeMapper pathNodes,
                         ArtifactMapper artifacts, ObjectMapper objectMapper, ExplainService explain) {
        this.runs = runs;
        this.sessions = sessions;
        this.pathNodes = pathNodes;
        this.artifacts = artifacts;
        this.objectMapper = objectMapper;
        this.explain = explain;
    }

    /**
     * serviceType 可选（Task 23）：缺省 EXPLAIN；COMPARE 走比较分支（提示词/输出形状切换，
     * 产出 COMPARE_CARD artifact）。白名单校验在 ExplainService.explain。
     */
    public record RunRequest(Long cardVersionId, Long sessionId, Long nodeId, Long parentRunId,
                             String question, String level, String serviceType) {
    }

    /** serviceType 随响应带出（Task 23：前端 DONE 态按 EXPLAIN/COMPARE 分流结果渲染） */
    public record RunView(long runId, String status, String serviceType, String model, Integer latencyMs,
                          String error, JsonNode artifact, JsonNode submitContext) {
    }

    /** 节点 run 简版行（Task 22 结果页列表） */
    public record RunSummary(long runId, String status) {
    }

    /** 提交讲解/比较运行 → 202 {runId}；前置校验在 ExplainService.explain（serviceType 白名单/会话属主/卡已发布） */
    @PostMapping("/api/agent/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> submit(@RequestBody RunRequest req) {
        Long runId = explain.explain(currentUserId(), req.cardVersionId(), req.question(),
                req.level(), req.sessionId(), req.nodeId(), req.parentRunId(), req.serviceType());
        return ApiResponse.ok(Map.of("runId", runId));
    }

    /** 运行详情（轮询）：终态 DONE 时带 artifact.content_json 对象；submitContext 供前端刷新后重建追问/重试 */
    @GetMapping("/api/agent/runs/{id}")
    public ApiResponse<RunView> get(@PathVariable long id) {
        AgentRunEntity run = runs.selectById(id);
        if (run == null) {
            throw new NotFoundException("运行不存在");
        }
        requireRunOwner(currentUserId(), run);
        return ApiResponse.ok(new RunView(run.getId(), run.getStatus(), run.getServiceType(), run.getModel(),
                run.getLatencyMs(), run.getError(), artifactOf(run), submitContextOf(run)));
    }

    /** 该节点的讲解 run 列表（id 升序；节点不存在 404、非属主 403） */
    @GetMapping("/api/agent/runs")
    public ApiResponse<List<RunSummary>> byNode(@RequestParam long nodeId) {
        PathNodeEntity node = pathNodes.selectById(nodeId);
        if (node == null) {
            throw new NotFoundException("节点不存在");
        }
        SessionEntity session = sessions.selectById(node.getSessionId());
        if (session == null || !session.getUserId().equals(currentUserId())) {
            throw new AccessDeniedException("无权访问该会话");
        }
        List<RunSummary> items = runs.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                        .eq(AgentRunEntity::getNodeId, nodeId)
                        .orderByAsc(AgentRunEntity::getId))
                .stream().map(r -> new RunSummary(r.getId(), r.getStatus())).toList();
        return ApiResponse.ok(items);
    }

    // ---------- 内部 ----------

    /**
     * 提交上下文读模型（review P5-FIX：结果页刷新丢路由 state 后重建追问/重试用）。
     * 从 input_json 投影白名单字段——不回显整个 input_json（userId 冗余其中，最小暴露面）；
     * 仅属主可达（GET 已过 {@link #requireRunOwner}），非属主场景不可见。
     * 解析失败（旧行/损坏 JSON）或缺关键字段 → null（jackson non_null 整键省略）。
     */
    public record SubmitContext(Long cardVersionId, Long sessionId, Long nodeId,
                                String serviceType, Long parentRunId, String question) {
    }

    private JsonNode submitContextOf(AgentRunEntity run) {
        if (run.getInputJson() == null || run.getInputJson().isBlank()) {
            return null;
        }
        try {
            ExplainService.ExplainInput input =
                    objectMapper.readValue(run.getInputJson(), ExplainService.ExplainInput.class);
            if (input.cardVersionId() == null) {
                return null; // 无 cardVersionId 的上下文不足以重建提交，视同无效
            }
            return objectMapper.valueToTree(new SubmitContext(input.cardVersionId(), input.sessionId(),
                    input.nodeId(), input.serviceType(), input.parentRunId(), input.question()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 属主三分：有会话 → 会话属主（会话被删/非属主一律 403 不区分泄露）；
     * 无会话 legacy run → input_json.userId 对比（ExplainInput 解析失败视同拒绝）。
     */
    private void requireRunOwner(long userId, AgentRunEntity run) {
        if (run.getSessionId() != null) {
            SessionEntity session = sessions.selectById(run.getSessionId());
            if (session == null || !session.getUserId().equals(userId)) {
                throw new AccessDeniedException("无权访问该运行");
            }
            return;
        }
        Long owner = userIdOf(run.getInputJson());
        if (owner == null || owner != userId) {
            throw new AccessDeniedException("无权访问该运行");
        }
    }

    private Long userIdOf(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(inputJson, ExplainService.ExplainInput.class).userId();
        } catch (Exception e) {
            return null;
        }
    }

    /** DONE 且 artifact_ids 首个 id 可解析 → artifact.content_json 对象；否则 null（序列化省略） */
    private JsonNode artifactOf(AgentRunEntity run) {
        Long artifactId = firstArtifactId(run);
        if (artifactId == null) {
            return null;
        }
        ArtifactEntity artifact = artifacts.selectById(artifactId);
        if (artifact == null) {
            return null;
        }
        try {
            return objectMapper.readTree(artifact.getContentJson());
        } catch (Exception e) {
            return null;
        }
    }

    private Long firstArtifactId(AgentRunEntity run) {
        String idsJson = run.getArtifactIds();
        if (!"DONE".equals(run.getStatus()) || idsJson == null || idsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode ids = objectMapper.readTree(idsJson);
            if (ids.isArray() && !ids.isEmpty() && ids.get(0).canConvertToLong()) {
                return ids.get(0).longValue();
            }
        } catch (Exception ignored) {
            // artifact_ids 损坏视同无 artifact
        }
        return null;
    }

    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
