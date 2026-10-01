package com.ke.entry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.EntryEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.service.entry.RelationGuard;

/**
 * 跨主题链接入口守卫单测（C09 / Task 23，直接构造实体，不起 Spring/DB）：
 * entry.target_card_id 非空且目标卡 theme ≠ 入口所属卡 theme（跨主题）时，
 * 必须 relationLabel 非空且 config_json.why 非空，否则 IllegalArgumentException
 * （Phase 6 Task 28 入口保存接线后映射 400 envelope；本任务交付 Guard + 单测，不接线）。
 */
class RelationGuardTest {

    private CardMapper cards;
    private RelationGuard guard;

    @BeforeEach
    void setUp() {
        cards = mock(CardMapper.class);
        guard = new RelationGuard(cards, new ObjectMapper());
    }

    private CardEntity card(long id, String theme) {
        CardEntity card = new CardEntity();
        card.setId(id);
        card.setTheme(theme);
        return card;
    }

    private EntryEntity entry(Long cardId, Long targetCardId, String relationLabel, String configJson) {
        EntryEntity entry = new EntryEntity();
        entry.setCardId(cardId);
        entry.setTargetCardId(targetCardId);
        entry.setRelationLabel(relationLabel);
        entry.setConfigJson(configJson);
        return entry;
    }

    @Test
    void crossThemeRequiresRelationLabelAndWhy() {
        when(cards.selectById(1L)).thenReturn(card(1L, "academy"));
        when(cards.selectById(9L)).thenReturn(card(9L, "folklore"));

        // 跨主题缺关系标签 → 拒绝
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guard.validateCrossTheme(entry(1L, 9L, null, "{\"why\":\"民俗对照\"}")))
                .withMessageContaining("关系标签");
        // 跨主题缺关系说明（config_json 无 why / why 空白 / config_json 损坏）→ 拒绝
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guard.validateCrossTheme(entry(1L, 9L, "相关联", "{\"note\":\"无 why\"}")))
                .withMessageContaining("关系原因");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guard.validateCrossTheme(entry(1L, 9L, "相关联", "{\"why\":\"   \"}")))
                .withMessageContaining("关系原因");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guard.validateCrossTheme(entry(1L, 9L, "相比较", "not-json{{{")))
                .withMessageContaining("关系原因");

        // 齐备（标签 + 非空 why）→ 放行
        assertThatCode(() -> guard.validateCrossTheme(
                entry(1L, 9L, "相比较", "{\"why\":\"书院民俗与民间信仰互为印证\"}")))
                .doesNotThrowAnyException();
    }

    @Test
    void sameThemeOrNoTargetSkipsCheck() {
        when(cards.selectById(1L)).thenReturn(card(1L, "academy"));
        when(cards.selectById(2L)).thenReturn(card(2L, "academy"));

        // 同主题：不要求标签与说明
        assertThatCode(() -> guard.validateCrossTheme(entry(1L, 2L, null, null)))
                .doesNotThrowAnyException();
        // 无目标卡：不属跨主题跳转，交给既有 LINK_CARD 守卫
        assertThatCode(() -> guard.validateCrossTheme(entry(1L, null, null, null)))
                .doesNotThrowAnyException();
        // 所属卡/目标卡不存在：存在性由调用方（Task 28 保存链路）负责，Guard 不误伤
        assertThatCode(() -> guard.validateCrossTheme(entry(1L, 999L, null, null)))
                .doesNotThrowAnyException();
    }
}
