package com.ke.service.card;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.domain.card.content.CardContent;
import com.ke.domain.card.content.CardContentValidator;
import com.ke.domain.card.content.CompareCardContent;
import com.ke.domain.card.content.TaskCardContent;
import com.ke.domain.card.content.TextCardContent;
import com.ke.domain.card.content.TimelineCardContent;
import com.ke.domain.enums.CardStatus;
import com.ke.domain.enums.ReviewStatus;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.ReviewTaskEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.ReviewTaskMapper;
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

/**
 * 卡片生命周期（FR-C07/C08）：
 * - 写路径一律先 {@link CardContentValidator#parseAndValidate}，再把校验后的 CardContent
 *   重序列化为规范 JSON 存库（丢弃未知字段，读出即 wire format）；
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
    private final ObjectMapper objectMapper;

    public CardService(CardMapper cards, CardVersionMapper versions, ReviewTaskMapper reviewTasks,
                       ObjectMapper objectMapper) {
        this.cards = cards;
        this.versions = versions;
        this.reviewTasks = reviewTasks;
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
                             JsonNode content, OffsetDateTime updatedAt) {
    }

    // ---------- 写路径（工作台） ----------

    @Transactional
    public Long create(String theme, String templateType, String title, String contentJson, long userId) {
        String type = normalizeType(templateType);
        String canonical = canonicalJson(type, contentJson);
        CardEntity card = new CardEntity();
        card.setTheme(theme);
        card.setTemplateType(type);
        card.setTitle(title);
        card.setStatus(CardStatus.DRAFT.name());
        card.setMaintainerId(userId);
        card.setSort(0);
        cards.insert(card);
        insertVersion(card.getId(), 1, canonical, userId);
        return card.getId();
    }

    /** 只追加新版本（不改旧行），返回新版本号 */
    @Transactional
    public int saveContent(long cardId, String contentJson, long userId) {
        CardEntity card = requireCard(cardId);
        String canonical = canonicalJson(card.getTemplateType(), contentJson);
        int next = nextVersionNo(cardId);
        insertVersion(cardId, next, canonical, userId);
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
                    version.getVersionNo(), objectMapper.readTree(version.getContentJson()), card.getUpdatedAt());
        } catch (JsonProcessingException e) {
            throw new BadRequestException("卡片内容损坏");
        }
    }

    // ---------- 内部 ----------

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

    private void insertVersion(long cardId, int versionNo, String contentJson, long userId) {
        CardVersionEntity v = new CardVersionEntity();
        v.setCardId(cardId);
        v.setVersionNo(versionNo);
        v.setContentJson(contentJson);
        v.setCreatedBy(userId);
        versions.insert(v);
    }

    /** MVP 并发策略：事务内 max(version_no)+1；UNIQUE(card_id, version_no) 兜底 */
    private int nextVersionNo(long cardId) {
        List<Object> max = versions.selectObjs(new QueryWrapper<CardVersionEntity>()
                .select("COALESCE(MAX(version_no), 0)").eq("card_id", cardId));
        Number value = (Number) max.get(0);
        return value.intValue() + 1;
    }

    /** 校验 + 规范化：未知字段丢弃，字段序与类型由 record 决定（存库即 wire format） */
    private String canonicalJson(String templateType, String contentJson) {
        CardContent content = CardContentValidator.parseAndValidate(templateType, contentJson);
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("content_json 序列化失败");
        }
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
