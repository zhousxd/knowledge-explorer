package com.ke.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.domain.enums.AgentRunStatus;
import com.ke.infra.entity.AgentRunEntity;
import com.ke.infra.entity.ArtifactEntity;
import com.ke.infra.mapper.AgentRunMapper;
import com.ke.infra.mapper.ArtifactMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 未决疑问清单（FR-E09 / Task 24）：聚合某会话全部讲解（service_type=EXPLAIN 且 DONE）run 的
 * artifact.output.openQuestions——讲解服务的结构化输出本就落库复用（01 FR-E09「落库复用」口径），
 * 这里只做扁平读取，不再调 LLM。run 按 id 逆时序（后讲的排前），不去重（保留各讲解原貌，
 * 成果整理的 LLM 合并走 {@link SummaryService} 材料链）。
 *
 * <p>{@link #collect(long)} 不做属主校验：调用方先做——会话端点（GET /api/sessions/{id}/open-questions）
 * 走 {@code SessionService.ownedSession}，断点续探计数（{@code SessionService.latest}）本身就是
 * 按属主查询。刻意不依赖 SessionService，避免与 SessionService.latest 的计数依赖成环。
 */
@Service
public class OpenQuestionService {

    /** 未决疑问行：runId=来源讲解 run，collectedAt=该 run 终态时间（updated_at；V1 无自动更新触发器，当前等于入库时刻） */
    public record OpenQuestion(long runId, String question, OffsetDateTime collectedAt) {
    }

    private final AgentRunMapper runs;
    private final ArtifactMapper artifacts;
    private final ObjectMapper objectMapper;

    public OpenQuestionService(AgentRunMapper runs, ArtifactMapper artifacts, ObjectMapper objectMapper) {
        this.runs = runs;
        this.artifacts = artifacts;
        this.objectMapper = objectMapper;
    }

    /**
     * 聚合会话全部讲解 run 的未决疑问（扁平、逆时序）；artifact 缺失/损坏的 run 跳过（视同无疑问）。
     */
    @Transactional(readOnly = true)
    public List<OpenQuestion> collect(long sessionId) {
        List<AgentRunEntity> doneRuns = runs.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getSessionId, sessionId)
                .eq(AgentRunEntity::getServiceType, ExplainService.SERVICE_TYPE)
                .eq(AgentRunEntity::getStatus, AgentRunStatus.DONE.name())
                .orderByDesc(AgentRunEntity::getId));
        List<OpenQuestion> questions = new ArrayList<>();
        for (AgentRunEntity run : doneRuns) {
            OffsetDateTime collectedAt = run.getUpdatedAt() != null ? run.getUpdatedAt() : run.getCreatedAt();
            for (String question : openQuestionsOf(run)) {
                questions.add(new OpenQuestion(run.getId(), question, collectedAt));
            }
        }
        return questions;
    }

    /** run 的 artifact_ids[0] → artifact.content_json.output.openQuestions（损坏视同无疑问） */
    private List<String> openQuestionsOf(AgentRunEntity run) {
        String idsJson = run.getArtifactIds();
        if (!AgentRunStatus.DONE.name().equals(run.getStatus()) || idsJson == null || idsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode ids = objectMapper.readTree(idsJson);
            if (!ids.isArray() || ids.isEmpty() || !ids.get(0).canConvertToLong()) {
                return List.of();
            }
            ArtifactEntity artifact = artifacts.selectById(ids.get(0).longValue());
            if (artifact == null || artifact.getContentJson() == null) {
                return List.of();
            }
            JsonNode output = objectMapper.readTree(artifact.getContentJson()).get("output");
            JsonNode questions = output == null ? null : output.get("openQuestions");
            if (questions == null || !questions.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode question : questions) {
                if (question != null && !question.isNull() && !question.asText().isBlank()) {
                    result.add(question.asText());
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
