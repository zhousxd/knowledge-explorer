package com.ke.domain.card.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四模板 content_json 写前校验（FR-C03/C04/C05/C06）。
 * 每模板 1 正例 + 结构错误例；错误信息需含违规字段名，供上层定位。
 */
class CardContentValidatorTest {

    @Test
    void textCardValid() {
        String summary = "s".repeat(100);
        String json = """
            {"summary":"%s",
             "sections":[{"h":"标题","body":"正文","citations":[1,2]}],
             "related":[{"cardId":7,"relation":"支持","why":"证据充分","source":3}]}
            """.formatted(summary);
        CardContent content = CardContentValidator.parseAndValidate("TEXT", json);
        assertThat(content).isInstanceOfSatisfying(TextCardContent.class, c -> {
            assertThat(c.summary()).hasSize(100);
            assertThat(c.sections()).hasSize(1);
            assertThat(c.related()).hasSize(1);
        });
    }

    @Test
    void textCardSummaryTooLongRejected() {
        String summary = "s".repeat(121);
        String json = """
            {"summary":"%s","sections":[{"h":"h","body":"b"}]}
            """.formatted(summary);
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("TEXT", json))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("summary");
    }

    @Test
    void compareCellsMismatchRejected() {
        // 2 对象 × 4 维度，但 cells 只有 3 行 → 行数与维度数不符
        String json = """
            {"objects":["甲","乙"],
             "dimensions":["价格","续航","重量","品牌"],
             "cells":[["a","b"],["c","d"],["e","f"]],
             "citations":[1]}
            """;
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("COMPARE", json))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("cells");
    }

    @Test
    void compareCellsRowWidthRejected() {
        // 2 对象 × 2 维度，但某行只有 1 列 → 行内长度与对象数不符
        String json = """
            {"objects":["甲","乙"],
             "dimensions":["价格","续航"],
             "cells":[["a","b"],["c"]]}
            """;
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("COMPARE", json))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("cells");
    }

    @Test
    void timelineEventsRequired() {
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("TIMELINE", """
            {"events":[]}
            """))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("events");
    }

    @Test
    void taskStepsMinutesPositive() {
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("TASK", """
            {"goal":"完成调研",
             "steps":[{"place":"图书馆","observe":"记录样本","minutes":0}],
             "recordSchema":["文本","照片"]}
            """))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("minutes");
    }

    @Test
    void unknownTemplateRejected() {
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("MAP", "{}"))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("MAP");
    }

    @Test
    void citationNegativeRejected() {
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("TEXT", """
            {"summary":"摘要","sections":[{"h":"h","body":"b","citations":[-1]}]}
            """))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("citations");
    }

    @Test
    void nullListElementsRejected() {
        // P1-2：容器元素级 @NotNull（objects/sections/events），null 元素不得入库
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("COMPARE", """
            {"objects":["甲",null],"dimensions":["价格"],"cells":[["低",null]]}
            """))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("objects");
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("COMPARE", """
            {"objects":["甲","乙"],"dimensions":[null],"cells":[["低"]]}
            """))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("dimensions");
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("TEXT", """
            {"summary":"摘要","sections":[null]}
            """))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("sections");
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("TIMELINE", """
            {"events":[null]}
            """))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("events");
    }

    @Test
    void unknownPropertiesIgnored() {
        String json = """
            {"summary":"摘要","sections":[{"h":"h","body":"b"}],"futureField":{"x":1}}
            """;
        assertThat(CardContentValidator.parseAndValidate("TEXT", json))
            .isInstanceOf(TextCardContent.class);
    }

    @Test
    void caseInsensitiveTemplateType() {
        assertThat(CardContentValidator.parseAndValidate("text", """
            {"summary":"摘要","sections":[{"h":"h","body":"b"}]}
            """)).isInstanceOf(TextCardContent.class);
        assertThat(CardContentValidator.parseAndValidate("Timeline", """
            {"events":[{"year":"2024","title":"事件"}]}
            """)).isInstanceOf(TimelineCardContent.class);
    }

    @Test
    void timelineAndCompareAndTaskHappyPath() {
        assertThat(CardContentValidator.parseAndValidate("COMPARE", """
            {"objects":["甲","乙"],"dimensions":["价格"],
             "cells":[["低","高"]],"citations":[0,1]}
            """)).isInstanceOf(CompareCardContent.class);
        assertThat(CardContentValidator.parseAndValidate("TIMELINE", """
            {"events":[{"year":"2024","title":"发布","body":"v1","cardId":2,"citations":[1]}]}
            """)).isInstanceOf(TimelineCardContent.class);
        assertThat(CardContentValidator.parseAndValidate("TASK", """
            {"goal":"目标","steps":[{"place":"机房","observe":"观察","minutes":30}],
             "recordSchema":["文本"]}
            """)).isInstanceOf(TaskCardContent.class);
    }

    @Test
    void malformedJsonRejected() {
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate("TEXT", "{not-json"))
            .isInstanceOf(InvalidCardContentException.class);
        assertThatThrownBy(() -> CardContentValidator.parseAndValidate(null, "{}"))
            .isInstanceOf(InvalidCardContentException.class);
    }
}
