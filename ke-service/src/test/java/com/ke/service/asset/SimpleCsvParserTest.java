package com.ke.service.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 手写极简 CSV 解析：双引号包裹字段内逗号不切分、"" 转义、空行跳过且行号保留。 */
class SimpleCsvParserTest {

    private static List<SimpleCsvParser.Row> parse(String text) {
        return SimpleCsvParser.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void quotedFieldKeepsCommaAndEscapedQuotes() {
        // CSV: book,"a,""b"","{""chapter"":""一"",""pages"":""1-2""}"
        List<SimpleCsvParser.Row> rows = parse(
                "kind,title,locator\nbook,\"a,\"\"b\"\"\",\"{\"\"chapter\"\":\"\"一\"\",\"\"pages\"\":\"\"1-2\"\"}\"\n");
        assertThat(rows).hasSize(1 + 1); // 表头 + 数据行
        assertThat(rows.get(1).line()).isEqualTo(2);
        assertThat(rows.get(1).fields()).containsExactly(
                "book", "a,\"b\"", "{\"chapter\":\"一\",\"pages\":\"1-2\"}");
    }

    @Test
    void blankLinesSkippedButLineNumbersPreserved() {
        List<SimpleCsvParser.Row> rows = parse("h1,h2\n\ntitle,tail\n  \nplain,after\n");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).line()).isEqualTo(3);
        assertThat(rows.get(2).line()).isEqualTo(5);
    }

    @Test
    void crlfAndUtf8BomHandled() {
        List<SimpleCsvParser.Row> rows = parse("\uFEFFkind,title\r\nbook,《岳麓书院史略》\r\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).fields()).containsExactly("book", "《岳麓书院史略》");
    }

    @Test
    void unterminatedQuoteRejected() {
        assertThatThrownBy(() -> parse("kind,title\n\"未闭合,还继续"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
    }
}
