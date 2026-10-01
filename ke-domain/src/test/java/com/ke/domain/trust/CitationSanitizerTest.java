package com.ke.domain.trust;

import com.ke.domain.enums.ClaimType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 引用校验降级器穷举（FR-S05 / R2，Task 19）：越界引用剔除、FACT 空引用降级 SYNTHESIS、
 * SYNTHESIS/GEN 不降级、null 容忍、去重、入参防御。
 */
class CitationSanitizerTest {

    private static final Set<Long> ALLOWED = Set.of(1L, 2L, 3L);

    private static SanitizedSection section(ClaimType type, Long... citations) {
        return new SanitizedSection("正文", type, new ArrayList<>(Arrays.asList(citations)));
    }

    // ---------- 1. 全合法 FACT 保持 ----------

    @Test
    void allValidFactKept() {
        SanitizeReport report = CitationSanitizer.sanitize(
                List.of(section(ClaimType.FACT, 1L, 2L), section(ClaimType.FACT, 3L)), ALLOWED);

        assertThat(report.sections()).hasSize(2);
        assertThat(report.sections().get(0).claimType()).isEqualTo(ClaimType.FACT);
        assertThat(report.sections().get(0).citations()).containsExactly(1L, 2L);
        assertThat(report.sections().get(1).claimType()).isEqualTo(ClaimType.FACT);
        assertThat(report.sections().get(1).citations()).containsExactly(3L);
        assertThat(report.strippedCitations()).isEmpty();
        assertThat(report.downgradedSectionIndexes()).isEmpty();
    }

    // ---------- 2. 1 个越界 id 被剥离，其余保留 ----------

    @Test
    void outOfBoundStripped() {
        SanitizeReport report = CitationSanitizer.sanitize(
                List.of(section(ClaimType.FACT, 1L, 99L)), ALLOWED);

        assertThat(report.sections().get(0).claimType()).isEqualTo(ClaimType.FACT);
        assertThat(report.sections().get(0).citations()).containsExactly(1L);
        assertThat(report.strippedCitations()).containsExactly(99L);
        assertThat(report.downgradedSectionIndexes()).isEmpty();
    }

    // ---------- 3. FACT 全部越界 → 降级 SYNTHESIS + downgraded 记录 ----------

    @Test
    void factDowngradedWhenAllStripped() {
        SanitizeReport report = CitationSanitizer.sanitize(
                List.of(section(ClaimType.FACT, 99L)), ALLOWED);

        assertThat(report.sections().get(0).claimType()).isEqualTo(ClaimType.SYNTHESIS);
        assertThat(report.sections().get(0).citations()).isEmpty();
        assertThat(report.strippedCitations()).containsExactly(99L);
        assertThat(report.downgradedSectionIndexes()).containsExactly(0);
    }

    // ---------- 4. SYNTHESIS 带越界 → 剥离不降级 ----------

    @Test
    void synthStrippedNotDowngraded() {
        SanitizeReport report = CitationSanitizer.sanitize(
                List.of(section(ClaimType.SYNTHESIS, 99L)), ALLOWED);

        assertThat(report.sections().get(0).claimType()).isEqualTo(ClaimType.SYNTHESIS);
        assertThat(report.sections().get(0).citations()).isEmpty();
        assertThat(report.strippedCitations()).containsExactly(99L);
        assertThat(report.downgradedSectionIndexes()).isEmpty();
    }

    // ---------- 5. allowed 空集 → 全部 FACT 降级 ----------

    @Test
    void emptyAllowedAllFactDowngraded() {
        SanitizeReport report = CitationSanitizer.sanitize(
                List.of(section(ClaimType.FACT, 1L), section(ClaimType.FACT, 2L, 3L)),
                Set.of());

        assertThat(report.sections()).allSatisfy(s -> assertThat(s.claimType()).isEqualTo(ClaimType.SYNTHESIS));
        assertThat(report.sections()).allSatisfy(s -> assertThat(s.citations()).isEmpty());
        assertThat(report.strippedCitations()).containsExactly(1L, 2L, 3L);
        assertThat(report.downgradedSectionIndexes()).containsExactly(0, 1);
    }

    // ---------- 6. citations=null 视为空列表（不 NPE） ----------

    @Test
    void nullCitationsTolerated() {
        SanitizeReport report = CitationSanitizer.sanitize(
                List.of(new SanitizedSection("正文", ClaimType.GEN, null),
                        new SanitizedSection("正文", ClaimType.FACT, null)),
                ALLOWED);

        assertThatNoException().isThrownBy(() -> CitationSanitizer.sanitize(
                List.of(new SanitizedSection("正文", ClaimType.FACT, null)), ALLOWED));
        // GEN 空引用保持原档
        assertThat(report.sections().get(0).claimType()).isEqualTo(ClaimType.GEN);
        assertThat(report.sections().get(0).citations()).isEmpty();
        // FACT 没有可查证出处 → 降级（null 视为空）
        assertThat(report.sections().get(1).claimType()).isEqualTo(ClaimType.SYNTHESIS);
        assertThat(report.downgradedSectionIndexes()).containsExactly(1);
        assertThat(report.strippedCitations()).isEmpty();
    }

    // ---------- 7. sections=null/空 → 报告空 sections ----------

    @Test
    void nullSectionsEmptyReport() {
        SanitizeReport nullSections = CitationSanitizer.sanitize(null, ALLOWED);
        assertThat(nullSections.sections()).isEmpty();
        assertThat(nullSections.strippedCitations()).isEmpty();
        assertThat(nullSections.downgradedSectionIndexes()).isEmpty();

        SanitizeReport emptySections = CitationSanitizer.sanitize(List.of(), ALLOWED);
        assertThat(emptySections.sections()).isEmpty();
        assertThat(emptySections.strippedCitations()).isEmpty();
        assertThat(emptySections.downgradedSectionIndexes()).isEmpty();
    }

    // ---------- 8. 两个 section 各引同一越界 id → stripped 只记一次 ----------

    @Test
    void strippedDeduplicated() {
        SanitizeReport report = CitationSanitizer.sanitize(
                List.of(section(ClaimType.FACT, 99L), section(ClaimType.FACT, 99L, 1L)), ALLOWED);

        assertThat(report.strippedCitations()).containsExactly(99L);
        assertThat(report.downgradedSectionIndexes()).containsExactly(0);
        assertThat(report.sections().get(1).citations()).containsExactly(1L);
    }

    // ---------- 9. 入参 list/section 未被修改 ----------

    @Test
    void inputNotMutated() {
        List<Long> citations = new ArrayList<>(Arrays.asList(1L, 99L));
        SanitizedSection original = new SanitizedSection("正文", ClaimType.FACT, citations);
        List<SanitizedSection> sections = new ArrayList<>(List.of(original));

        SanitizeReport report = CitationSanitizer.sanitize(sections, ALLOWED);

        // 原 list/section 原样：长度、档位、引用列表均未动
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0)).isSameAs(original);
        assertThat(original.claimType()).isEqualTo(ClaimType.FACT);
        assertThat(original.citations()).containsExactly(1L, 99L);
        assertThat(citations).containsExactly(1L, 99L);
        // 输出是新对象：改输出不影响入参，反之亦然
        assertThat(report.sections().get(0)).isNotSameAs(original);
        report.sections().get(0).citations().clear();
        assertThat(citations).containsExactly(1L, 99L);
    }

    // ---------- 10. GEN 段越界剥离后仍 GEN ----------

    @Test
    void genKeptWithStripped() {
        SanitizeReport report = CitationSanitizer.sanitize(
                List.of(section(ClaimType.GEN, 99L)), ALLOWED);

        assertThat(report.sections().get(0).claimType()).isEqualTo(ClaimType.GEN);
        assertThat(report.sections().get(0).citations()).isEmpty();
        assertThat(report.strippedCitations()).containsExactly(99L);
        assertThat(report.downgradedSectionIndexes()).isEmpty();
    }
}
