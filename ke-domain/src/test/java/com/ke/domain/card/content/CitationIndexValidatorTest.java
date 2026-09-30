package com.ke.domain.card.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引用索引校验（sources 契约）：citations[n] 为指向 sources 数组的 1-based 索引，
 * 须落在 [1, sourceCount]；sources 为空（sourceCount=0）时任何非空 citations 均越界。
 */
class CitationIndexValidatorTest {

    private static final CardContent TEXT = parse("TEXT", """
        {"summary":"摘要",
         "sections":[{"h":"甲","body":"正文一","citations":[1,2]},{"h":"乙","body":"正文二"}]}
        """);

    private static final CardContent COMPARE = parse("COMPARE", """
        {"objects":["甲","乙"],"dimensions":["价格"],"cells":[["低","高"]],"citations":[1]}
        """);

    private static final CardContent TIMELINE = parse("TIMELINE", """
        {"events":[{"year":"976","title":"创办","citations":[2]}]}
        """);

    private static CardContent parse(String type, String json) {
        return CardContentValidator.parseAndValidate(type, json);
    }

    @Test
    void indexWithinRangeAccepted() {
        assertThatCode(() -> CitationIndexValidator.check(TEXT, 2)).doesNotThrowAnyException();
        assertThatCode(() -> CitationIndexValidator.check(COMPARE, 1)).doesNotThrowAnyException();
        assertThatCode(() -> CitationIndexValidator.check(TIMELINE, 2)).doesNotThrowAnyException();
    }

    @Test
    void zeroBasedIndexRejected() {
        // 1-based：0 是越界
        CardContent content = parse("TEXT", """
            {"summary":"摘要","sections":[{"h":"h","body":"b","citations":[0]}]}
            """);
        assertThatThrownBy(() -> CitationIndexValidator.check(content, 2))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("引用 [0] 超出来源范围");
    }

    @Test
    void indexBeyondSourceCountRejected() {
        assertThatThrownBy(() -> CitationIndexValidator.check(TEXT, 1))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("引用 [2] 超出来源范围")
            .hasMessageContaining("sections[0].citations");
    }

    @Test
    void emptySourcesRejectsAnyCitation() {
        assertThatThrownBy(() -> CitationIndexValidator.check(TEXT, 0))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("超出来源范围");
        assertThatThrownBy(() -> CitationIndexValidator.check(COMPARE, 0))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("超出来源范围");
        assertThatThrownBy(() -> CitationIndexValidator.check(TIMELINE, 0))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("超出来源范围");
    }

    @Test
    void noCitationsAcceptedWithoutSources() {
        CardContent content = parse("TEXT", """
            {"summary":"摘要","sections":[{"h":"h","body":"b"}]}
            """);
        assertThatCode(() -> CitationIndexValidator.check(content, 0)).doesNotThrowAnyException();
    }

    @Test
    void timelineCitationPathReported() {
        assertThatThrownBy(() -> CitationIndexValidator.check(TIMELINE, 1))
            .isInstanceOf(InvalidCardContentException.class)
            .hasMessageContaining("events[0].citations");
    }
}
