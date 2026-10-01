package com.ke.service.review;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.ke.domain.card.content.CardContent;
import com.ke.domain.card.content.CardContentValidator;
import com.ke.domain.card.content.CompareCardContent;
import com.ke.domain.card.content.TaskCardContent;
import com.ke.domain.card.content.TextCardContent;
import com.ke.domain.card.content.TimelineCardContent;
import com.ke.domain.enums.ReviewStatus;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.EntryEntity;
import com.ke.infra.entity.KeUserEntity;
import com.ke.infra.entity.ReviewTaskEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.EntryMapper;
import com.ke.infra.mapper.KeUserMapper;
import com.ke.infra.mapper.ReviewTaskMapper;
import com.ke.service.card.CardService;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;

/**
 * 审核工作流（FR-O03）：工作台队列的查询与裁决。
 * <ul>
 *   <li>队列 item：id/objectType/objectId/action/status/createdAt + summary（CARD＝
 *       标题 · 模板类型 · 提交人昵称；ENTRY＝入口名 · 所属卡题 · 提交人昵称，Task 28）+
 *       precheck（CARD＝contentValid 当前版本内容可过校验、hasSources 来源非空；ENTRY 无卡片
 *       内容语义，恒为 null——前端需容错）+
 *       contentPreview（CARD＝当前版本内容大意：TEXT 取 summary、COMPARE/TIMELINE/TASK 取
 *       对应摘要，截 100 字；内容不可解析或非 CARD 为 null）——审批人不点开即可见内容大意；</li>
 *   <li>队列查询为 offset 分页（page 从 1 起，size 夹取 [1,100] 默认 20），返回
 *       {items, total, page, size}；</li>
 *   <li>approve：任务置 APPROVED 并委托对象动作（CARD → {@link CardService#publish}，
 *       其 @Audited 切面落 CARD_PUBLISH；ENTRY → 入口无 publish 概念，维持 ACTIVE 即 Task 28
 *       决策——公共入口创建即 ACTIVE，审核通过即维持不动）；</li>
 *   <li>reject：notes 必填，任务置 REJECTED，CARD 经 returnToDraft 回 DRAFT，
 *       ENTRY 置入口 DISABLED（驳回即下架，作者卡页不再可见）；</li>
 *   <li>自审禁绝：审核人 = 提交人（CARD=card.maintainer_id；ENTRY=entry.author_id，Task 28）
 *       → AccessDeniedException → 403 envelope。</li>
 * </ul>
 */
@Service
public class ReviewService {

    /** 与工作台卡片列表一致的 offset 分页上界 */
    private static final int MAX_PAGE_SIZE = 100;

    /** contentPreview 截断长度：审批人扫一眼即知大意 */
    private static final int MAX_PREVIEW_LENGTH = 100;

    private final ReviewTaskMapper reviewTasks;
    private final CardMapper cards;
    private final CardVersionMapper versions;
    private final EntryMapper entries;
    private final KeUserMapper users;
    private final CardService cardService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ReviewService(ReviewTaskMapper reviewTasks, CardMapper cards, CardVersionMapper versions,
                         EntryMapper entries, KeUserMapper users, CardService cardService,
                         com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.reviewTasks = reviewTasks;
        this.cards = cards;
        this.versions = versions;
        this.entries = entries;
        this.users = users;
        this.cardService = cardService;
        this.objectMapper = objectMapper;
    }

    /** 队列 item：precheck/contentPreview 仅 CARD 有值（contentValid/hasSources/内容大意），其余对象为 null */
    public record ReviewPrecheck(boolean contentValid, boolean hasSources) {
    }

    public record ReviewItem(Long id, String objectType, Long objectId, String action, String status,
                             java.time.OffsetDateTime createdAt, String summary, ReviewPrecheck precheck,
                             String contentPreview) {
    }

    /** 队列分页响应：page 从 1 起，size 夹取 [1,100] 默认 20，total 恒在 */
    public record ReviewPage(List<ReviewItem> items, long total, int page, int size) {
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public ReviewPage page(String status, String objectType, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = reviewTasks.selectCount(queueFilter(status, objectType));
        QueryWrapper<ReviewTaskEntity> listWrapper = queueFilter(status, objectType)
                .orderByAsc("id")
                .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize);
        List<ReviewItem> items = reviewTasks.selectList(listWrapper).stream().map(this::toItem).toList();
        return new ReviewPage(items, total, safePage, safeSize);
    }

    /** 队列过滤：status 空缺省 PENDING，objectType 可选 */
    private QueryWrapper<ReviewTaskEntity> queueFilter(String status, String objectType) {
        QueryWrapper<ReviewTaskEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("status", status == null || status.isBlank() ? ReviewStatus.PENDING.name() : status.trim());
        if (objectType != null && !objectType.isBlank()) {
            wrapper.eq("object_type", objectType.trim());
        }
        return wrapper;
    }

    private ReviewItem toItem(ReviewTaskEntity task) {
        String summary = null;
        ReviewPrecheck precheck = null;
        String contentPreview = null;
        if ("CARD".equals(task.getObjectType())) {
            CardEntity card = cards.selectById(task.getObjectId());
            if (card != null) {
                summary = card.getTitle() + " · " + card.getTemplateType() + " · " + nicknameOf(card.getMaintainerId());
            }
            CardVersionEntity latest = latestVersion(task.getObjectId());
            precheck = precheckOf(card, latest);
            contentPreview = previewOf(card, latest);
        } else if ("ENTRY".equals(task.getObjectType())) {
            // Task 28：ENTRY 摘要 = 入口名 · 所属卡题 · 提交人昵称；precheck/contentPreview 无卡片内容语义，恒 null
            EntryEntity entry = entries.selectById(task.getObjectId());
            if (entry != null) {
                CardEntity owner = entry.getCardId() == null ? null : cards.selectById(entry.getCardId());
                summary = entry.getName() + " · " + (owner == null ? "—" : owner.getTitle())
                        + " · " + nicknameOf(entry.getAuthorId());
            }
        }
        return new ReviewItem(task.getId(), task.getObjectType(), task.getObjectId(), task.getAction(),
                task.getStatus(), task.getCreatedAt(), summary, precheck, contentPreview);
    }

    private String nicknameOf(Long maintainerId) {
        if (maintainerId == null) {
            return null;
        }
        KeUserEntity user = users.selectById(maintainerId);
        return user == null ? null : user.getNickname();
    }

    private CardVersionEntity latestVersion(long cardId) {
        return versions.selectOne(new LambdaQueryWrapper<CardVersionEntity>()
                .eq(CardVersionEntity::getCardId, cardId)
                .orderByDesc(CardVersionEntity::getVersionNo)
                .last("LIMIT 1"));
    }

    /** 诚实且廉价的卡片预检：当前版本内容过校验 + 来源非空（无版本/内容损坏按不合格） */
    private ReviewPrecheck precheckOf(CardEntity card, CardVersionEntity latest) {
        if (latest == null) {
            return new ReviewPrecheck(false, false);
        }
        boolean contentValid;
        try {
            CardContentValidator.parseAndValidate(card == null ? null : card.getTemplateType(), latest.getContentJson());
            contentValid = true;
        } catch (RuntimeException e) {
            contentValid = false;
        }
        return new ReviewPrecheck(contentValid, hasSources(latest));
    }

    /** 内容大意：解析成功才给（TEXT=summary，COMPARE/TIMELINE/TASK=对应摘要），截 100 字 */
    private String previewOf(CardEntity card, CardVersionEntity latest) {
        if (latest == null) {
            return null;
        }
        try {
            CardContent content = CardContentValidator.parseAndValidate(
                    card == null ? null : card.getTemplateType(), latest.getContentJson());
            return truncate(describe(content), MAX_PREVIEW_LENGTH);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 四模板 → 摘要文本（供队列 contentPreview） */
    private static String describe(CardContent content) {
        if (content instanceof TextCardContent t) {
            return t.summary();
        }
        if (content instanceof TaskCardContent k) {
            return k.goal();
        }
        if (content instanceof CompareCardContent c) {
            return String.join(" vs ", c.objects()) + " · " + String.join("、", c.dimensions());
        }
        if (content instanceof TimelineCardContent tl && !tl.events().isEmpty()) {
            TimelineCardContent.Event first = tl.events().get(0);
            String head = first.year() + " " + first.title();
            return tl.events().size() > 1 ? head + " 等 " + tl.events().size() + " 条" : head;
        }
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    private boolean hasSources(CardVersionEntity version) {
        String sources = version.getSources();
        if (sources == null || sources.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(sources);
            if (node == null || node.isNull()) {
                return false;
            }
            return node.isArray() || node.isObject() ? !node.isEmpty() : true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    // ---------- 裁决 ----------

    /** 通过：PENDING→APPROVED 条件更新抢并发锁（只可能一人成功）→ 再委托对象动作（CARD 的
     *  CARD_PUBLISH 审计由切面在其 publish 内落库） */
    @Transactional
    public ReviewItem approve(long reviewId, long reviewerId, String notes) {
        ReviewTaskEntity task = requireTask(reviewId);
        guardNotSelf(task, reviewerId);
        transitionPending(reviewId, ReviewStatus.APPROVED, reviewerId,
                notes != null && !notes.isBlank() ? notes.trim() : null);
        dispatch(task);
        return toItem(reviewTasks.selectById(reviewId));
    }

    /** 驳回：notes 必填；自审禁绝（同 approve）；PENDING→REJECTED 条件更新抢并发锁 →
     *  对象回到来源状态（CARD → DRAFT） */
    @Transactional
    public ReviewItem reject(long reviewId, long reviewerId, String notes) {
        if (notes == null || notes.isBlank()) {
            throw new BadRequestException("notes 不能为空");
        }
        ReviewTaskEntity task = requireTask(reviewId);
        guardNotSelf(task, reviewerId);
        transitionPending(reviewId, ReviewStatus.REJECTED, reviewerId, notes.trim());
        dispatchReturn(task);
        return toItem(reviewTasks.selectById(reviewId));
    }

    // ---------- 内部 ----------

    private ReviewTaskEntity requireTask(long reviewId) {
        ReviewTaskEntity task = reviewTasks.selectById(reviewId);
        if (task == null) {
            throw new NotFoundException("审核任务不存在");
        }
        return task;
    }

    /**
     * PENDING 条件更新（CAS）：UPDATE ... WHERE id=? AND status='PENDING'。
     * 「先读后判」存在并发窗口（两人同时 approve 双双过守卫 → 双审计行、reviewer 互相覆盖），
     * 落库必须带状态谓词：命中 0 行 = 已被他人处理 → 400。业务动作（publish/returnToDraft）
     * 只在本方法成功后执行，失败则整个事务回滚、任务保持 PENDING。
     */
    private void transitionPending(long reviewId, ReviewStatus target, long reviewerId, String notes) {
        LambdaUpdateWrapper<ReviewTaskEntity> cas = new LambdaUpdateWrapper<ReviewTaskEntity>()
                .eq(ReviewTaskEntity::getId, reviewId)
                .eq(ReviewTaskEntity::getStatus, ReviewStatus.PENDING.name())
                .set(ReviewTaskEntity::getStatus, target.name())
                .set(ReviewTaskEntity::getReviewerId, reviewerId)
                .set(ReviewTaskEntity::getUpdatedAt, java.time.OffsetDateTime.now());
        if (notes != null) {
            cas.set(ReviewTaskEntity::getNotes, notes);
        }
        if (reviewTasks.update(cas) == 0) {
            throw new BadRequestException("该审核任务已被处理");
        }
    }

    /** 自审禁绝：提交人（CARD=card.maintainer_id；ENTRY=entry.author_id，Task 28）与审核人相同
     *  → 403 语义，交给 envelope */
    private void guardNotSelf(ReviewTaskEntity task, long reviewerId) {
        Long submitterId = null;
        if ("CARD".equals(task.getObjectType())) {
            CardEntity card = cards.selectById(task.getObjectId());
            submitterId = card == null ? null : card.getMaintainerId();
        } else if ("ENTRY".equals(task.getObjectType())) {
            EntryEntity entry = entries.selectById(task.getObjectId());
            submitterId = entry == null ? null : entry.getAuthorId();
        }
        if (submitterId != null && submitterId == reviewerId) {
            throw new AccessDeniedException("不能审核自己提交的内容");
        }
    }

    /** approve 委托：CARD 发布；ENTRY 入口无 publish 概念——创建即 ACTIVE，审核通过维持不动（Task 28 决策） */
    private void dispatch(ReviewTaskEntity task) {
        switch (task.getObjectType()) {
            case "CARD" -> cardService.publish(task.getObjectId());
            case "ENTRY" -> { }
            default -> throw new BadRequestException("未知审核对象类型: " + task.getObjectType());
        }
    }

    /** reject 委托：CARD 回 DRAFT；ENTRY 置 DISABLED（驳回即下架，作者卡页不再可见） */
    private void dispatchReturn(ReviewTaskEntity task) {
        switch (task.getObjectType()) {
            case "CARD" -> cardService.returnToDraft(task.getObjectId());
            case "ENTRY" -> {
                EntryEntity entry = entries.selectById(task.getObjectId());
                if (entry != null) {
                    entry.setStatus("DISABLED");
                    entry.setUpdatedAt(java.time.OffsetDateTime.now());
                    entries.updateById(entry);
                }
            }
            default -> throw new BadRequestException("未知审核对象类型: " + task.getObjectType());
        }
    }
}
