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
import com.ke.service.agent.post.ClaimLabelAppender;
import com.ke.service.agent.post.RunMetrics;
import com.ke.service.agent.post.SensitiveWordFilter;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import com.ke.service.common.RateLimitException;
import com.ke.service.explore.SessionService;
import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import com.ke.service.quota.QuotaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
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
 * 讲解服务流水线（FR-S02 / 02 §5.2）：受限检索 → 提示词组装 → LLM（{@code ke.agent.llm-timeout-seconds}
 * 超时护栏，超时置 TIMEOUT 不重试）→ Jackson 绑定 {@link ExplainOutput}（失败自动重试 1 次）→
 * 后处理链（Task 20）：敏感词过滤（FR-S10，summary+各段 body 命中替换等长 *）→ 引用校验
 * （FR-S05，{@link CitationSanitizer}：越界引用剔除 + FACT 空引用降级）→ 生成标识 disclaimer（R8）+
 * 审计旁注 audit 落 artifact → citation(object_type=agent_run) 落库 → 回写 agent_run(DONE, artifact_ids)。
 * 任何异常置 FAILED(error=消息)。提交前置配额（FR-S04，{@link QuotaService#tryConsume}，
 * 超限 RateLimitException → 429 envelope）。
 *
 * 线程模型：submit 前置校验（会话属主/卡已发布/配额）在 controller 线程（userId 在 submit 时
 * 捕获进 input_json，虚拟线程不再依赖 SecurityContext）；执行走 agentExecutor 虚拟线程（自代理
 * 调 @Async，同类 this 调用会退化为同步）。LLM 调用再包一层虚拟线程 future.get 超时（Task 20 手写
 * 等价实现，见 completeWithinTimeout 注释）；心跳循环维持 recycleStale 兜底。
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

    /** LLM 超时护栏专用执行器：虚拟线程每任务一线，无池化开销（守护线程，JVM 退出无需 shutdown） */
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();

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
    private final QuotaService quota;
    private final SensitiveWordFilter sensitiveWords;
    /** 自代理：submit 必须经代理调 executeExplain，同类 this 调用会让 @Async 失效退化为同步 */
    private final ExplainService self;

    @Value("${ke.llm.generator-model:unknown}")
    private String generatorModel;

    /** 单次 LLM 调用超时秒数（FR-S13；真实 60s，IT 压到 1s） */
    @Value("${ke.agent.llm-timeout-seconds:60}")
    private int llmTimeoutSeconds;

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
                          ObjectMapper objectMapper, QuotaService quota, SensitiveWordFilter sensitiveWords,
                          @Lazy ExplainService self) {
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
        this.quota = quota;
        this.sensitiveWords = sensitiveWords;
        this.self = self;
    }

    /**
     * agent_run.input_json 形状（userId 冗余进 JSON：GET 属主判定与 Task 21 归一都从这里取）。
     * parentRunId=追问链（FR-E08，Task 22）：追问=新 run，agent_run 无 parent 列，记 JSON 即可
     * （Phase 4 parentNodeId 先例）；null=首次讲解。
     */
    public record ExplainInput(Long userId, Long sessionId, Long nodeId,
                               Long cardVersionId, String question, String level, Long parentRunId) {
    }

    // ---------- 提交（controller 线程，同步前置校验） ----------

    /**
     * 提交讲解运行：校验（question/level、会话属主 404/403、节点归属、追问 parent 存在且属主 400、
     * 卡已发布 400、每日配额超限 429）→ 落库 QUEUED（input_json={userId,sessionId,nodeId,
     * cardVersionId,question,level,parentRunId}）→ 异步执行，立即返回 runId（POST → 202）。
     */
    public Long explain(long userId, Long cardVersionId, String question, String level,
                        Long sessionId, Long nodeId, Long parentRunId) {
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
        if (parentRunId != null) {
            requireParentOwned(userId, parentRunId);
        }
        CardVersionEntity version = cardVersions.selectById(cardVersionId);
        if (version == null) {
            throw new BadRequestException("卡片版本不存在");
        }
        CardEntity card = cards.selectById(version.getCardId());
        if (card == null || !CardStatus.PUBLISHED.name().equals(card.getStatus())) {
            throw new BadRequestException("卡片未发布");
        }
        // FR-S04 配额前置（Task 20）：校验全过后才消费——无效请求不计入当日 30 次；
        // 超限 RateLimitException → 429 envelope（04 §8.6 文案），不创建 run
        if (!quota.tryConsume(userId)) {
            throw new RateLimitException("今日 " + quota.dailyLimit() + " 次智能服务已用完,明早 8 点恢复");
        }

        ExplainInput input = new ExplainInput(userId, sessionId, nodeId, cardVersionId, question, level, parentRunId);
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

    /**
     * 追问链校验（Task 22 决策）：parent run 必须存在且属主——否则一律 400「无效的追问来源」，
     * 不区分存在性泄露（404 会暴露他人 run 的存在）。属主判定与 RunController.requireRunOwner
     * 同规则：有会话走会话属主，无会话 legacy run 解析 input_json.userId。
     */
    private void requireParentOwned(long userId, long parentRunId) {
        AgentRunEntity parent = runs.selectById(parentRunId);
        if (parent != null && parent.getSessionId() != null) {
            try {
                sessions.ownedSession(userId, parent.getSessionId());
                return;
            } catch (NotFoundException | AccessDeniedException e) {
                // 他人会话/会话已删 → 与不存在同响应
            }
        } else if (parent != null) {
            Long owner = legacyOwnerOf(parent.getInputJson());
            if (owner != null && owner == userId) {
                return;
            }
        }
        throw new BadRequestException("无效的追问来源");
    }

    /** 无会话 legacy run 的属主解析（input_json.userId），损坏视同无属主 */
    private Long legacyOwnerOf(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(inputJson, ExplainInput.class).userId();
        } catch (Exception e) {
            return null;
        }
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
            // 心跳：单次 LLM 已被 llm-timeout-seconds 封顶（默认 60s），执行中循环心跳收益有限，
            // 卡死兜底维持 recycleStale（stale-seconds）定时回收
            runs.touch(runId);

            Map<Long, String> materials = retrieval.retrieve(input.cardVersionId());
            String context = input.sessionId() == null ? "" : sessions.contextSummary(input.sessionId());
            String system = systemPrompt(input.level(), materials.isEmpty());
            String user = userPrompt(input, materials, context);

            ExplainOutput output = null;
            Exception last = null;
            for (int attempt = 0; attempt < MAX_LLM_ATTEMPTS && output == null; attempt++) {
                try {
                    String raw = completeWithinTimeout(new ChatCommand(system, user, ModelTier.GENERATOR));
                    output = objectMapper.readValue(stripFences(raw), ExplainOutput.class);
                } catch (LlmTimeoutException e) {
                    // 超时不重试：LLM 已耗满时间预算，重试只会把等待翻倍；置 TIMEOUT 终态（用户可重新提交新 run）
                    timeout(runId);
                    return;
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

    /** 终态回写：artifact(EXPLAIN, content_json={output,sources,disclaimer,audit}) + citation 落表 + DONE/artifact_ids */
    private void finish(long runId, long start, ExplainInput input,
                        ExplainOutput output, Map<Long, String> materials) {
        // Task 20 后处理链 ①敏感词过滤（FR-S10）：summary + 各段 body 命中词表 → 等长 '*' 替换，命中数留审计
        SensitiveWordFilter.FilterResult summary = sensitiveWords.filter(output.summary());
        List<ExplainOutput.Section> cleanedSections = new ArrayList<>();
        int filtered = summary.hits();
        if (output.sections() != null) {
            for (ExplainOutput.Section section : output.sections()) {
                if (section == null) {
                    continue; // 展示层职责：落 artifact 不含 null 段（与 withSections 同则）
                }
                SensitiveWordFilter.FilterResult body = sensitiveWords.filter(section.body());
                filtered += body.hits();
                cleanedSections.add(new ExplainOutput.Section(body.text(), section.claimType(), section.citations()));
            }
        }
        ExplainOutput cleaned = new ExplainOutput(summary.text(), cleanedSections,
                output.openQuestions(), output.evidenceGaps());

        // ②引用校验（FR-S05，Task 19）：LLM 绑定成功后、落 artifact/citation 之前——
        // 越界引用剔除（只剩本次检索资料内的 id），FACT 空引用降级 SYNTHESIS（段落档位自表达，不加顶层标志）
        SanitizeReport report = CitationSanitizer.sanitize(
                toSanitizedSections(cleaned.sections()), materials.keySet());
        ExplainOutput sanitized = withSections(cleaned, report.sections());

        // ③生成标识（R8）+ 审计旁注（FR-S13 计量的 MVP 口径=latency+model；tokens/cost 随网关二期）：
        // artifact content_json 顶层 disclaimer + audit={stripped,filtered}
        RunMetrics.Audit audit = new RunMetrics.Audit(report.strippedCitations().size(), filtered);
        ExplainResult result = ClaimLabelAppender.label(sanitized, materials, audit);

        Long artifactId = null;
        if (input.sessionId() != null) {
            // artifact.session_id 非空（V1 约束）：无会话的讲解 run 不落 artifact，仅回状态
            ArtifactEntity artifact = new ArtifactEntity();
            artifact.setSessionId(input.sessionId());
            artifact.setType(SERVICE_TYPE);
            artifact.setContentJson(json(result));
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
            // 旁路留痕（不置 FAILED，Task 19 起的既有行为，IT 依赖）：完整审计数据在 artifact.audit
            done.setError("引用校验:剥离 " + report.strippedCitations().size() + " 个越界引用");
        }
        runs.updateById(done);
    }

    /**
     * LLM 调用超时护栏（FR-S13 / 02 §5.1）。Task 18 简报建议 Resilience4j TimeLimiter——单为一处
     * 超时引入整个 resilience4j 栈不值，等价手写：complete 跑在独立虚拟线程，当前（虚拟）线程
     * {@code future.get(timeout)} 等待，超时 {@code cancel(true)} 尽力中断底层任务（虚拟线程可中断，
     * 但 HTTP 客户端阻塞读是否响应中断取决于实现——取消尽力而为，run 状态已落终态）。
     * 阈值 {@code ke.agent.llm-timeout-seconds}（默认 60s，IT 压到 1s）。
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

    /** 超时信号（不进重试循环）：error 文案固定，60s 上限细节由前端（Task 21）按配置展示 */
    private static final class LlmTimeoutException extends RuntimeException {
        LlmTimeoutException() {
            super("服务超时,请稍后重试");
        }
    }

    /** 超时终态：RUNNING→TIMEOUT 合法迁移；允许用户重新提交（新 run 新路径无损） */
    private void timeout(long runId) {
        try {
            AgentRunEntity run = runs.selectById(runId);
            run.setStatus(AgentRunStatus.TIMEOUT.name());
            run.setError("服务超时,请稍后重试");
            runs.updateById(run);
        } catch (Exception e) {
            log.error("explain run {} 超时终态回写失败", runId, e);
        }
    }

    /** 讲解段 → 校验器入参（同形映射：ke-domain 不依赖 service DTO）；null 段原位传给校验器（索引不漂移），由 withSections 落库前过滤 */
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

    /** 校验后的段落回填讲解输出（summary/openQuestions/evidenceGaps 原样）；null 段在此过滤——展示层职责，落 artifact 不含 null */
    private static ExplainOutput withSections(ExplainOutput output, List<SanitizedSection> sections) {
        List<ExplainOutput.Section> mapped = new ArrayList<>(sections.size());
        for (SanitizedSection section : sections) {
            if (section != null) {
                mapped.add(new ExplainOutput.Section(section.body(), section.claimType(), section.citations()));
            }
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
