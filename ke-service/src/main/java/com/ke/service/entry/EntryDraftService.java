package com.ke.service.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.domain.entry.EntryConfig;
import com.ke.domain.entry.EntryConfigValidator;
import com.ke.domain.entry.NlIntent;
import com.ke.domain.enums.CardStatus;
import com.ke.domain.enums.EntryType;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.service.card.CardService;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 自然语言入口草稿抽取（FR-N01/N02/N06/N07，Task 27）：用户一句话 → 意图分类
 * （{@link NlIntentClassifier}，路由档）→ 按意图产出 {@link DraftResult}——
 * <ul>
 *   <li>OUT_OF_SCOPE：不抽取，返回替代建议（礼貌拒绝，不追问）。</li>
 *   <li>EXPLAIN/COMPARE：生成档抽取配置（提示词 draft-prompts.properties：用户话+卡摘要+授权资料
 *       清单 id+标题），绑定 {@link DraftOutput}（失败重试 1 次，与讲解流水线同则；两次仍败抛
 *       IllegalStateException → 500 兜底）→ 组装 EntryConfig(type=AGENT_SERVICE)→
 *       {@link EntryConfigValidator} 硬校验（服务白名单 {EXPLAIN,COMPARE} + 卡授权资产集）；
 *       违规时先自动收窄（assetScope 过滤掉越权 id）重校验一次（FR-N06 宽泛给默认方案），
 *       仍违 → 如实返回 violations、config=null——白名单是硬边界，绝不放宽放行（FR-N07）。</li>
 *   <li>LINK_CARD：站内检索（{@link CardService#list}，q=text 截 20 字）取 top 1 已发布卡
 *       （跳过入口所属卡自身，自链无意义）；无匹配给替代建议。跨主题（目标卡 theme ≠ 所属卡）
 *       时 Validator 要求三要件，草稿不代填出处（RelationGuard 键约定：出处须可查证，由保存端
 *       UI 采集）→ 跨主题命中返回缺失要件的 violations 引导用户补齐，但 DraftResult 仍携带
 *       config（只填 name/type/targetCardId——目标卡是检索发现的事实，回填避免前端丢目标卡
 *       后保存必 400 的死路；why/source 留空不代填）。</li>
 * </ul>
 * 前置校验：text 非空 ≤200 字（HTTP 400）；卡存在（否则 404）且 PUBLISHED（否则 400）。
 * 超时护栏：nl-draft 是同步 HTTP 请求（A3 交互 30s 保障），两次 LLM 调用分别以
 * {@code ke.agent.draft-classify-timeout-seconds}（默认 8s）/ {@code ke.agent.draft-extract-timeout-seconds}
 * （默认 15s）封顶（与讲解流水线同一手写 future.get 等价实现）——分类超时安全侧兜底
 * OUT_OF_SCOPE（礼貌拒绝），抽取超时不重试、转 FAILED 语义（violations「配置生成超时」+config=null）。
 * 网关异常（分类/生成）不在此吞掉——向上传播由全局兜底 500（与讲解流水线同语义）。
 * 本任务只产出草稿不落库；userId 预留给 Task 28 保存链路（草稿归属人）。
 */
@Service
@PropertySource(value = "classpath:draft-prompts.properties", encoding = "UTF-8")
public class EntryDraftService {

    /** FR-N01：草稿输入上限，超限 HTTP 400（分类器内部另有 500 字防御截断） */
    static final int MAX_TEXT_CHARS = 200;
    /** LINK_CARD 检索词截断（一句话描述只取前 20 字做标题匹配） */
    static final int SEARCH_QUERY_CHARS = 20;
    /** 默认命名「关于{text}」的总长上限 */
    static final int MAX_DRAFT_NAME_CHARS = 30;
    /** AGENT_SERVICE 两个已上线服务（02 §5）；抽取与校验共用同一白名单 */
    static final Set<String> SERVICE_WHITELIST = Set.of("EXPLAIN", "COMPARE");
    /** 绑定失败重试 1 次 = 最多 2 次 LLM 调用（与 ExplainService.MAX_LLM_ATTEMPTS 同则） */
    static final int MAX_LLM_ATTEMPTS = 2;

    static final String OUT_OF_SCOPE_ADVICE =
            "暂不支持该类入口。可以试试:深入了解某个问题、对比两个事物、或把相关卡片连接为入口";
    static final String NO_MATCH_ADVICE = "未找到相关卡片,可直接浏览专题选择";
    /** 钉子④（P6-27 移交）：卡无挂接知识单元时 EXPLAIN/COMPARE 草稿的短路 violations 文案 */
    static final String NO_ASSET_ADVICE = "该卡暂无挂接知识单元,请先在知识资源挂接";
    /** 抽取超时的 FAILED 语义 violations 文案（config=null，提示用户重试而非 500） */
    static final String DRAFT_TIMEOUT_VIOLATION = "配置生成超时,请重试";

    /** 超时护栏专用执行器：虚拟线程每任务一线，无池化开销（守护线程，JVM 退出无需 shutdown） */
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final CardMapper cards;
    private final EntryAuthorizedAssets authorizedAssets;
    private final CardService cardService;
    private final NlIntentClassifier classifier;
    private final LlmGateway llm;
    private final ObjectMapper objectMapper;

    @Value("${draft.prompt.system}")
    private String draftSystemPrompt;

    /** 意图分类（路由档）单次调用超时秒数（真实 8s，IT 压到 2s） */
    @Value("${ke.agent.draft-classify-timeout-seconds:8}")
    private int classifyTimeoutSeconds;

    /** 生成档抽取单次调用超时秒数（真实 15s，IT 压到 1s） */
    @Value("${ke.agent.draft-extract-timeout-seconds:15}")
    private int extractTimeoutSeconds;

    public EntryDraftService(CardMapper cards, EntryAuthorizedAssets authorizedAssets,
                             CardService cardService, NlIntentClassifier classifier, LlmGateway llm,
                             ObjectMapper objectMapper) {
        this.cards = cards;
        this.authorizedAssets = authorizedAssets;
        this.cardService = cardService;
        this.classifier = classifier;
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    /** 统一草稿结果：config/violations 二选一（config=null 时 violations 非空）；advice 仅替代建议路径非空 */
    public record DraftResult(NlIntent intent, EntryConfig config, List<String> violations, String advice) {
    }

    /** 生成档抽取输出的绑定目标（draft.prompt.system 约定的 JSON 形状） */
    record DraftOutput(String name, String goal, String serviceType,
                       List<Long> assetScope, String outputSpec) {
    }

    /**
     * 抽取一张已发布卡的入口草稿。任何认证用户可为自己起草（含 EXPLORER）；草稿为无状态产物，
     * 不落库不占配额（Task 28 保存链路才写 entry 表并按其规则鉴权）。
     */
    public DraftResult draft(long userId, long cardId, String text) {
        if (text == null || text.isBlank()) {
            throw new BadRequestException("请用一句话描述想要的入口");
        }
        String input = text.trim();
        if (input.length() > MAX_TEXT_CHARS) {
            throw new BadRequestException("描述不能超过 " + MAX_TEXT_CHARS + " 字(当前 "
                    + input.length() + " 字)");
        }
        CardEntity card = cards.selectById(cardId);
        if (card == null) {
            throw new NotFoundException("卡片不存在");
        }
        if (!CardStatus.PUBLISHED.name().equals(card.getStatus())) {
            throw new BadRequestException("卡片未发布,暂不能起草入口");
        }

        // 分类超时 → 安全侧兜底 OUT_OF_SCOPE（礼貌拒绝给替代建议），与解析失败同侧；
        // 网关自身异常仍向上传播 500（分类器同语义）
        NlIntent intent;
        try {
            intent = completeWithinTimeout(() -> classifier.classify(input), classifyTimeoutSeconds);
        } catch (DraftTimeoutException e) {
            return new DraftResult(NlIntent.OUT_OF_SCOPE, null, List.of(), OUT_OF_SCOPE_ADVICE);
        }
        return switch (intent) {
            case EXPLAIN, COMPARE -> agentServiceDraft(intent, card, input);
            case LINK_CARD -> linkCardDraft(card, input);
            case OUT_OF_SCOPE -> new DraftResult(intent, null, List.of(), OUT_OF_SCOPE_ADVICE);
        };
    }

    // ---------- EXPLAIN/COMPARE：生成档抽取 + 硬校验 + 自动收窄 ----------

    private DraftResult agentServiceDraft(NlIntent intent, CardEntity card, String text) {
        Map<Long, String> authorized = authorizedAssets(card.getCurrentVersionId());
        // 钉子④（P6-27 移交）：卡无挂接知识单元 → 短路返回 violations,不打生成 LLM
        //（没有可作资料范围的对象,抽取结果必然违规;省一次调用与等待）
        if (authorized.isEmpty()) {
            return new DraftResult(intent, null, List.of(NO_ASSET_ADVICE), null);
        }
        String user = "用户请求:" + text + "\n所在卡片:《" + card.getTitle() + "》"
                + (card.getSummaryText() == null || card.getSummaryText().isBlank()
                        ? "" : "\n卡片摘要:" + card.getSummaryText())
                + "\n可用资料清单(assetScope 只能从这里选 assetId):\n" + assetListText(authorized);

        DraftOutput output = null;
        Exception last = null;
        for (int attempt = 0; attempt < MAX_LLM_ATTEMPTS && output == null; attempt++) {
            try {
                String raw = completeWithinTimeout(
                        () -> llm.complete(new ChatCommand(draftSystemPrompt, user, ModelTier.GENERATOR)),
                        extractTimeoutSeconds);
                output = objectMapper.readValue(stripFences(raw), DraftOutput.class);
            } catch (DraftTimeoutException e) {
                // 超时不重试（时间预算已耗满，重试只会翻倍等待）：FAILED 语义——violations 提示
                // 重试 + config=null，由前端引导，而非 IllegalStateException 500
                return new DraftResult(intent, null, List.of(DRAFT_TIMEOUT_VIOLATION), null);
            } catch (Exception e) {
                last = e; // JSON 绑定失败 → 重试一次（共 2 次），与讲解流水线同则
            }
        }
        if (output == null) {
            throw new IllegalStateException("入口草稿抽取失败(已重试 1 次): "
                    + (last == null ? "未知原因" : last.getMessage()));
        }

        // 钉子③（P6-27 移交）：LLM 抽取的 assetScope 数组可能含 null 元素（模型幻觉输出形状），
        // Set.copyOf 会 NPE 打穿重试——序列化前过滤 null；name/goal 等 null 由 Validator 出违规清单
        Set<Long> scopeIds = output.assetScope() == null ? null
                : output.assetScope().stream().filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        EntryConfig config = new EntryConfig(output.name(), EntryType.AGENT_SERVICE, output.goal(), null,
                output.serviceType(), scopeIds == null ? null : Set.copyOf(scopeIds),
                output.outputSpec(), null, null, null, null);
        List<String> violations = EntryConfigValidator.validate(config, SERVICE_WHITELIST,
                authorized.keySet(), false);
        if (violations.isEmpty()) {
            return new DraftResult(intent, config, List.of(), null);
        }

        // 自动收窄（FR-N06）：越权资产过滤到授权集内重校验一次；serviceType 等其他违规收窄救不了，
        // 收窄后仍违 → 如实报告（config=null），由前端引导用户改写
        Set<Long> narrowed = new LinkedHashSet<>();
        if (config.assetScope() != null) {
            config.assetScope().stream().filter(authorized::containsKey).forEach(narrowed::add);
        }
        EntryConfig trimmed = new EntryConfig(config.name(), config.type(), config.goal(), config.inputs(),
                config.serviceType(), narrowed, config.outputSpec(), null, null, null, null);
        List<String> after = EntryConfigValidator.validate(trimmed, SERVICE_WHITELIST,
                authorized.keySet(), false);
        if (after.isEmpty()) {
            return new DraftResult(intent, trimmed, List.of(), null);
        }
        // intent 恒为分类结果（EXPLAIN/COMPARE）；serviceType 与意图不符等由 violations 表达
        return new DraftResult(intent, null, after, null);
    }

    // ---------- LINK_CARD：站内检索 + 跨主题判定 ----------

    private DraftResult linkCardDraft(CardEntity card, String text) {
        String query = text.length() > SEARCH_QUERY_CHARS ? text.substring(0, SEARCH_QUERY_CHARS) : text;
        List<CardService.CardListItem> matches = cardService.list(null, query, null).items().stream()
                // 自链无意义，跳过自身（longValue 比较_Long 装箱缓存只到 127，== 会变成引用比较）
                .filter(item -> item.id() != null && item.id().longValue() != card.getId())
                .toList();
        if (matches.isEmpty()) {
            return new DraftResult(NlIntent.LINK_CARD, null, List.of(), NO_MATCH_ADVICE);
        }
        CardService.CardListItem target = matches.get(0);
        // 跨主题判定与 RelationGuard 同则：任一 theme 缺失视为同主题（不要求三要件）；
        // 保存路径以 RelationGuard 实体计算为准，此处仅供草稿预校验
        boolean crossTheme = target.theme() != null && !target.theme().equals(card.getTheme());
        String name = truncate("关于" + text, MAX_DRAFT_NAME_CHARS);
        EntryConfig config = new EntryConfig(name, EntryType.LINK_CARD, null, null,
                null, null, null, null, target.id(), null, null);
        List<String> violations = EntryConfigValidator.validate(config, Set.of(), Set.of(), crossTheme);
        if (violations.isEmpty()) {
            return new DraftResult(NlIntent.LINK_CARD, config, List.of(), null);
        }
        // 跨主题命中但草稿不代填 why/source（出处须可查证，RelationGuard 键约定）→ 返回缺失要件；
        // config 仍携带 name/type/targetCardId（目标卡是检索发现的事实，非捏造）——前端回填后
        // 用户补齐三要件即可保存，避免丢失目标卡导致的「必须指定目标卡片」400 死路
        return new DraftResult(NlIntent.LINK_CARD, config, violations, null);
    }

    // ---------- LLM 超时护栏 ----------

    /**
     * LLM 调用超时护栏（与 {@code ExplainService.completeWithinTimeout} 同一手写等价实现，
     * 单为一处超时不引 resilience4j）：调用跑在独立虚拟线程，当前（虚拟）线程
     * {@code future.get(timeout)} 等待，超时 {@code cancel(true)} 尽力中断底层任务。
     * 阈值分类/抽取分开配置（{@code ke.agent.draft-classify/extract-timeout-seconds}），
     * 同步 HTTP 请求不被挂起的网关连接无限占用（A3 30s 交互保障）。
     * {@link LlmGateway#complete} 只抛非受检异常，故解包后原样重抛（全局兜底 500 同旧语义）。
     */
    private <T> T completeWithinTimeout(Callable<T> call, int timeoutSeconds) {
        Future<T> future = llmExecutor.submit(call);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DraftTimeoutException();
        } catch (ExecutionException e) {
            // 解包网关真实异常（如 Stub 未配置应答），消息与直调一致
            throw e.getCause() instanceof RuntimeException ex ? ex
                    : new IllegalStateException("入口草稿调用失败: " + e.getCause(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("入口草稿处理被中断", e);
        }
    }

    /** 草稿超时信号（不进重试循环）：分类兜底安全侧 OUT_OF_SCOPE、抽取转 FAILED 语义 violations */
    private static final class DraftTimeoutException extends RuntimeException {
        DraftTimeoutException() {
            super(DRAFT_TIMEOUT_VIOLATION);
        }
    }

    // ---------- 授权资料清单（id→标题） ----------

    /**
     * 当前卡版本的授权资产清单（LinkedHashMap 保 sources 顺序）：card_version.sources[].assetId
     * → knowledge_asset（license_expire 为空或 ≥ 今天，与 RetrievalService 同一授权过滤）。
     * Task 28 抽出 {@link EntryAuthorizedAssets} 共用（草稿与保存同一份授权判定，口径不漂移）。
     */
    private Map<Long, String> authorizedAssets(Long cardVersionId) {
        return authorizedAssets.of(cardVersionId);
    }

    /** 提示词用资料清单：每行「- {id}:{标题}」 */
    private static String assetListText(Map<Long, String> authorized) {
        if (authorized.isEmpty()) {
            return "（该卡暂无挂接资料）";
        }
        StringBuilder text = new StringBuilder();
        authorized.forEach((id, title) -> text.append("- ").append(id).append(":").append(title).append('\n'));
        return text.toString().stripTrailing();
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** 兼容真实网关返回的 markdown 代码围栏（```json ... ```），与讲解/总结流水线同则 */
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
        }
        return text.trim();
    }
}
