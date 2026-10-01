package com.ke.service.entry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.EntryEntity;
import com.ke.infra.mapper.CardMapper;
import org.springframework.stereotype.Component;

/**
 * 跨主题链接入口守卫（C09 / Task 23）：entry.target_card_id 非空且「目标卡 theme ≠ 入口所属卡
 * theme」（跨主题跳转）时，必须携带三要件——关系词 relationLabel、关系说明 config_json.why
 * （非空字符串）、出处 config_json.source（键约定：非空字符串描述，或非空对象如
 * {@code {"assetId":11,"quote":"…"}}；Task 28 接线时 UI 采集该键落库）。跨主题关系缺上下文
 * 或缺可查证出处就是无依据的突兀跳转，违背 C09「关系可解释」。同主题、无目标卡、卡缺失
 * （存在性由调用方保存链路负责）均不在本守卫范围。
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

    /** 跨主题入口必须 relationLabel 非空、config_json.why 非空且 config_json.source 非空，否则 IllegalArgumentException */
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
        JsonNode config = readConfig(entry.getConfigJson());
        if (!hasWhy(config)) {
            throw new IllegalArgumentException("跨主题入口必须说明关系原因(config_json.why)");
        }
        if (!hasSource(config)) {
            throw new IllegalArgumentException("跨主题入口必须注明出处(config_json.source)");
        }
    }

    /** config_json 解析：缺省/损坏一律 null（视为既无 why 也无 source），由各 hasXxx 统一判缺 */
    private JsonNode readConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(configJson);
        } catch (Exception e) {
            return null;
        }
    }

    /** config_json.why 非空判断：缺 config_json、损坏、无 why 键、why 空白均视为缺说明 */
    private boolean hasWhy(JsonNode config) {
        JsonNode why = config == null ? null : config.get("why");
        return why != null && !why.asText().isBlank();
    }

    /**
     * config_json.source 出处判断（C09 第三要件）：非空字符串描述，或非空对象（如 {assetId,quote}）；
     * 缺键/null/空串/空白/空对象以及数组、数字等约定外形态均视为缺出处。
     */
    private boolean hasSource(JsonNode config) {
        JsonNode source = config == null ? null : config.get("source");
        if (source == null || source.isNull()) {
            return false;
        }
        if (source.isObject()) {
            return source.size() > 0;
        }
        return source.isTextual() && !source.asText().isBlank();
    }
}
