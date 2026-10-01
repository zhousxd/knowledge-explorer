package com.ke.service.entry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.domain.entry.EntryConfig;
import com.ke.domain.entry.EntryConfigValidator;
import com.ke.domain.entry.NlIntent;
import com.ke.domain.enums.CardStatus;
import com.ke.domain.enums.EntryType;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.KnowledgeAssetEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.KnowledgeAssetMapper;
import com.ke.service.card.CardService;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *       UI 采集）→ 跨主题命中会返回缺失要件的 violations，引导用户补齐后保存。</li>
 * </ul>
 * 前置校验：text 非空 ≤200 字（HTTP 400）；卡存在（否则 404）且 PUBLISHED（否则 400）。
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

    private final CardMapper cards;
    private final CardVersionMapper cardVersions;
    private final KnowledgeAssetMapper assets;
    private final CardService cardService;
    private final NlIntentClassifier classifier;
    private final LlmGateway llm;
    private final ObjectMapper objectMapper;

    @Value("${draft.prompt.system}")
    private String draftSystemPrompt;

    public EntryDraftService(CardMapper cards, CardVersionMapper cardVersions, KnowledgeAssetMapper assets,
                             CardService cardService, NlIntentClassifier classifier, LlmGateway llm,
                             ObjectMapper objectMapper) {
        this.cards = cards;
        this.cardVersions = cardVersions;
        this.assets = assets;
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

        NlIntent intent = classifier.classify(input);
        return switch (intent) {
            case EXPLAIN, COMPARE -> agentServiceDraft(intent, card, input);
            case LINK_CARD -> linkCardDraft(card, input);
            case OUT_OF_SCOPE -> new DraftResult(intent, null, List.of(), OUT_OF_SCOPE_ADVICE);
        };
    }

    // ---------- EXPLAIN/COMPARE：生成档抽取 + 硬校验 + 自动收窄 ----------

    private DraftResult agentServiceDraft(NlIntent intent, CardEntity card, String text) {
        Map<Long, String> authorized = authorizedAssets(card.getCurrentVersionId());
        String user = "用户请求:" + text + "\n所在卡片:《" + card.getTitle() + "》"
                + (card.getSummaryText() == null || card.getSummaryText().isBlank()
                        ? "" : "\n卡片摘要:" + card.getSummaryText())
                + "\n可用资料清单(assetScope 只能从这里选 assetId):\n" + assetListText(authorized);

        DraftOutput output = null;
        Exception last = null;
        for (int attempt = 0; attempt < MAX_LLM_ATTEMPTS && output == null; attempt++) {
            try {
                String raw = llm.complete(new ChatCommand(draftSystemPrompt, user, ModelTier.GENERATOR));
                output = objectMapper.readValue(stripFences(raw), DraftOutput.class);
            } catch (Exception e) {
                last = e; // JSON 绑定失败 → 重试一次（共 2 次），与讲解流水线同则
            }
        }
        if (output == null) {
            throw new IllegalStateException("入口草稿抽取失败(已重试 1 次): "
                    + (last == null ? "未知原因" : last.getMessage()));
        }

        EntryConfig config = new EntryConfig(output.name(), EntryType.AGENT_SERVICE, output.goal(), null,
                output.serviceType(), output.assetScope() == null ? null
                        : Set.copyOf(output.assetScope()),
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
        // 跨主题判定与 RelationGuard 同则：任一 theme 缺失视为同主题（不要求三要件）
        boolean crossTheme = target.theme() != null && !target.theme().equals(card.getTheme());
        String name = truncate("关于" + text, MAX_DRAFT_NAME_CHARS);
        EntryConfig config = new EntryConfig(name, EntryType.LINK_CARD, null, null,
                null, null, null, null, target.id(), null, null);
        List<String> violations = EntryConfigValidator.validate(config, Set.of(), Set.of(), crossTheme);
        if (violations.isEmpty()) {
            return new DraftResult(NlIntent.LINK_CARD, config, List.of(), null);
        }
        // 跨主题命中但草稿不代填 why/source（出处须可查证，RelationGuard 键约定）→ 返回缺失要件
        return new DraftResult(NlIntent.LINK_CARD, null, violations, null);
    }

    // ---------- 授权资料清单（id→标题） ----------

    /**
     * 当前卡版本的授权资产清单（LinkedHashMap 保 sources 顺序）：card_version.sources[].assetId
     * → knowledge_asset（license_expire 为空或 ≥ 今天，与 RetrievalService 同一授权过滤）。
     * 解析失败/缺失资产静默跳过——受限集合只缩不涨。
     */
    private Map<Long, String> authorizedAssets(Long cardVersionId) {
        if (cardVersionId == null) {
            return Map.of();
        }
        CardVersionEntity version = cardVersions.selectById(cardVersionId);
        List<Long> assetIds = assetIdsOf(version == null ? null : version.getSources());
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeAssetEntity> rows = assets.selectList(new LambdaQueryWrapper<KnowledgeAssetEntity>()
                .in(KnowledgeAssetEntity::getId, assetIds)
                .and(w -> w.isNull(KnowledgeAssetEntity::getLicenseExpire)
                        .or().ge(KnowledgeAssetEntity::getLicenseExpire, LocalDate.now())));
        Map<Long, String> titles = new LinkedHashMap<>();
        for (KnowledgeAssetEntity asset : rows) {
            titles.put(asset.getId(), asset.getTitle());
        }
        Map<Long, String> ordered = new LinkedHashMap<>();
        for (Long assetId : assetIds) {
            String title = titles.get(assetId);
            if (title != null) {
                ordered.put(assetId, title);
            }
        }
        return ordered;
    }

    /** sources JSON 数组 → 非空 assetId（去重、保序）；非法 JSON 一律当无挂接 */
    private List<Long> assetIdsOf(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode sources = objectMapper.readTree(sourcesJson);
            if (sources == null || !sources.isArray()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            for (JsonNode source : sources) {
                JsonNode assetId = source == null ? null : source.get("assetId");
                if (assetId != null && !assetId.isNull() && assetId.canConvertToLong()) {
                    long id = assetId.longValue();
                    if (id > 0 && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        } catch (JsonProcessingException e) {
            return List.of();
        }
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
