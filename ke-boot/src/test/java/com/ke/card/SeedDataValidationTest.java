package com.ke.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ke.domain.card.content.CardContent;
import com.ke.domain.card.content.CardContentValidator;
import com.ke.domain.card.content.CitationIndexValidator;

/**
 * 种子数据结构校验（Task 37,01 §6 最小启动量样例）：seed/cards-yuelu.json 的 10 张示范卡
 * 逐张走与写路径完全相同的校验链——CardContentValidator.parseAndValidate（模板结构/约束）
 * + CitationIndexValidator.check（citations 1-based 索引 vs sources 数量），
 * 即「这些卡 POST /api/wb/cards 一定能通过」的门禁。
 *
 * 取舍说明：种子文件在仓库根 seed/（非 Maven 资源），ke-boot 测试以相对 working dir 定位——
 * Maven 下 user.dir=ke-boot 模块目录，取 parent 即仓库根；为 IDE（working dir=仓库根）容错，
 * 两种候选均尝试。该定位耦合「ke-boot 为仓库一级子模块」的布局，迁移仓库结构需同步调整。
 */
class SeedDataValidationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private static final int EXPECTED_TOTAL = 10;
    private static final Map<String, Long> EXPECTED_TEMPLATE_MIX =
        Map.of("TEXT", 5L, "COMPARE", 2L, "TIMELINE", 2L, "TASK", 1L);

    private static JsonNode cards;

    @BeforeAll
    static void loadSeedFile() throws IOException {
        cards = MAPPER.readTree(Files.readString(locateSeedFile()));
        assertThat(cards.isArray()).as("种子文件须为 JSON 数组").isTrue();
    }

    /** Maven（user.dir=ke-boot）取 parent;IDE（user.dir=仓库根）直取本目录。 */
    private static Path locateSeedFile() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        List<Path> candidates = List.of(
            cwd.resolve("seed/cards-yuelu.json"),
            (cwd.getParent() == null ? cwd : cwd.getParent()).resolve("seed/cards-yuelu.json"));
        return candidates.stream().filter(Files::exists).findFirst()
            .orElseThrow(() -> new IllegalStateException("未找到 seed/cards-yuelu.json(尝试了 " + candidates + ")"));
    }

    @Test
    void seedScaleAndTemplateMixMatchMinimalLaunchDesign() {
        assertThat(cards.size()).as("10 张示范卡").isEqualTo(EXPECTED_TOTAL);
        Map<String, Long> mix = new HashMap<>();
        cards.forEach(c -> mix.merge(c.get("templateType").asText(), 1L, Long::sum));
        assertThat(mix).as("四模板配比 TEXT×5/COMPARE×2/TIMELINE×2/TASK×1").containsAllEntriesOf(EXPECTED_TEMPLATE_MIX);
        assertThat(mix).as("不出现四模板之外的类型").containsOnlyKeys("TEXT", "COMPARE", "TIMELINE", "TASK");
    }

    @Test
    void everyCardPassesSameValidationChainAsWritePath() throws IOException {
        Set<String> titles = new HashSet<>();
        for (int i = 0; i < cards.size(); i++) {
            JsonNode card = cards.get(i);
            String where = "cards[" + i + "](" + card.path("title").asText() + ")";

            assertThat(card.path("theme").asText()).as(where + " theme=专题1 书院地标").isEqualTo("academy");
            String title = card.path("title").asText();
            assertThat(title).as(where + " 标题非空且 ≤120(API @Size 同参)").isNotBlank().hasSizeLessThanOrEqualTo(120);
            assertThat(titles.add(title)).as(where + " 标题唯一(幂等查重键)").isTrue();

            String templateType = card.path("templateType").asText();
            JsonNode content = card.path("content");
            assertThat(content.isObject()).as(where + " content 为 JSON 对象").isTrue();

            // 与 CardService.canonicalize 同一校验链:模板结构 + 引用索引 vs sources 数量
            CardContent parsed = CardContentValidator.parseAndValidate(templateType, MAPPER.writeValueAsString(content));
            JsonNode sources = card.path("sources");
            assertThat(sources.isArray() && !sources.isEmpty()).as(where + " sources 非空").isTrue();
            CitationIndexValidator.check(parsed, sources.size());

            // 来源条目形状:SourceRef 契约必填字段非空,license 有值(种子卡统一已授权)
            for (int s = 0; s < sources.size(); s++) {
                JsonNode source = sources.get(s);
                assertThat(source.path("title").asText()).as(where + " sources[" + s + "].title").isNotBlank();
                assertThat(source.path("locator").asText()).as(where + " sources[" + s + "].locator").isNotBlank();
                assertThat(source.path("license").asText()).as(where + " sources[" + s + "].license").isNotBlank();
            }
        }
    }

    @Test
    void textCardsCarryTwoToThreeSources() {
        cards.forEach(card -> {
            if ("TEXT".equals(card.get("templateType").asText())) {
                assertThat(card.path("sources").size())
                    .as("TEXT 卡《%s》来源 2-3 条", card.get("title").asText())
                    .isBetween(2, 3);
            }
        });
    }
}
