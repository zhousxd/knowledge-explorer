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
import com.ke.domain.enums.ReviewStatus;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.KeUserEntity;
import com.ke.infra.entity.ReviewTaskEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.KeUserMapper;
import com.ke.infra.mapper.ReviewTaskMapper;
import com.ke.service.card.CardService;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;

/**
 * 审核工作流（FR-O03）：工作台队列的查询与裁决。
 * <ul>
 *   <li>队列 item：id/objectType/objectId/action/status/createdAt + summary（CARD＝
 *       标题 · 模板类型 · 提交人昵称）+ precheck（CARD＝contentValid 当前版本内容可过校验、
 *       hasSources 来源非空；ENTRY 等 Phase 1 Task 6 接入后才有意义，暂为 null）；</li>
 *   <li>approve：任务置 APPROVED 并委托对象动作（CARD → {@link CardService#publish}，
 *       其 @Audited 切面落 CARD_PUBLISH；ENTRY → 400 待 Task 6）；</li>
 *   <li>reject：notes 必填，任务置 REJECTED，CARD 经 returnToDraft 回 DRAFT；</li>
 *   <li>自审禁绝：审核人 = 提交人（card.maintainer_id）→ AccessDeniedException → 403 envelope。</li>
 * </ul>
 */
@Service
public class ReviewService {

    private final ReviewTaskMapper reviewTasks;
    private final CardMapper cards;
    private final CardVersionMapper versions;
    private final KeUserMapper users;
    private final CardService cardService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ReviewService(ReviewTaskMapper reviewTasks, CardMapper cards, CardVersionMapper versions,
                         KeUserMapper users, CardService cardService,
                         com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.reviewTasks = reviewTasks;
        this.cards = cards;
        this.versions = versions;
        this.users = users;
        this.cardService = cardService;
        this.objectMapper = objectMapper;
    }

    /** 队列 item：precheck 仅 CARD 有值（contentValid/hasSources），其余对象为 null */
    public record ReviewPrecheck(boolean contentValid, boolean hasSources) {
    }

    public record ReviewItem(Long id, String objectType, Long objectId, String action, String status,
                             java.time.OffsetDateTime createdAt, String summary, ReviewPrecheck precheck) {
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public List<ReviewItem> list(String status, String objectType) {
        QueryWrapper<ReviewTaskEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("status", status == null || status.isBlank() ? ReviewStatus.PENDING.name() : status.trim());
        if (objectType != null && !objectType.isBlank()) {
            wrapper.eq("object_type", objectType.trim());
        }
        wrapper.orderByAsc("id");
        return reviewTasks.selectList(wrapper).stream().map(this::toItem).toList();
    }

    private ReviewItem toItem(ReviewTaskEntity task) {
        String summary = null;
        ReviewPrecheck precheck = null;
        if ("CARD".equals(task.getObjectType())) {
            CardEntity card = cards.selectById(task.getObjectId());
            if (card != null) {
                summary = card.getTitle() + " · " + card.getTemplateType() + " · " + nicknameOf(card.getMaintainerId());
            }
            precheck = cardPrecheck(task.getObjectId());
        }
        return new ReviewItem(task.getId(), task.getObjectType(), task.getObjectId(), task.getAction(),
                task.getStatus(), task.getCreatedAt(), summary, precheck);
    }

    private String nicknameOf(Long maintainerId) {
        if (maintainerId == null) {
            return null;
        }
        KeUserEntity user = users.selectById(maintainerId);
        return user == null ? null : user.getNickname();
    }

    /** 诚实且廉价的卡片预检：当前版本内容过校验 + 来源非空（无版本/内容损坏按不合格） */
    private ReviewPrecheck cardPrecheck(long cardId) {
        CardVersionEntity latest = versions.selectOne(new LambdaQueryWrapper<CardVersionEntity>()
                .eq(CardVersionEntity::getCardId, cardId)
                .orderByDesc(CardVersionEntity::getVersionNo)
                .last("LIMIT 1"));
        if (latest == null) {
            return new ReviewPrecheck(false, false);
        }
        boolean contentValid;
        try {
            CardEntity card = cards.selectById(cardId);
            CardContentValidator.parseAndValidate(card == null ? null : card.getTemplateType(), latest.getContentJson());
            contentValid = true;
        } catch (RuntimeException e) {
            contentValid = false;
        }
        return new ReviewPrecheck(contentValid, hasSources(latest));
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

    /** 自审禁绝：提交人（card.maintainer_id）与审核人相同 → 403 语义，交给 envelope */
    private void guardNotSelf(ReviewTaskEntity task, long reviewerId) {
        if (!"CARD".equals(task.getObjectType())) {
            return;
        }
        CardEntity card = cards.selectById(task.getObjectId());
        if (card != null && card.getMaintainerId() != null && card.getMaintainerId() == reviewerId) {
            throw new AccessDeniedException("不能审核自己提交的内容");
        }
    }

    private void dispatch(ReviewTaskEntity task) {
        switch (task.getObjectType()) {
            case "CARD" -> cardService.publish(task.getObjectId());
            case "ENTRY" -> throw new BadRequestException("入口审核在 Task 6 接入");
            default -> throw new BadRequestException("未知审核对象类型: " + task.getObjectType());
        }
    }

    private void dispatchReturn(ReviewTaskEntity task) {
        switch (task.getObjectType()) {
            case "CARD" -> cardService.returnToDraft(task.getObjectId());
            case "ENTRY" -> throw new BadRequestException("入口审核在 Task 6 接入");
            default -> throw new BadRequestException("未知审核对象类型: " + task.getObjectType());
        }
    }
}
