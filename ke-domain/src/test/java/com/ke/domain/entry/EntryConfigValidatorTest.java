package com.ke.domain.entry;

import com.ke.domain.enums.EntryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口配置硬约束校验穷举（FR-N01/N02/N07，Task 27 Requirements 列举的 10 例 + 边界补充）：
 * - serviceType 必须 ∈ 白名单（AGENT_SERVICE/COMPARE 必填；LINK_CARD 不许携带）；
 * - assetScope ⊆ 授权集（越权逐个列出）；LINK_CARD 不许携带资料范围；
 * - targetCardId 仅 LINK_CARD 必填；relationLabel/why/source 由调用方传入 crossTheme 判定——
 *   跨主题三要件齐备（relation ∈ 四词、why 非空、source 非空白非 "{}"）、同主题三者须空。
 * 违规消息中文且具体（含越权资产 id），空列表 = 通过。
 */
class EntryConfigValidatorTest {

    private static final Set<String> SERVICES = Set.of("EXPLAIN", "COMPARE");
    private static final Set<Long> SCOPE = Set.of(1L, 2L, 11L);

    /** AGENT_SERVICE 合法基线配置（不携带 LINK_CARD 专属字段） */
    private static EntryConfig agentService() {
        return new EntryConfig("讲讲书院讲会", EntryType.AGENT_SERVICE, "弄清讲会制度", null,
                "EXPLAIN", Set.of(1L, 11L), "摘要+三段正文", null, null, null, null);
    }

    /** LINK_CARD 合法基线配置（同主题：不携带 relation/why/source） */
    private static EntryConfig linkCard() {
        return new EntryConfig("关于白鹿洞书院", EntryType.LINK_CARD, null, null,
                null, null, null, null, 99L, null, null);
    }

    // ---------- 1. name 超 60 拒 ----------

    @Test
    void nameOver60Rejected() {
        List<String> violations = EntryConfigValidator.validate(
                new EntryConfig("长".repeat(61), EntryType.AGENT_SERVICE, "目标", null,
                        "EXPLAIN", Set.of(1L), "规格", null, null, null, null),
                SERVICES, SCOPE, false);
        assertThat(violations).anyMatch(v -> v.contains("60"));
    }

    @Test
    void blankNameRejected() {
        List<String> violations = EntryConfigValidator.validate(
                new EntryConfig("  ", EntryType.AGENT_SERVICE, "目标", null,
                        "EXPLAIN", Set.of(1L), "规格", null, null, null, null),
                SERVICES, SCOPE, false);
        assertThat(violations).anyMatch(v -> v.contains("名称"));
    }

    // ---------- 2. LINK_CARD 带 serviceType 拒 ----------

    @Test
    void linkCardWithServiceRejected() {
        EntryConfig config = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                "EXPLAIN", null, null, null, 99L, null, null);
        List<String> violations = EntryConfigValidator.validate(config, SERVICES, SCOPE, false);
        assertThat(violations).anyMatch(v -> v.contains("LINK_CARD 入口不应携带服务"));
    }

    // ---------- 3. AGENT_SERVICE 越权 assetScope 逐个列出 ----------

    @Test
    void overreachAssetsListedOneByOne() {
        EntryConfig config = new EntryConfig("讲讲书院", EntryType.AGENT_SERVICE, "目标", null,
                "EXPLAIN", Set.of(1L, 999L, 888L), "规格", null, null, null, null);
        List<String> violations = EntryConfigValidator.validate(config, SERVICES, SCOPE, false);
        // 两个越权 id 各一条，且合法 id 不出现在越权消息里
        assertThat(violations).anyMatch(v -> v.contains("资料范围越权") && v.contains("999"));
        assertThat(violations).anyMatch(v -> v.contains("资料范围越权") && v.contains("888"));
        assertThat(violations).noneMatch(v -> v.contains("资产 1 "));
    }

    @Test
    void serviceTypeMissingOrOutOfWhitelistRejected() {
        // serviceType 缺失
        EntryConfig noService = new EntryConfig("讲讲书院", EntryType.AGENT_SERVICE, "目标", null,
                null, Set.of(1L), "规格", null, null, null, null);
        assertThat(EntryConfigValidator.validate(noService, SERVICES, SCOPE, false))
                .anyMatch(v -> v.contains("服务类型"));

        // serviceType 不在白名单（LLM 幻觉的第九种服务）
        EntryConfig rogueService = new EntryConfig("讲讲书院", EntryType.AGENT_SERVICE, "目标", null,
                "SUMMARIZE", Set.of(1L), "规格", null, null, null, null);
        assertThat(EntryConfigValidator.validate(rogueService, SERVICES, SCOPE, false))
                .anyMatch(v -> v.contains("白名单") && v.contains("SUMMARIZE"));

        // serviceType 非空但授权集为空（allowedServiceTypes 空集）→ 同样拦
        assertThat(EntryConfigValidator.validate(agentService(), Set.of(), SCOPE, false))
                .anyMatch(v -> v.contains("白名单"));
    }

    // ---------- 4. LINK_CARD 缺 target 拒 ----------

    @Test
    void linkCardWithoutTargetRejected() {
        EntryConfig config = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, null, null, null, null);
        assertThat(EntryConfigValidator.validate(config, SERVICES, SCOPE, false))
                .anyMatch(v -> v.contains("目标卡片"));
    }

    // ---------- 5/6/7. 跨主题缺 relation / 缺 why / 缺 source 各自拒绝 ----------

    @Test
    void crossThemeMissingRelationRejected() {
        EntryConfig config = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, null, 99L, "因同讲书院制度", "{\"assetId\":11}");
        List<String> violations = EntryConfigValidator.validate(config, SERVICES, SCOPE, true);
        assertThat(violations).anyMatch(v -> v.contains("关系词"));

        // 非四词的关系词同样拒（沿用 RelationType 白名单）
        EntryConfig rogueRelation = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, "有点关系", 99L, "因同讲书院制度", "{\"assetId\":11}");
        assertThat(EntryConfigValidator.validate(rogueRelation, SERVICES, SCOPE, true))
                .anyMatch(v -> v.contains("关系词"));
    }

    @Test
    void crossThemeMissingWhyRejected() {
        EntryConfig config = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, "相关联", 99L, "  ", "{\"assetId\":11}");
        assertThat(EntryConfigValidator.validate(config, SERVICES, SCOPE, true))
                .anyMatch(v -> v.contains("why"));
    }

    @Test
    void crossThemeMissingOrEmptySourceRejected() {
        // 缺 source
        EntryConfig noSource = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, "相关联", 99L, "因同讲书院制度", null);
        assertThat(EntryConfigValidator.validate(noSource, SERVICES, SCOPE, true))
                .anyMatch(v -> v.contains("出处"));

        // "{}" 空对象 = 形式上有、实际无出处（RelationGuard 键约定：非空对象才有效）
        EntryConfig emptyObjectSource = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, "相关联", 99L, "因同讲书院制度", "{}");
        assertThat(EntryConfigValidator.validate(emptyObjectSource, SERVICES, SCOPE, true))
                .anyMatch(v -> v.contains("出处"));

        // 合法形态二：非空白字符串描述（不必是 JSON）
        EntryConfig textSource = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, "相关联", 99L, "因同讲书院制度", "《岳麓书院志》卷三");
        assertThat(EntryConfigValidator.validate(textSource, SERVICES, SCOPE, true)).isEmpty();
    }

    // ---------- 8. 同主题带 relation/why/source 应拒（三者须空） ----------

    @Test
    void sameThemeWithRelationContextRejected() {
        EntryConfig withRelation = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, "相关联", 99L, null, null);
        assertThat(EntryConfigValidator.validate(withRelation, SERVICES, SCOPE, false))
                .anyMatch(v -> v.contains("关系词"));

        EntryConfig withWhy = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, null, 99L, "同讲书院", null);
        assertThat(EntryConfigValidator.validate(withWhy, SERVICES, SCOPE, false))
                .anyMatch(v -> v.contains("why"));

        EntryConfig withSource = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, null, null, null, 99L, null, "{\"assetId\":11}");
        assertThat(EntryConfigValidator.validate(withSource, SERVICES, SCOPE, false))
                .anyMatch(v -> v.contains("source"));
    }

    // ---------- 边界补充：LINK_CARD 带 assetScope / 服务入口带 target / type 缺失 ----------

    @Test
    void structuralFieldMisplacementRejected() {
        // LINK_CARD 带 assetScope → 拒
        EntryConfig linkWithScope = new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                null, Set.of(1L), null, null, 99L, null, null);
        assertThat(EntryConfigValidator.validate(linkWithScope, SERVICES, SCOPE, false))
                .anyMatch(v -> v.contains("LINK_CARD 入口不应携带资料范围"));

        // AGENT_SERVICE 带 targetCardId → 拒
        EntryConfig serviceWithTarget = new EntryConfig("讲讲书院", EntryType.AGENT_SERVICE, "目标", null,
                "EXPLAIN", Set.of(1L), "规格", null, 99L, null, null);
        assertThat(EntryConfigValidator.validate(serviceWithTarget, SERVICES, SCOPE, false))
                .anyMatch(v -> v.contains("目标卡片"));

        // 服务入口 assetScope 空/缺失 → 拒
        EntryConfig noScope = new EntryConfig("讲讲书院", EntryType.AGENT_SERVICE, "目标", null,
                "COMPARE", null, "规格", null, null, null, null);
        assertThat(EntryConfigValidator.validate(noScope, SERVICES, SCOPE, false))
                .anyMatch(v -> v.contains("资料范围"));

        // type 缺失 → 拒（后续类型分支短路，不再叠加误报）
        EntryConfig noType = new EntryConfig("讲讲书院", null, "目标", null,
                "EXPLAIN", Set.of(1L), "规格", null, null, null, null);
        List<String> violations = EntryConfigValidator.validate(noType, SERVICES, SCOPE, false);
        assertThat(violations).anyMatch(v -> v.contains("类型"));
        assertThat(violations).hasSize(1);
    }

    // ---------- 10. 全合法通过 ----------

    @Test
    void fullyValidConfigsPass() {
        assertThat(EntryConfigValidator.validate(agentService(), SERVICES, SCOPE, false)).isEmpty();
        assertThat(EntryConfigValidator.validate(
                new EntryConfig("对比两书院", EntryType.COMPARE, "对比讲会制度", List.of("岳麓", "白鹿洞"),
                        "COMPARE", SCOPE, "维度×对象表格", null, null, null, null),
                SERVICES, SCOPE, false)).isEmpty();
        assertThat(EntryConfigValidator.validate(linkCard(), SERVICES, SCOPE, false)).isEmpty();
        // 跨主题 + 三要件齐备 → 通过
        assertThat(EntryConfigValidator.validate(
                new EntryConfig("关于书院", EntryType.LINK_CARD, null, null,
                        null, null, null, "相比较", 99L, "同源异流", "{\"assetId\":11,\"quote\":\"…\"}"),
                SERVICES, SCOPE, true)).isEmpty();
    }
}
