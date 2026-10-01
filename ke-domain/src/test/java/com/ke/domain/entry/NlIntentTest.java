package com.ke.domain.entry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-N01 / 02 §5.3：四类白名单意图枚举 + 模型输出解析纯逻辑（trim+upper；白名单外一律不识别） */
class NlIntentTest {

    @Test
    void fourWhitelistIntentsExactly() {
        assertThat(NlIntent.values()).containsExactly(
                NlIntent.LINK_CARD, NlIntent.EXPLAIN, NlIntent.COMPARE, NlIntent.OUT_OF_SCOPE);
    }

    @Test
    void parseTrimsAndIgnoresCase() {
        assertThat(NlIntent.parse("LINK_CARD")).contains(NlIntent.LINK_CARD);
        assertThat(NlIntent.parse(" explain ")).contains(NlIntent.EXPLAIN);
        assertThat(NlIntent.parse("\nCompare\t")).contains(NlIntent.COMPARE);
        assertThat(NlIntent.parse("out_of_scope")).contains(NlIntent.OUT_OF_SCOPE);
    }

    @Test
    void parseBlankOrNullIsEmpty() {
        assertThat(NlIntent.parse(null)).isEmpty();
        assertThat(NlIntent.parse("")).isEmpty();
        assertThat(NlIntent.parse("   \n\t")).isEmpty();
    }

    @Test
    void parseNonWhitelistIsEmpty() {
        // 白名单外：解析失败由调用方兜底 OUT_OF_SCOPE（安全侧错报优于漏报）
        assertThat(NlIntent.parse("FOO")).isEmpty();
        assertThat(NlIntent.parse("SUMMARIZE")).isEmpty();
        assertThat(NlIntent.parse("link card")).isEmpty();
        assertThat(NlIntent.parse("帮我订机票")).isEmpty();
    }
}
