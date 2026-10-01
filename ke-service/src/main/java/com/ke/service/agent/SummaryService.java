package com.ke.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.domain.enums.AgentRunStatus;
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
import com.ke.service.agent.dto.ReportResult;
import com.ke.service.agent.dto.SummaryOutput;
import com.ke.service.agent.post.AgentLabels;
import com.ke.service.agent.post.RunMetrics;
import com.ke.service.agent.post.SensitiveWordFilter;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.RateLimitException;
import com.ke.service.explore.SessionService;
import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import com.ke.service.quota.QuotaService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 成果整理服务（FR-S03/E07 / Task 24）：勾选跨分支节点 → 汇总为探索报告。
 * 模式复用 {@link ExplainService}（Task 23 比较通道同例）：submit 前同步校验（sessionId/nodeIds
 * 形状、会话属主 404/403、节点属于该会话 400、勾选 ≤{@value #MAX_NODES} 个、每日配额 429）→
 * agent_run(QUEUED, service_type=SUMMARIZE, input_json={userId,sessionId,nodeIds})→ 异步执行：
 * 材料组装（决策：材料=每个选中根 {@code selectSubtree} 子树内所有节点——关联卡题+question_text+
 * 该节点讲解(EXPLAIN DONE) artifact 的 sections 文本与遗留 openQuestions；资产全集=各讲解
 * artifact.sources 快照并集，即 citations 的 allowed 集合）→ GENERATOR 提示词
 * （summarize-prompts：汇总为 {keyFindings, openQuestions 去重合并}）→ 绑定 {@link SummaryOutput}
 * （keyFindings 空视同结构非法，重试 1 次仍非法 → FAILED）→ 后处理链与讲解同源：敏感词过滤
 * （FR-S10）→ {@link CitationSanitizer}（FR-S05，allowed=材料资产全集，FACT 空引降级 SYNTHESIS）
 * → openQuestions 服务端兜底去重（保序）→ artifact(type=REPORT, content_json={type,keyFindings,
 * openQuestions,branchView,sources,disclaimer,audit})，branchView 从 pathNode 树结构生成（非 LLM）
 * → citation(object_type=agent_run) 落表 → DONE。任何异常置 FAILED；LLM 超时置 TIMEOUT 不重试。
 *
 * <p>线程模型与讲解一致：submit 校验在 controller 线程（userId 捕获进 input_json），执行走
 * agentExecutor 虚拟线程（自代理经 {@code self} 调用，同类 this 调用会让 @Async 退化同步）；
 * LLM 调用包虚拟线程 future.get 超时（Task 20 等价手写，见 completeWithinTimeout 注释）。
 */
@Service
@PropertySource(value = "classpath:summarize-prompts.properties", encoding = "UTF-8")
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    static final String SERVICE_TYPE = "SUMMARIZE";
    /** REPORT 产出的 artifact 类型（artifact.type 列与 content_json.type 同值，前端 DONE 分流依据） */
    static final String REPORT_ARTIFACT_TYPE = "REPORT";
    /** 单次勾选节点上限（FR-E07 跨分支勾选；防提示词预算爆炸） */
    static final int MAX_NODES = 50;
    /** 单个讲解摘录进入材料的截断上限 */
    private static final int EXPLAIN_EXCERPT_LIMIT = 2000;
    private static final int MAX_LLM_ATTEMPTS = 2;

    /** LLM 超时护栏专用执行器：虚拟线程每任务一线（守护线程，JVM 退出无需 shutdown） */
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final AgentRunMapper runs;
    private final PathNodeMapper pathNodes;
    private final CardVersionMapper cardVersions;
    private final CardMapper cards;
    private final SessionService sessions;
    private final ArtifactMapper artifacts;
    private final CitationMapper citations;
    private final LlmGateway llm;
    private final ObjectMapper objectMapper;
    private final QuotaService quota;
    private final SensitiveWordFilter sensitiveWords;
    /** 自代理：submit 必须经代理调 executeSummarize，同类 this 调用会让 @Async 失效退化为同步 */
    private final SummaryService self;

    @Value("${ke.llm.generator-model:unknown}")
    private String generatorModel;

    /** LLM 超时护栏（FR-S13；真实 60s，IT 压到 1s） */
    @Value("${ke.agent.llm-timeout-seconds:60}")
    private int llmTimeoutSeconds;

    @Value("${summarize.prompt.system}")
    private String systemPrompt;

    public SummaryService(AgentRunMapper runs, PathNodeMapper pathNodes, CardVersionMapper cardVersions,
                          CardMapper cards, SessionService sessions, ArtifactMapper artifacts,
                          CitationMapper citations, LlmGateway llm, ObjectMapper objectMapper,
                          QuotaService quota, SensitiveWordFilter sensitiveWords,
                          @Lazy SummaryService self) {
        this.runs = runs;
        this.pathNodes = pathNodes;
        this.cardVersions = cardVersions;
        this.cards = cards;
        this.sessions = sessions;
        this.artifacts = artifacts;
        this.citations = citations;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.quota = quota;
        this.sensitiveWords = sensitiveWords;
        this.self = self;
    }

    /**
     * agent_run.input_json 形状（userId 冗余进 JSON：GET 属主判定的 legacy 解析与讲解同则）。
     */
    public record SummarizeInput(Long userId, Long sessionId, List<Long> nodeIds) {
    }

    /** 材料装配产物：资产快照（citations allowed + sources 落库）、分支视图（非 LLM）、材料文本块 */
    private record Materials(Map<Long, String> assets, List<ReportResult.BranchView> branchViews,
                             String blocks) {
    }

    // ---------- 提交（controller 线程，同步前置校验） ----------

    /**
     * 提交整理运行：校验（sessionId 必填、nodeIds 非空且 ≤{@value #MAX_NODES}、会话属主 404/403、
     * 每个节点存在且属于该会话 400、每日配额超限 429）→ 落库 QUEUED → 异步执行，立即返回 runId
     * （POST → 202）。无效请求不消费配额（与讲解同则）。
     */
    public Long summarize(long userId, Long sessionId, List<Long> nodeIds) {
        if (sessionId == null) {
            throw new BadRequestException("sessionId 不能为空");
        }
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new BadRequestException("nodeIds 不能为空");
        }
        if (nodeIds.size() > MAX_NODES) {
            throw new BadRequestException("勾选节点不能超过 " + MAX_NODES + " 个");
        }
        sessions.ownedSession(userId, sessionId);
        for (Long nodeId : nodeIds) {
            PathNodeEntity node = nodeId == null ? null : pathNodes.selectById(nodeId);
            // 节点不属于本会话（别人的/不存在）一律 400，不区分泄露（与讲解 nodeId 校验同则）
            if (node == null || !node.getSessionId().equals(sessionId)) {
                throw new BadRequestException("节点不属于该会话");
            }
        }
        // FR-S04 配额前置：校验全过后才消费；超限 RateLimitException → 429 envelope，不创建 run
        if (!quota.tryConsume(userId)) {
            throw new RateLimitException("今日 " + quota.dailyLimit() + " 次智能服务已用完,明早 8 点恢复");
        }
        // 去重保序：同节点被多支选中（父子嵌套勾选）只组装一次材料；branchView 仍按选中根逐支生成
        SummarizeInput input = new SummarizeInput(userId, sessionId,
                nodeIds.stream().distinct().toList());
        AgentRunEntity run = new AgentRunEntity();
        run.setSessionId(sessionId);
        run.setServiceType(SERVICE_TYPE);
        run.setInputJson(json(input));
        run.setStatus(AgentRunStatus.QUEUED.name());
        runs.insert(run);
        self.executeSummarize(run.getId(), input);
        return run.getId();
    }

    // ---------- 执行（agentExecutor 虚拟线程） ----------

    @Async("agentExecutor")
    public void executeSummarize(long runId, SummarizeInput input) {
        long start = System.currentTimeMillis();
        try {
            AgentRunEntity run = runs.selectById(runId);
            if (run == null) {
                return;
            }
            run.setStatus(AgentRunStatus.RUNNING.name());
            runs.updateById(run);
            runs.touch(runId);

            Materials materials = assembleMaterials(input.sessionId(), input.nodeIds());
            String context = sessions.contextSummary(input.sessionId());
            String user = userPrompt(input, materials, context);

            SummaryOutput output = null;
            Exception last = null;
            for (int attempt = 0; attempt < MAX_LLM_ATTEMPTS && output == null; attempt++) {
                try {
                    String raw = completeWithinTimeout(new ChatCommand(systemPrompt, user, ModelTier.GENERATOR));
                    output = readSummaryOutput(raw);
                } catch (LlmTimeoutException e) {
                    // 超时不重试：LLM 已耗满时间预算；置 TIMEOUT 终态（用户可重新提交新 run）
                    timeout(runId);
                    return;
                } catch (Exception e) {
                    last = e; // Jackson 绑定/结构校验失败 → 重试 = 再调一次 LLM（共 2 次）
                }
            }
            if (output == null) {
                throw new IllegalStateException("整理输出解析失败（已重试 1 次）: "
                        + (last == null ? "未知原因" : last.getMessage()));
            }
            finish(runId, start, input, output, materials);
        } catch (Exception e) {
            log.warn("summarize run {} failed: {}", runId, e.getMessage());
            fail(runId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 整理输出绑定 + 落库前结构校验（与比较通道同路）：keyFindings 缺失/为空视同结构非法
     * （空报告无意义），抛 IllegalArgumentException 与 JSON 绑定失败同路：重试 1 次，仍非法 → FAILED。
     */
    private SummaryOutput readSummaryOutput(String raw) throws JsonProcessingException {
        SummaryOutput output = objectMapper.readValue(stripFences(raw), SummaryOutput.class);
        if (output.keyFindings() == null || output.keyFindings().isEmpty()) {
            throw new IllegalArgumentException("整理输出结构非法:keyFindings 不能为空");
        }
        return output;
    }

    // ---------- 材料组装 ----------

    /**
     * 材料装配（Task 24 决策）：逐选中根 {@code selectSubtree} 取子树——每个子树节点产出
     * 「提问 + 主题卡题 + 该节点讲解 artifact 的 sections 文本与遗留 openQuestions」文本块；
     * 资产全集 = 各讲解 artifact.sources 快照并集（citations 的 allowed 集合，putIfAbsent 保首见）；
     * branchView 每个选中根一行（rootNodeTitle + 子树节点标题列表，按访问顺序，非 LLM 生成）。
     * 跨选中根的重复节点（父子嵌套勾选）只组装一次材料（branchView 不去重，忠实呈现勾选）。
     */
    private Materials assembleMaterials(long sessionId, List<Long> rootIds) {
        List<ReportResult.BranchView> branchViews = new ArrayList<>(rootIds.size());
        Map<Long, String> assets = new LinkedHashMap<>();
        StringBuilder blocks = new StringBuilder();
        Set<Long> seen = new HashSet<>();
        for (Long rootId : rootIds) {
            List<PathNodeEntity> subtree = pathNodes.selectSubtree(sessionId, rootId);
            List<String> nodeTitles = new ArrayList<>(subtree.size());
            String rootTitle = null;
            for (PathNodeEntity node : subtree) {
                String title = nodeTitleOf(node);
                nodeTitles.add(title);
                if (rootTitle == null) {
                    rootTitle = title;
                }
                if (seen.add(node.getId())) {
                    appendNodeMaterial(blocks, node, title, assets);
                }
            }
            branchViews.add(new ReportResult.BranchView(
                    rootTitle == null ? "节点#" + rootId : rootTitle, nodeTitles));
        }
        return new Materials(assets, branchViews, blocks.toString());
    }

    /** 节点标题：卡题优先（访问了哪张卡），无卡节点回退提问文本，再退占位（branchView 与材料同则） */
    private String nodeTitleOf(PathNodeEntity node) {
        if (node.getCardVersionId() != null) {
            CardVersionEntity version = cardVersions.selectById(node.getCardVersionId());
            if (version != null) {
                CardEntity card = cards.selectById(version.getCardId());
                if (card != null) {
                    return card.getTitle();
                }
            }
        }
        return node.getQuestionText() == null || node.getQuestionText().isBlank()
                ? "节点#" + node.getId() : node.getQuestionText();
    }

    /** 单节点材料块：提问 + 主题卡题 + 该节点讲解 artifact（EXPLAIN DONE）的摘录与遗留疑问 */
    private void appendNodeMaterial(StringBuilder blocks, PathNodeEntity node, String title,
                                    Map<Long, String> assets) {
        blocks.append("[节点 ").append(node.getId()).append("] ");
        if (node.getQuestionText() != null && !node.getQuestionText().isBlank()) {
            blocks.append("提问:").append(node.getQuestionText()).append("。");
        }
        blocks.append("主题卡:").append(title).append('\n');
        List<AgentRunEntity> explains = runs.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getSessionId, node.getSessionId())
                .eq(AgentRunEntity::getNodeId, node.getId())
                .eq(AgentRunEntity::getServiceType, ExplainService.SERVICE_TYPE)
                .eq(AgentRunEntity::getStatus, AgentRunStatus.DONE.name())
                .orderByDesc(AgentRunEntity::getId));
        for (AgentRunEntity run : explains) {
            ExplainBrief brief = explainBriefOf(run);
            if (brief == null) {
                continue; // artifact 缺失/损坏：跳过该次讲解，材料不阻断
            }
            brief.sources().forEach(assets::putIfAbsent);
            blocks.append("讲解摘录:").append(brief.text()).append('\n');
            if (!brief.openQuestions().isEmpty()) {
                blocks.append("遗留疑问:").append(String.join(";", brief.openQuestions())).append('\n');
            }
        }
    }

    /** 讲解 artifact 的材料摘要：sections 文本（截断）+ sources 快照 + openQuestions（损坏视同无材料） */
    private record ExplainBrief(String text, Map<Long, String> sources, List<String> openQuestions) {
    }

    private ExplainBrief explainBriefOf(AgentRunEntity run) {
        ArtifactEntity artifact = firstArtifact(run);
        if (artifact == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(artifact.getContentJson());
            JsonNode output = root.get("output");
            if (output == null || output.isNull()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            JsonNode summary = output.get("summary");
            if (summary != null && !summary.isNull()) {
                text.append(summary.asText());
            }
            JsonNode sections = output.get("sections");
            if (sections != null && sections.isArray()) {
                for (JsonNode section : sections) {
                    if (section == null || section.isNull()) {
                        continue;
                    }
                    JsonNode body = section.get("body");
                    if (body != null && !body.isNull()) {
                        text.append(' ').append(body.asText());
                    }
                }
            }
            String excerpt = text.length() > EXPLAIN_EXCERPT_LIMIT
                    ? text.substring(0, EXPLAIN_EXCERPT_LIMIT) : text.toString();
            Map<Long, String> sources = new LinkedHashMap<>();
            JsonNode sourcesNode = root.get("sources");
            if (sourcesNode != null && sourcesNode.isObject()) {
                sourcesNode.fields().forEachRemaining(entry -> {
                    try {
                        sources.put(Long.parseLong(entry.getKey()), entry.getValue().asText());
                    } catch (NumberFormatException ignored) {
                        // 非 assetId 键：跳过（sources 键约定为 assetId 字符串）
                    }
                });
            }
            List<String> openQuestions = new ArrayList<>();
            JsonNode questions = output.get("openQuestions");
            if (questions != null && questions.isArray()) {
                for (JsonNode question : questions) {
                    if (question != null && !question.isNull() && !question.asText().isBlank()) {
                        openQuestions.add(question.asText());
                    }
                }
            }
            return new ExplainBrief(excerpt, sources, openQuestions);
        } catch (Exception e) {
            return null;
        }
    }

    /** DONE 且 artifact_ids 首个 id 可解析 → artifact 实体；否则 null（与 RunController 同规则） */
    private ArtifactEntity firstArtifact(AgentRunEntity run) {
        String idsJson = run.getArtifactIds();
        if (!AgentRunStatus.DONE.name().equals(run.getStatus()) || idsJson == null || idsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode ids = objectMapper.readTree(idsJson);
            if (ids.isArray() && !ids.isEmpty() && ids.get(0).canConvertToLong()) {
                return artifacts.selectById(ids.get(0).longValue());
            }
        } catch (Exception ignored) {
            // artifact_ids 损坏视同无 artifact
        }
        return null;
    }

    // ---------- 终态回写 ----------

    /**
     * 终态回写：后处理链与讲解同源——①敏感词过滤（FR-S10，keyFindings 正文 + openQuestions）；
     * ②{@link CitationSanitizer}（FR-S05，allowed=材料资产全集，FACT 空引降级 SYNTHESIS 同规则）；
     * ③openQuestions 服务端兜底去重（LLM 按提示词合并，此处保序去重剔除空白）→
     * artifact(REPORT, content_json={type,keyFindings,openQuestions,branchView,sources,disclaimer,audit})
     * + citation(object_type=agent_run) + DONE/artifact_ids。越界剥离旁路留痕 run.error（不置 FAILED）。
     */
    private void finish(long runId, long start, SummarizeInput input, SummaryOutput output,
                        Materials materials) {
        int filtered = 0;
        List<SummaryOutput.KeyFinding> cleanedFindings = new ArrayList<>();
        for (SummaryOutput.KeyFinding finding : output.keyFindings()) {
            if (finding == null) {
                continue; // 展示层职责：落 artifact 不含 null 条（与讲解 null 段同则）
            }
            SensitiveWordFilter.FilterResult body = sensitiveWords.filter(finding.body());
            filtered += body.hits();
            cleanedFindings.add(new SummaryOutput.KeyFinding(body.text(), finding.claimType(),
                    finding.citations()));
        }
        List<String> cleanedQuestions = new ArrayList<>();
        if (output.openQuestions() != null) {
            for (String question : output.openQuestions()) {
                SensitiveWordFilter.FilterResult clean = sensitiveWords.filter(question);
                filtered += clean.hits();
                cleanedQuestions.add(clean.text());
            }
        }

        SanitizeReport report = CitationSanitizer.sanitize(toSanitizedSections(cleanedFindings),
                materials.assets().keySet());
        List<SummaryOutput.KeyFinding> sanitized = new ArrayList<>(report.sections().size());
        for (SanitizedSection section : report.sections()) {
            if (section != null) {
                sanitized.add(new SummaryOutput.KeyFinding(section.body(), section.claimType(),
                        section.citations()));
            }
        }

        ReportResult result = new ReportResult(REPORT_ARTIFACT_TYPE, sanitized,
                dedupe(cleanedQuestions), materials.branchViews(), materials.assets(),
                AgentLabels.DISCLAIMER, new RunMetrics.Audit(report.strippedCitations().size(), filtered));

        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setSessionId(input.sessionId());
        artifact.setType(REPORT_ARTIFACT_TYPE);
        artifact.setContentJson(json(result));
        artifact.setStatus("DRAFT");
        artifacts.insert(artifact);
        insertCitations(runId, sanitized.stream()
                .flatMap(finding -> finding.citations() == null ? java.util.stream.Stream.<Long>empty()
                        : finding.citations().stream())
                .toList());

        AgentRunEntity done = runs.selectById(runId);
        done.setStatus(AgentRunStatus.DONE.name());
        done.setLatencyMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start));
        done.setModel(generatorModel);
        done.setArtifactIds(json(List.of(artifact.getId())));
        if (!report.strippedCitations().isEmpty()) {
            // 旁路留痕（不置 FAILED，与讲解/比较同则）：完整审计数据在 artifact.audit
            done.setError("引用校验:剥离 " + report.strippedCitations().size() + " 个越界引用");
        }
        runs.updateById(done);
    }

    /** keyFinding → 校验器入参（同形映射：ke-domain 不依赖 service DTO） */
    private static List<SanitizedSection> toSanitizedSections(List<SummaryOutput.KeyFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        List<SanitizedSection> mapped = new ArrayList<>(findings.size());
        for (SummaryOutput.KeyFinding finding : findings) {
            mapped.add(finding == null ? null
                    : new SanitizedSection(finding.body(), finding.claimType(), finding.citations()));
        }
        return mapped;
    }

    /** openQuestions 兜底去重（保序、剔除空白）；null 入参视为无 */
    private static List<String> dedupe(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String question : questions) {
            if (question != null && !question.isBlank()) {
                seen.add(question);
            }
        }
        return List.copyOf(seen);
    }

    /** 引用行落表（object_type=agent_run）：入参应为 sanitize 后的允许集合，逐 run 去重（与讲解同则） */
    private void insertCitations(long runId, List<Long> assetIds) {
        Set<Long> linked = new HashSet<>();
        for (Long assetId : assetIds) {
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

    // ---------- 提示词组装 ----------

    /** user = 会话上下文摘要 + 选中分支材料块 + 可用资料（assetId 标注，供 citations 引用） */
    private String userPrompt(SummarizeInput input, Materials materials, String context) {
        StringBuilder sb = new StringBuilder("会话上下文:").append(context.isBlank() ? "（无）" : context).append('\n');
        sb.append("选中分支材料:\n").append(materials.blocks());
        sb.append("可用资料:\n");
        if (materials.assets().isEmpty()) {
            sb.append("（无）");
        } else {
            materials.assets().forEach((assetId, text) ->
                    sb.append("[asset ").append(assetId).append("] ").append(text).append('\n'));
        }
        return sb.toString();
    }

    // ---------- 护栏与终态（与 ExplainService 同型，Task 20 等价手写） ----------

    /**
     * LLM 调用超时护栏（FR-S13）：complete 跑在独立虚拟线程，当前（虚拟）线程
     * {@code future.get(timeout)} 等待，超时 {@code cancel(true)} 尽力中断；阈值
     * {@code ke.agent.llm-timeout-seconds}（默认 60s，IT 压到 1s）。
     */
    private String completeWithinTimeout(ChatCommand command) throws Exception {
        Future<String> future = llmExecutor.submit(() -> llm.complete(command));
        try {
            return future.get(llmTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmTimeoutException();
        } catch (ExecutionException e) {
            // 解包网关真实异常（如 Stub 未配置应答），消息与直调一致
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    /** 超时信号（不进重试循环）：error 文案与讲解一致 */
    private static final class LlmTimeoutException extends RuntimeException {
        LlmTimeoutException() {
            super("服务超时,请稍后重试");
        }
    }

    /** 超时终态：RUNNING→TIMEOUT 合法迁移；允许用户重新提交（新 run 无损） */
    private void timeout(long runId) {
        try {
            AgentRunEntity run = runs.selectById(runId);
            run.setStatus(AgentRunStatus.TIMEOUT.name());
            run.setError("服务超时,请稍后重试");
            runs.updateById(run);
        } catch (Exception e) {
            log.error("summarize run {} 超时终态回写失败", runId, e);
        }
    }

    private void fail(long runId, String message) {
        try {
            AgentRunEntity run = runs.selectById(runId);
            run.setStatus(AgentRunStatus.FAILED.name());
            run.setError(message);
            runs.updateById(run);
        } catch (Exception e) {
            log.error("summarize run {} 终态回写失败", runId, e);
        }
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
