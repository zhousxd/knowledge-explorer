package com.ke.service.review;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

    /** 通过：任务 APPROVED → 委托对象动作（CARD 的 CARD_PUBLISH 审计由切面在其 publish 内落库） */
    @Transactional
    public ReviewItem approve(long reviewId, long reviewerId, String notes) {
        ReviewTaskEntity task = requirePending(reviewId);
        guardNotSelf(task, reviewerId);
        dispatch(task);
        task.setStatus(ReviewStatus.APPROVED.name());
        applyReviewerNotes(task, reviewerId, notes);
        return toItem(updated(task));
    }

    /** 驳回：notes 必填；任务 REJECTED，对象回到来源状态（CARD → DRAFT） */
    @Transactional
    public ReviewItem reject(long reviewId, long reviewerId, String notes) {
        if (notes == null || notes.isBlank()) {
            throw new BadRequestException("notes 不能为空");
        }
        ReviewTaskEntity task = requirePending(reviewId);
        task.setStatus(ReviewStatus.REJECTED.name());
        task.setNotes(notes.trim());
        dispatchReturn(task);
        applyReviewerNotes(task, reviewerId, task.getNotes());
        return toItem(updated(task));
    }

    // ---------- 内部 ----------

    private ReviewTaskEntity requirePending(long reviewId) {
        ReviewTaskEntity task = reviewTasks.selectById(reviewId);
        if (task == null) {
            throw new NotFoundException("审核任务不存在");
        }
        if (!ReviewStatus.PENDING.name().equals(task.getStatus())) {
            throw new BadRequestException("该审核任务已处理");
        }
        return task;
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

    private void applyReviewerNotes(ReviewTaskEntity task, long reviewerId, String notes) {
        task.setReviewerId(reviewerId);
        if (notes != null && !notes.isBlank()) {
            task.setNotes(notes.trim());
        }
        task.setUpdatedAt(java.time.OffsetDateTime.now());
    }

    private ReviewTaskEntity updated(ReviewTaskEntity task) {
        reviewTasks.updateById(task);
        return task;
    }
}
