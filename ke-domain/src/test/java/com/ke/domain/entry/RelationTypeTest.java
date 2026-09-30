package com.ke.domain.entry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-C09：入口副标题的四类关系词是固定白名单，中文 label 即 wire format */
class RelationTypeTest {

    @Test
    void fourRelationWordsExactly() {
        assertThat(RelationType.labels()).containsExactly("深入了解", "相关联", "相比较", "去实践");
        assertThat(RelationType.DEEPEN.label()).isEqualTo("深入了解");
        assertThat(RelationType.RELATED.label()).isEqualTo("相关联");
        assertThat(RelationType.COMPARE.label()).isEqualTo("相比较");
        assertThat(RelationType.PRACTICE.label()).isEqualTo("去实践");
    }

    @Test
    void whitelistMembershipCheck() {
        assertThat(RelationType.isRelationLabel("深入了解")).isTrue();
        assertThat(RelationType.isRelationLabel("去实践")).isTrue();
        assertThat(RelationType.isRelationLabel("相似")).isFalse();
        assertThat(RelationType.isRelationLabel("深入了解 ")).isFalse();
        assertThat(RelationType.isRelationLabel(null)).isFalse();
    }
}
