package com.ke.service.agent;

import com.ke.domain.enums.AgentRunStatus;
import com.ke.domain.enums.CardStatus;
import com.ke.domain.trust.CitationSanitizer;
import com.ke.domain.trust.SanitizeReport;
import com.ke.domain.trust.SanitizedSection;
import com.ke.infra.entity.AgentRunEntity;
import com.ke.infra.entity.ArtifactEntity;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.CitationEntity;
import com.ke.infra.entity.PathNodeEntity;
import com.ke.infra.mapper.AgentRunMapper;
import com.ke.infra.mapper.ArtifactMapper;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.CitationMapper;
import com.ke.infra.mapper.PathNodeMapper;
import com.ke.service.agent.dto.ExplainOutput;
import com.ke.service.agent.dto.ExplainResult;
import com.ke.service.common.BadRequestException;
import com.ke.service.explore.SessionService;
import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 讲解服务流水线（FR-S02 / 02 §5.2）：受限检索 → 提示词组装 → LLM → Jackson 绑定
 * {@link ExplainOutput}（失败自动重试 1 次）→ 引用校验（FR-S05，{@link CitationSanitizer}：
 * 越界引用剔除 + FACT 空引用降级，剥离留痕 agent_run.error 不置 FAILED）→ artifact(EXPLAIN) +
 * citation(object_type=agent_run) 落库 → 回写 agent_run(DONE, artifact_ids)。任何异常置 FAILED(error=消息)。
 *
 * 线程模型：submit 前置校验在 controller 线程（会话属主/卡已发布，钉子①——userId 在 submit 时
 * 捕获进 input_json，虚拟线程不再依赖 SecurityContext）；执行走 agentExecutor 虚拟线程（自代理
 * 调 @Async，同类 this 调用会退化为同步）。配额/超时/心跳循环留 Task 20（Resilience4j），
 * 本实现仅 RUNNING 时 touch 一次。
 */
@Service
@PropertySource(value = "classpath:explain-prompts.properties", encoding = "UTF-8")
public class ExplainService {

    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);

    static final String SERVICE_TYPE = "EXPLAIN";
    private static final Set<String> LEVELS = Set.of("SIMPLE", "DEEP", "CHILD");
    /** 卡片内容进入提示词的截断上限（检索文本预算在 RetrievalService） */
    private static final int CARD_CONTENT_LIMIT = 2000;
    private static final int MAX_LLM_ATTEMPTS = 2;

    private final AgentRunMapper runs;
    private final CardMapper cards;
    private final CardVersionMapper cardVersions;
    private final PathNodeMapper pathNodes;
    private final SessionService sessions;
    private final RetrievalService retrieval;
    private final ArtifactMapper artifacts;
    private final CitationMapper citations;
    private final LlmGateway llm;
    private final ObjectMapper objectMapper;
    /** 自代理：submit 必须经代理调 executeExplain，同类 this 调用会让 @Async 失效退化为同步 */
    private final ExplainService self;

    @Value("${ke.llm.generator-model:unknown}")
    private String generatorModel;

    @Value("${explain.prompt.common}")
    private String commonPrompt;
    @Value("${explain.prompt.SIMPLE}")
    private String simplePrompt;
    @Value("${explain.prompt.DEEP}")
    private String deepPrompt;
    @Value("${explain.prompt.CHILD}")
    private String childPrompt;
    @Value("${explain.prompt.no-material}")
    private String noMaterialPrompt;

    public ExplainService(AgentRunMapper runs, CardMapper cards, CardVersionMapper cardVersions,
                          PathNodeMapper pathNodes, SessionService sessions, RetrievalService retrieval,
                          ArtifactMapper artifacts, CitationMapper citations, LlmGateway llm,
                          ObjectMapper objectMapper, @Lazy ExplainService self) {
        this.runs = runs;
        this.cards = cards;
        this.cardVersions = cardVersions;
        this.pathNodes = pathNodes;
        this.sessions = sessions;
        this.retrieval = retrieval;
        this.artifacts = artifacts;
        this.citations = citations;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    /** agent_run.input_json 形状（userId 冗余进 JSON：GET 属主判定与 Task 21 归一都从这里取） */
    public record ExplainInput(Long userId, Long sessionId, Long nodeId,
                               Long cardVersionId, String question, String level) {
    }

    // ---------- 提交（controller 线程，同步前置校验） ----------

    /**
     * 提交讲解运行：校验（question/level、会话属主 404/403、节点归属、卡已发布 400）→
     * 落库 QUEUED（input_json={userId,sessionId,nodeId,cardVersionId,question,level}）→
     * 异步执行，立即返回 runId（POST → 202）。
     */
    public Long explain(long userId, Long cardVersionId, String question, String level,
                        Long sessionId, Long nodeId) {
        if (cardVersionId == null) {
            throw new BadRequestException("cardVersionId 不能为空");
        }
        if (question == null || question.isBlank()) {
            throw new BadRequestException("question 不能为空");
        }
        if (level == null || !LEVELS.contains(level)) {
            throw new BadRequestException("讲解度仅支持 SIMPLE/DEEP/CHILD");
        }
        if (nodeId != null && sessionId == null) {
            // nodeId 必随会话：否则下面的属主校验块整块被跳过，run 可附着到任意人的节点上
            //（跨租户污染 + 存在性泄露，review P5-18）
            throw new BadRequestException("nodeId 必须随会话提交");
        }
        if (sessionId != null) {
            sessions.ownedSession(userId, sessionId);
            if (nodeId != null) {
                PathNodeEntity node = pathNodes.selectById(nodeId);
                // 节点不属于本会话（别人的/不存在）一律 400，不区分泄露（与 addNode 父节点同则）
                if (node == null || !node.getSessionId().equals(sessionId)) {
                    throw new BadRequestException("节点不属于该会话");
                }
            }
        }
        CardVersionEntity version = cardVersions.selectById(cardVersionId);
        if (version == null) {
            throw new BadRequestException("卡片版本不存在");
        }
        CardEntity card = cards.selectById(version.getCardId());
        if (card == null || !CardStatus.PUBLISHED.name().equals(card.getStatus())) {
            throw new BadRequestException("卡片未发布");
        }

        ExplainInput input = new ExplainInput(userId, sessionId, nodeId, cardVersionId, question, level);
        AgentRunEntity run = new AgentRunEntity();
        run.setSessionId(sessionId);
        run.setNodeId(nodeId);
        run.setServiceType(SERVICE_TYPE);
        run.setInputJson(json(input));
        run.setStatus(AgentRunStatus.QUEUED.name());
        runs.insert(run);
        self.executeExplain(run.getId(), input);
        return run.getId();
    }

    // ---------- 执行（agentExecutor 虚拟线程） ----------

    @Async("agentExecutor")
    public void executeExplain(long runId, ExplainInput input) {
        long start = System.currentTimeMillis();
        try {
            AgentRunEntity run = runs.selectById(runId);
            if (run == null) {
                return;
            }
            run.setStatus(AgentRunStatus.RUNNING.name());
            runs.updateById(run);
            runs.touch(runId); // Task 20 接 Resilience4j 后改为执行中循环心跳

            Map<Long, String> materials = retrieval.retrieve(input.cardVersionId());
            String context = input.sessionId() == null ? "" : sessions.contextSummary(input.sessionId());
            String system = systemPrompt(input.level(), materials.isEmpty());
            String user = userPrompt(input, materials, context);

            ExplainOutput output = null;
            Exception last = null;
            for (int attempt = 0; attempt < MAX_LLM_ATTEMPTS && output == null; attempt++) {
                try {
                    String raw = llm.complete(new ChatCommand(system, user, ModelTier.GENERATOR));
                    output = objectMapper.readValue(stripFences(raw), ExplainOutput.class);
                } catch (Exception e) {
                    last = e; // Jackson 绑定失败 → 重试 = 再调一次 LLM（共 2 次）
                }
            }
            if (output == null) {
                throw new IllegalStateException("讲解输出解析失败（已重试 1 次）: "
                        + (last == null ? "未知原因" : last.getMessage()));
            }
            finish(runId, start, input, output, materials);
        } catch (Exception e) {
            log.warn("explain run {} failed: {}", runId, e.getMessage());
            fail(runId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** 终态回写：artifact(EXPLAIN, content_json={output,sources}) + citation 落表 + DONE/artifact_ids */
    private void finish(long runId, long start, ExplainInput input,
                        ExplainOutput output, Map<Long, String> materials) {
        // FR-S05/R2 引用校验（Task 19）：LLM 绑定成功后、落 artifact/citation 之前——
        // 越界引用剔除（只剩本次检索资料内的 id），FACT 空引用降级 SYNTHESIS（段落档位自表达，不加顶层标志）
        SanitizeReport report = CitationSanitizer.sanitize(
                toSanitizedSections(output.sections()), materials.keySet());
        ExplainOutput sanitized = withSections(output, report.sections());

        Long artifactId = null;
        if (input.sessionId() != null) {
            // artifact.session_id 非空（V1 约束）：无会话的讲解 run 不落 artifact，仅回状态
            ArtifactEntity artifact = new ArtifactEntity();
            artifact.setSessionId(input.sessionId());
            artifact.setType(SERVICE_TYPE);
            artifact.setContentJson(json(new ExplainResult(sanitized, materials)));
            artifact.setStatus("DRAFT");
            artifacts.insert(artifact);
            artifactId = artifact.getId();
            linkCitations(runId, sanitized);
        }
        AgentRunEntity done = runs.selectById(runId);
        done.setStatus(AgentRunStatus.DONE.name());
        done.setLatencyMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start));
        done.setModel(generatorModel);
        done.setArtifactIds(artifactId == null ? json(List.of()) : json(List.of(artifactId)));
        if (!report.strippedCitations().isEmpty()) {
            // 旁路留痕（不置 FAILED）：LLM 幻觉引用被拦截的事实挂在 error 字段
            done.setError("引用校验:剥离 " + report.strippedCitations().size() + " 个越界引用");
        }
        runs.updateById(done);
    }

    /** 讲解段 → 校验器入参（同形映射：ke-domain 不依赖 service DTO） */
    private static List<SanitizedSection> toSanitizedSections(List<ExplainOutput.Section> sections) {
        if (sections == null) {
            return List.of();
        }
        List<SanitizedSection> mapped = new ArrayList<>(sections.size());
        for (ExplainOutput.Section section : sections) {
            mapped.add(section == null ? null
                    : new SanitizedSection(section.body(), section.claimType(), section.citations()));
        }
        return mapped;
    }

    /** 校验后的段落回填讲解输出（summary/openQuestions/evidenceGaps 原样） */
    private static ExplainOutput withSections(ExplainOutput output, List<SanitizedSection> sections) {
        List<ExplainOutput.Section> mapped = new ArrayList<>(sections.size());
        for (SanitizedSection section : sections) {
            mapped.add(section == null ? null
                    : new ExplainOutput.Section(section.body(), section.claimType(), section.citations()));
        }
        return new ExplainOutput(output.summary(), mapped, output.openQuestions(), output.evidenceGaps());
    }

    /**
     * 服务输出的 citation 行落 citation 表（object_type=agent_run，溯源用）。
     * 入参 sections 已过 {@link CitationSanitizer}（只剩检索允许集合内的 assetId，LLM 幻觉引用被剥离），
     * 按 sanitized sections 的并集逐 run 去重落表。
     */
    private void linkCitations(long runId, ExplainOutput output) {
        if (output.sections() == null) {
            return;
        }
        Set<Long> linked = new HashSet<>();
        for (ExplainOutput.Section section : output.sections()) {
            if (section == null || section.citations() == null) {
                continue;
            }
            for (Long assetId : section.citations()) {
                if (assetId == null || !linked.add(assetId)) {
                    continue;
                }
                CitationEntity citation = new CitationEntity();
                citation.setAssetId(assetId);
                citation.setObjectType("agent_run");
                citation.setObjectId(runId);
                citations.insert(citation);
            }
        }
    }

    private void fail(long runId, String message) {
        try {
            AgentRunEntity run = runs.selectById(runId);
            run.setStatus(AgentRunStatus.FAILED.name());
            run.setError(message);
            runs.updateById(run);
        } catch (Exception e) {
            log.error("explain run {} 终态回写失败", runId, e);
        }
    }

    // ---------- 提示词组装 ----------

    /** system = 公共前缀 + 档位段；无检索资料时追加「谨慎作答全 GEN」段 */
    private String systemPrompt(String level, boolean noMaterial) {
        String tier = switch (level == null ? "SIMPLE" : level) {
            case "DEEP" -> deepPrompt;
            case "CHILD" -> childPrompt;
            default -> simplePrompt;
        };
        String system = commonPrompt + "\n" + tier;
        if (noMaterial) {
            system += "\n" + noMaterialPrompt;
        }
        return system;
    }

    /** user = 问题 + 卡片（标题+内容截 2000）+ 会话上下文摘要 + 受限检索文本 */
    private String userPrompt(ExplainInput input, Map<Long, String> materials, String context) {
        StringBuilder sb = new StringBuilder("问题:").append(input.question()).append('\n');
        CardVersionEntity version = cardVersions.selectById(input.cardVersionId());
        if (version != null) {
            CardEntity card = cards.selectById(version.getCardId());
            if (card != null) {
                sb.append("讲解对象卡:").append(card.getTitle()).append('\n');
            }
            sb.append("卡片内容:").append(truncate(version.getContentJson())).append('\n');
        }
        sb.append("会话上下文:").append(context.isBlank() ? "（无）" : context).append('\n');
        sb.append("可用资料:\n");
        if (materials.isEmpty()) {
            sb.append("（无）");
        } else {
            materials.forEach((assetId, text) -> sb.append("[asset ").append(assetId).append("] ").append(text).append('\n'));
        }
        return sb.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > CARD_CONTENT_LIMIT ? text.substring(0, CARD_CONTENT_LIMIT) : text;
    }

    /** 兼容真实网关返回的 markdown 代码围栏（```json ... ```） */
    private static String stripFences(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLineBreak = text.indexOf('\n');
            if (firstLineBreak > 0) {
                text = text.substring(firstLineBreak + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        return text;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
