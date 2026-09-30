package com.ke.service.card;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ke.domain.card.content.CardContent;
import com.ke.domain.card.content.CardContentValidator;
import com.ke.domain.card.content.CitationIndexValidator;
import com.ke.domain.card.content.CompareCardContent;
import com.ke.domain.card.content.TaskCardContent;
import com.ke.domain.card.content.TextCardContent;
import com.ke.domain.card.content.TimelineCardContent;
import com.ke.domain.enums.CardStatus;
import com.ke.domain.enums.ReviewStatus;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.CitationEntity;
import com.ke.infra.entity.KeUserEntity;
import com.ke.infra.entity.ReviewTaskEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.CitationMapper;
import com.ke.infra.mapper.KeUserMapper;
import com.ke.infra.mapper.KnowledgeAssetMapper;
import com.ke.infra.mapper.ReviewTaskMapper;
import com.ke.service.card.dto.SourceRef;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import com.ke.service.review.AuditId;
import com.ke.service.review.Audited;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 卡片生命周期（FR-C07/C08）：
 * - 写路径一律先 {@link CardContentValidator#parseAndValidate}，再把校验后的 CardContent
 *   重序列化为规范 JSON 存库（丢弃未知字段，读出即 wire format）；
 * - sources 契约：content 内 citations[n] 是 1-based 索引指向 sources 数组，写前经
 *   {@link CitationIndexValidator#check} 对齐数量；sources 同样以规范 JSON 存 card_version.sources，
 *   带 assetId 的来源须指向真实知识单元，发布时建 citation 行（FR-O02）；
 * - card_version 只 INSERT（版本不可变，DB 触发器兜底），version_no = max+1（事务内）；
 * - 状态流转严格走 {@link CardStatus#canComeFrom}（方向：目标.canComeFrom(来源)），
 *   非法来源 → 400；不存在/非 PUBLISHED 在公开端点 → 404（不泄露存在性）；
 * - 发布回填 summary_text（≤200 字，规则见 {@link #summaryOf}）与 current_version_id。
 */
@Service
public class CardService {

    static final int PAGE_SIZE = 10;
    static final int SUMMARY_MAX = 200;

    private final CardMapper cards;
    private final CardVersionMapper versions;
    private final ReviewTaskMapper reviewTasks;
    private final KeUserMapper users;
    private final KnowledgeAssetMapper assets;
    private final CitationMapper citations;
    private final ObjectMapper objectMapper;

    public CardService(CardMapper cards, CardVersionMapper versions, ReviewTaskMapper reviewTasks,
                       KeUserMapper users, KnowledgeAssetMapper assets, CitationMapper citations,
                       ObjectMapper objectMapper) {
        this.cards = cards;
        this.versions = versions;
        this.reviewTasks = reviewTasks;
        this.users = users;
        this.assets = assets;
        this.citations = citations;
        this.objectMapper = objectMapper;
    }

    /** 探索端列表页：items + nextCursor（null=没有更多）。ALWAYS 保证 nextCursor 字段恒在 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CardPage(List<CardListItem> items, String nextCursor) {
    }

    public record CardListItem(Long id, String theme, String templateType, String title,
                               String summaryText, Integer sort, OffsetDateTime updatedAt) {
    }

    public record CardDetail(Long id, String theme, String templateType, String title, Integer versionNo,
                             JsonNode content, JsonNode sources, OffsetDateTime updatedAt) {
    }

    /** 版本历史 item（工作台）：versionNo 倒序，created_by 关联昵称 */
    public record VersionItem(Integer versionNo, String createdByNickname, OffsetDateTime createdAt) {
    }

    // ---------- 写路径（工作台） ----------

    @Transactional
    public Long create(String theme, String templateType, String title, JsonNode content,
                       List<SourceRef> sources, long userId) {
        String type = normalizeType(templateType);
        WritePayload payload = canonicalize(type, content, sources);
        CardEntity card = new CardEntity();
        card.setTheme(theme);
        card.setTemplateType(type);
        card.setTitle(title);
        card.setStatus(CardStatus.DRAFT.name());
        card.setMaintainerId(userId);
        card.setSort(0);
        cards.insert(card);
        insertVersion(card.getId(), 1, payload, userId);
        return card.getId();
    }

    /** 只追加新版本（不改旧行），返回新版本号 */
    @Transactional
    public int saveContent(long cardId, JsonNode content, List<SourceRef> sources, long userId) {
        CardEntity card = requireCard(cardId);
        WritePayload payload = canonicalize(card.getTemplateType(), content, sources);
        int next = nextVersionNo(cardId);
        insertVersion(cardId, next, payload, userId);
        card.setUpdatedAt(OffsetDateTime.now());
        cards.updateById(card);
        return next;
    }

    /** DRAFT → PENDING，并在同事务挂入审核队列（FR-O03） */
    @Transactional
    @Audited(action = "CARD_SUBMIT", objectType = "CARD")
    public CardEntity submit(@AuditId long cardId) {
        CardEntity card = transition(cardId, CardStatus.PENDING);
        ReviewTaskEntity task = new ReviewTaskEntity();
        task.setObjectType("CARD");
        task.setObjectId(card.getId());
        task.setAction("SUBMIT");
        task.setStatus(ReviewStatus.PENDING.name());
        reviewTasks.insert(task);
        return card;
    }

    /** PENDING → PUBLISHED，回填当前版本 summary 与 current_version_id（审核通过即委托到此） */
    @Transactional
    @Audited(action = "CARD_PUBLISH", objectType = "CARD")
    public CardEntity publish(@AuditId Long cardId) {
        CardEntity card = transition(cardId, CardStatus.PUBLISHED);
        CardVersionEntity latest = versions.selectOne(new LambdaQueryWrapper<CardVersionEntity>()
                .eq(CardVersionEntity::getCardId, cardId)
                .orderByDesc(CardVersionEntity::getVersionNo)
                .last("LIMIT 1"));
        if (latest == null) {
            throw new BadRequestException("卡片没有可发布的版本");
        }
        // 发布前再校验当前内容，防止历史版本因规则演进变为非法
        CardContent content = CardContentValidator.parseAndValidate(card.getTemplateType(), latest.getContentJson());
        card.setCurrentVersionId(latest.getId());
        card.setSummaryText(truncate(summaryOf(content), SUMMARY_MAX));
        card.setUpdatedAt(OffsetDateTime.now());
        cards.updateById(card);
        // 卡→资产挂接（FR-O02）：为当前版本的带 assetId 来源建 citation 行（幂等：先清后建）
        linkCitations(latest.getId(), latest.getSources());
        return card;
    }

    @Transactional
    public CardEntity disable(long cardId) {
        return transition(cardId, CardStatus.DISABLED);
    }

    /** 驳回回落：PENDING → DRAFT（审核 reject 委托到此，FR-O03） */
    @Transactional
    @Audited(action = "CARD_RETURN_DRAFT", objectType = "CARD")
    public CardEntity returnToDraft(@AuditId Long cardId) {
        return transition(cardId, CardStatus.DRAFT);
    }

    // ---------- 读路径（探索端，仅 PUBLISHED） ----------

    @Transactional(readOnly = true)
    public CardPage list(String theme, String q, String cursor) {
        QueryWrapper<CardEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("status", CardStatus.PUBLISHED.name());
        if (theme != null && !theme.isBlank()) {
            wrapper.eq("theme", theme.trim());
        }
        if (q != null && !q.isBlank()) {
            wrapper.apply("(title ILIKE {0} OR summary_text ILIKE {0})", "%" + q.trim() + "%");
        }
        if (cursor != null && !cursor.isBlank()) {
            long[] key = decodeCursor(cursor);
            wrapper.and(w -> w.gt("sort", key[0]).or(ww -> ww.eq("sort", key[0]).gt("id", key[1])));
        }
        wrapper.orderByAsc("sort", "id").last("LIMIT " + (PAGE_SIZE + 1));

        List<CardEntity> rows = cards.selectList(wrapper);
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<CardEntity> items = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        String nextCursor = hasMore ? encodeCursor(items.get(items.size() - 1)) : null;
        return new CardPage(items.stream()
                .map(c -> new CardListItem(c.getId(), c.getTheme(), c.getTemplateType(), c.getTitle(),
                        c.getSummaryText(), c.getSort(), c.getUpdatedAt()))
                .toList(), nextCursor);
    }

    /** 不存在或非 PUBLISHED 一律 404（不泄露草稿/待审卡的存在性） */
    @Transactional(readOnly = true)
    public CardDetail detail(long id) {
        CardEntity card = cards.selectById(id);
        if (card == null || !CardStatus.PUBLISHED.name().equals(card.getStatus())
                || card.getCurrentVersionId() == null) {
            throw new NotFoundException("卡片不存在");
        }
        CardVersionEntity version = versions.selectById(card.getCurrentVersionId());
        if (version == null) {
            throw new NotFoundException("卡片不存在");
        }
        try {
            return new CardDetail(card.getId(), card.getTheme(), card.getTemplateType(), card.getTitle(),
                    version.getVersionNo(), objectMapper.readTree(version.getContentJson()),
                    sourcesNode(version.getSources()), card.getUpdatedAt());
        } catch (JsonProcessingException e) {
            throw new BadRequestException("卡片内容损坏");
        }
    }

    /** 版本历史（工作台）：versionNo 倒序，created_by 关联 ke_user.nickname */
    @Transactional(readOnly = true)
    public List<VersionItem> versionsOf(long cardId) {
        requireCard(cardId);
        List<CardVersionEntity> rows = versions.selectList(new LambdaQueryWrapper<CardVersionEntity>()
                .eq(CardVersionEntity::getCardId, cardId)
                .orderByDesc(CardVersionEntity::getVersionNo));
        Set<Long> userIds = rows.stream()
                .map(CardVersionEntity::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> nicknames = userIds.isEmpty() ? Map.of()
                : users.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(KeUserEntity::getId, u ->
                                u.getNickname() == null ? "" : u.getNickname()));
        return rows.stream()
                .map(v -> new VersionItem(v.getVersionNo(),
                        v.getCreatedBy() == null ? null : nicknames.get(v.getCreatedBy()), v.getCreatedAt()))
                .toList();
    }

    // ---------- 内部 ----------

    /** 一次写版本的规范化产物：content 与 sources 的规范 JSON（null = 无来源） */
    private record WritePayload(String contentJson, String sourcesJson) {
    }

    /**
     * 写前规范化（create/saveContent 共用）：
     * ① content 必须是 JSON 对象；② 模板结构校验后重序列化为规范 camelCase JSON；
     * ③ citations 索引对齐 sources 数量（1-based）；④ assetId 非空的来源须指向真实知识单元；
     * ⑤ sources 同样产出规范 JSON（空列表存 NULL）。
     */
    private WritePayload canonicalize(String templateType, JsonNode content, List<SourceRef> sources) {
        if (content == null || content.isNull() || !content.isObject()) {
            throw new BadRequestException("content 必须是 JSON 对象");
        }
        List<SourceRef> safeSources = sources == null ? List.of() : sources;
        CardContent parsed = CardContentValidator.parseAndValidate(templateType, content.toString());
        CitationIndexValidator.check(parsed, safeSources.size());
        checkAssetRefs(safeSources);
        try {
            String sourcesJson = safeSources.isEmpty() ? null : objectMapper.writeValueAsString(safeSources);
            return new WritePayload(objectMapper.writeValueAsString(parsed), sourcesJson);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("content_json 序列化失败");
        }
    }

    private void checkAssetRefs(List<SourceRef> sources) {
        for (SourceRef source : sources) {
            if (source != null && source.assetId() != null && assets.selectById(source.assetId()) == null) {
                throw new BadRequestException("来源引用的知识单元不存在");
            }
        }
    }

    private CardEntity requireCard(long cardId) {
        CardEntity card = cards.selectById(cardId);
        if (card == null) {
            throw new NotFoundException("卡片不存在");
        }
        return card;
    }

    private CardEntity transition(long cardId, CardStatus target) {
        CardEntity card = requireCard(cardId);
        CardStatus from = CardStatus.valueOf(card.getStatus());
        if (!target.canComeFrom(from)) {
            throw new BadRequestException("状态不允许该操作: " + from + " → " + target);
        }
        card.setStatus(target.name());
        card.setUpdatedAt(OffsetDateTime.now());
        cards.updateById(card);
        return card;
    }

    private void insertVersion(long cardId, int versionNo, WritePayload payload, long userId) {
        CardVersionEntity v = new CardVersionEntity();
        v.setCardId(cardId);
        v.setVersionNo(versionNo);
        v.setContentJson(payload.contentJson());
        v.setSources(payload.sourcesJson());
        v.setCreatedBy(userId);
        versions.insert(v);
    }

    /**
     * 卡→资产挂接（FR-O02）：object_type='card_version'、object_id=版本 id。
     * 先 DELETE 该版本的既有行再 INSERT（重复发布幂等，不产生重复挂接）；
     * 每个带 assetId 的 source 一行，locator 记为 {"ref": <来源定位>}，quote 留空。
     */
    private void linkCitations(long versionId, String sourcesJson) {
        citations.delete(new QueryWrapper<CitationEntity>()
                .eq("object_type", "card_version")
                .eq("object_id", versionId));
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return;
        }
        JsonNode sources;
        try {
            sources = objectMapper.readTree(sourcesJson);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("卡片来源数据损坏");
        }
        if (sources == null || !sources.isArray()) {
            return;
        }
        for (JsonNode source : sources) {
            JsonNode assetId = source.get("assetId");
            if (assetId == null || assetId.isNull() || !assetId.canConvertToLong()) {
                continue;
            }
            CitationEntity citation = new CitationEntity();
            citation.setAssetId(assetId.longValue());
            ObjectNode locator = objectMapper.createObjectNode();
            locator.put("ref", source.path("locator").asText(null));
            try {
                citation.setLocator(objectMapper.writeValueAsString(locator));
            } catch (JsonProcessingException e) {
                throw new BadRequestException("来源定位序列化失败");
            }
            citation.setQuote(null);
            citation.setObjectType("card_version");
            citation.setObjectId(versionId);
            citations.insert(citation);
        }
    }

    /** sources 存库 JSON → 响应节点；无来源时给空数组（契约恒有 sources 字段） */
    private JsonNode sourcesNode(String sourcesJson) throws JsonProcessingException {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return objectMapper.createArrayNode();
        }
        JsonNode node = objectMapper.readTree(sourcesJson);
        return node.isArray() ? node : objectMapper.createArrayNode();
    }

    /** MVP 并发策略：事务内 max(version_no)+1；UNIQUE(card_id, version_no) 兜底 */
    private int nextVersionNo(long cardId) {
        List<Object> max = versions.selectObjs(new QueryWrapper<CardVersionEntity>()
                .select("COALESCE(MAX(version_no), 0)").eq("card_id", cardId));
        Number value = (Number) max.get(0);
        return value.intValue() + 1;
    }

    private static String normalizeType(String templateType) {
        if (templateType == null || templateType.isBlank()) {
            throw new BadRequestException("模板类型缺失");
        }
        String type = templateType.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "TEXT", "COMPARE", "TIMELINE", "TASK" -> type;
            default -> throw new BadRequestException("未知模板类型: " + templateType);
        };
    }

    /** 发布时合成的检索摘要；TEXT 用作者 summary，其余取首要素（截 200 字） */
    static String summaryOf(CardContent content) {
        if (content instanceof TextCardContent t) {
            return t.summary();
        }
        if (content instanceof CompareCardContent c) {
            return "对比：" + String.join(" vs ", c.objects());
        }
        if (content instanceof TimelineCardContent tl && !tl.events().isEmpty()) {
            return tl.events().get(0).title();
        }
        if (content instanceof TaskCardContent task) {
            return task.goal();
        }
        return "";
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ---------- keyset 游标（(sort,id) 的 base64url 不透明编码） ----------

    private static final Base64.Encoder CURSOR_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder CURSOR_DECODER = Base64.getUrlDecoder();

    static String encodeCursor(CardEntity card) {
        String raw = card.getSort() + ":" + card.getId();
        return CURSOR_ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static long[] decodeCursor(String cursor) {
        try {
            String[] parts = new String(CURSOR_DECODER.decode(cursor), StandardCharsets.UTF_8).split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("cursor 形状非法");
            }
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (RuntimeException e) {
            throw new BadRequestException("无效的分页游标");
        }
    }
}
