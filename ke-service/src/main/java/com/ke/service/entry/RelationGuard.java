package com.ke.service.entry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.EntryEntity;
import com.ke.infra.mapper.CardMapper;
import org.springframework.stereotype.Component;

/**
 * 跨主题链接入口守卫（C09 / Task 23）：entry.target_card_id 非空且「目标卡 theme ≠ 入口所属卡
 * theme」（跨主题跳转）时，必须携带关系词 relationLabel 与关系说明 config_json.why——跨主题关系
 * 缺上下文就是无解释的突兀跳转，违背 C09「关系可解释」。同主题、无目标卡、卡缺失（存在性由
 * 调用方保存链路负责）均不在本守卫范围。
 *
 * <p>Phase 6 Task 28 入口保存（新增/更新）时调用；本任务交付 Guard + 单测（RelationGuardTest，
 * 直接构造实体断言），不接线。非法抛 {@link IllegalArgumentException}，接线侧映射 400 envelope。
 */
@Component
public class RelationGuard {

    private final CardMapper cards;
    private final ObjectMapper objectMapper;

    public RelationGuard(CardMapper cards, ObjectMapper objectMapper) {
        this.cards = cards;
        this.objectMapper = objectMapper;
    }

    /** 跨主题入口必须 relationLabel 非空且 config_json.why 非空，否则 IllegalArgumentException */
    public void validateCrossTheme(EntryEntity entry) {
        if (entry.getTargetCardId() == null) {
            return;
        }
        CardEntity owner = entry.getCardId() == null ? null : cards.selectById(entry.getCardId());
        CardEntity target = cards.selectById(entry.getTargetCardId());
        if (owner == null || target == null
                || owner.getTheme() == null || owner.getTheme().equals(target.getTheme())) {
            return;
        }
        if (entry.getRelationLabel() == null || entry.getRelationLabel().isBlank()) {
            throw new IllegalArgumentException("跨主题入口必须携带关系标签");
        }
        if (!hasWhy(entry.getConfigJson())) {
            throw new IllegalArgumentException("跨主题入口必须说明关系原因(config_json.why)");
        }
    }

    /** config_json.why 非空判断：缺 config_json、损坏、无 why 键、why 空白均视为缺说明 */
    private boolean hasWhy(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return false;
        }
        try {
            JsonNode why = objectMapper.readTree(configJson).get("why");
            return why != null && !why.asText().isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}
