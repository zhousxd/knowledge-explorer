package com.ke.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ke.domain.card.content.CardContent;
import com.ke.domain.card.content.CardContentValidator;
import com.ke.domain.card.content.CitationIndexValidator;

/**
 * 种子数据结构校验（Task 37,01 §6 最小启动量样例;多数据集参数化）：每个注册数据集
 * 逐张走与写路径完全相同的校验链——CardContentValidator.parseAndValidate（模板结构/约束）
 * + CitationIndexValidator.check（citations 1-based 索引 vs sources 数量），
 * 即「这些卡 POST /api/wb/cards 一定能通过」的门禁。
 *
 * 入口种子（entries-xiangcai.json）在此做纯 JSON 层结构校验：引用按卡题可解析、
 * LINK_CARD/服务入口字段形状与 EntryConfigValidator 约束一致、(cardId,name) 幂等键唯一、
 * mainPath 逐跳成链且全图从首卡可达——对应入口种子包自述的结构检查项；运行时的
 * assetScope/targetCardId 解析与 PUBLIC 过审由 seed/load.sh 收敛（重跑幂等）。
 *
 * 取舍说明：种子文件在仓库根 seed/（非 Maven 资源），ke-boot 测试以相对 working dir 定位——
 * Maven 下 user.dir=ke-boot 模块目录，取 parent 即仓库根；为 IDE（working dir=仓库根）容错，
 * 两种候选均尝试。该定位耦合「ke-boot 为仓库一级子模块」的布局，迁移仓库结构需同步调整。
 */
class SeedDataValidationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /** 数据集画像：卡片文件/入口文件(空=无入口种子)/专题/规模与四模板配比/TEXT 来源条数区间/主线层数 */
    record SeedDataset(String cardsFile, String entriesFile, String theme, int total,
                       Map<String, Long> templateMix, int textSourcesMin, int textSourcesMax,
                       int mainPathSize) {
    }

    static List<SeedDataset> datasets() {
        return List.of(
            new SeedDataset("cards-yuelu.json", null, "academy", 10,
                Map.of("TEXT", 5L, "COMPARE", 2L, "TIMELINE", 2L, "TASK", 1L), 2, 3, 0),
            new SeedDataset("cards-xiangcai.json", "entries-xiangcai.json", "cuisine", 11,
                Map.of("TEXT", 9L, "COMPARE", 1L, "TASK", 1L), 1, 3, 8));
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void seedScaleAndTemplateMixMatchMinimalLaunchDesign(SeedDataset ds) throws IOException {
        JsonNode cards = readJson(ds.cardsFile());
        assertThat(cards.isArray()).as("%s 须为 JSON 数组", ds.cardsFile()).isTrue();
        assertThat(cards.size()).as("%s 示范卡规模", ds.cardsFile()).isEqualTo(ds.total());
        Map<String, Long> mix = new HashMap<>();
        cards.forEach(c -> mix.merge(c.get("templateType").asText(), 1L, Long::sum));
        assertThat(mix).as("%s 模板配比", ds.cardsFile()).containsAllEntriesOf(ds.templateMix());
        assertThat(mix.keySet()).as("%s 不出现四模板之外的类型", ds.cardsFile())
            .isSubsetOf("TEXT", "COMPARE", "TIMELINE", "TASK");
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void everyCardPassesSameValidationChainAsWritePath(SeedDataset ds) throws IOException {
        JsonNode cards = readJson(ds.cardsFile());
        Set<String> titles = new HashSet<>();
        for (int i = 0; i < cards.size(); i++) {
            JsonNode card = cards.get(i);
            String where = ds.cardsFile() + "[" + i + "](" + card.path("title").asText() + ")";

            assertThat(card.path("theme").asText()).as(where + " theme=专题键").isEqualTo(ds.theme());
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

            // 来源条目形状:SourceRef 契约必填字段非空,license 有值
            for (int s = 0; s < sources.size(); s++) {
                JsonNode source = sources.get(s);
                assertThat(source.path("title").asText()).as(where + " sources[" + s + "].title").isNotBlank();
                assertThat(source.path("locator").asText()).as(where + " sources[" + s + "].locator").isNotBlank();
                assertThat(source.path("license").asText()).as(where + " sources[" + s + "].license").isNotBlank();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void textCardsSourcesWithinDatasetRange(SeedDataset ds) throws IOException {
        readJson(ds.cardsFile()).forEach(card -> {
            if ("TEXT".equals(card.get("templateType").asText())) {
                assertThat(card.path("sources").size())
                    .as("%s《%s》TEXT 卡来源 %d-%d 条", ds.cardsFile(), card.get("title").asText(),
                        ds.textSourcesMin(), ds.textSourcesMax())
                    .isBetween(ds.textSourcesMin(), ds.textSourcesMax());
            }
        });
    }

    /**
     * 入口种子结构门禁（仅带 entriesFile 的数据集）：引用可解析、字段形状与
     * EntryConfigValidator 同口径、(宿主,名称) 幂等键唯一、主线逐跳成链、全图可达。
     */
    @ParameterizedTest
    @MethodSource("datasets")
    void entriesSpecWellFormedAndCoversMainPath(SeedDataset ds) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(ds.entriesFile() != null, "该数据集无入口种子");
        JsonNode spec = readJson(ds.entriesFile());
        JsonNode cards = readJson(ds.cardsFile());
        Set<String> titles = new HashSet<>();
        Set<String> hostsHavingSources = new HashSet<>();
        for (JsonNode card : cards) {
            titles.add(card.path("title").asText());
            if (card.path("sources").isArray() && !card.path("sources").isEmpty()) {
                hostsHavingSources.add(card.path("title").asText());
            }
        }

        JsonNode entries = spec.path("entries");
        assertThat(entries.isArray() && !entries.isEmpty()).as("entries 非空数组").isTrue();
        Map<String, Set<String>> adjacency = new HashMap<>();
        Set<String> dedupeKeys = new HashSet<>();
        for (JsonNode entry : entries) {
            String where = ds.entriesFile() + "「" + entry.path("name").asText() + "」";
            assertThat(titles).as(where + " 宿主卡存在").contains(entry.path("card").asText());
            String name = entry.path("name").asText();
            assertThat(name).as(where + " 名称非空且 ≤60(EntryConfigValidator 同参)")
                .isNotBlank().hasSizeLessThanOrEqualTo(60);
            assertThat(dedupeKeys.add(entry.path("card").asText() + "|" + name))
                .as(where + " (宿主,名称) 唯一(load.sh 幂等查重键)").isTrue();

            String type = entry.path("type").asText();
            assertThat(List.of("LINK_CARD", "AGENT_SERVICE", "COMPARE"))
                .as(where + " 类型 ∈ 入口类型枚举").contains(type);
            if ("LINK_CARD".equals(type)) {
                assertThat(titles).as(where + " 目标卡存在").contains(entry.path("target").asText());
                assertThat(entry.has("serviceType")).as(where + " 链接入口不应携带服务").isFalse();
                adjacency.computeIfAbsent(entry.path("card").asText(), k -> new HashSet<>())
                    .add(entry.path("target").asText());
            } else {
                assertThat(List.of("EXPLAIN", "COMPARE"))
                    .as(where + " 服务类型白名单").contains(entry.path("serviceType").asText());
                assertThat(entry.has("target")).as(where + " 服务入口不应指定目标卡片").isFalse();
                assertThat(entry.path("goal").asText()).as(where + " 服务入口须带运行问题(goal)").isNotBlank();
                assertThat(hostsHavingSources).as(where + " 宿主卡须挂接来源(assetScope 前提)")
                    .contains(entry.path("card").asText());
            }
        }

        // mainPath：层数、卡题有效、相邻两卡之间必有 LINK_CARD 入口（推荐阅读顺序完整）
        JsonNode mainPath = spec.path("mainPath");
        assertThat(mainPath.size()).as("主线层数").isEqualTo(ds.mainPathSize());
        for (int i = 0; i < mainPath.size(); i++) {
            String where = ds.entriesFile() + " mainPath[" + i + "]";
            assertThat(titles).as(where + " 卡题存在").contains(mainPath.get(i).asText());
            if (i > 0) {
                assertThat(adjacency.getOrDefault(mainPath.get(i - 1).asText(), Set.of()))
                    .as(where + " 与上一层之间有链接入口").contains(mainPath.get(i).asText());
            }
        }

        // 全图可达：从主线首卡出发沿 LINK_CARD 可到达数据集全部卡片
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(mainPath.get(0).asText());
        seen.add(mainPath.get(0).asText());
        while (!queue.isEmpty()) {
            for (String next : adjacency.getOrDefault(queue.poll(), Set.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        assertThat(seen).as("全部卡片可从主线首卡到达").containsAll(titles);
    }

    private static JsonNode readJson(String fileName) throws IOException {
        return MAPPER.readTree(Files.readString(locateSeedFile(fileName)));
    }

    /** Maven（user.dir=ke-boot）取 parent;IDE（user.dir=仓库根）直取本目录。 */
    private static Path locateSeedFile(String fileName) {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        List<Path> candidates = List.of(
            cwd.resolve("seed/" + fileName),
            (cwd.getParent() == null ? cwd : cwd.getParent()).resolve("seed/" + fileName));
        return candidates.stream().filter(Files::exists).findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "未找到 seed/" + fileName + "(尝试了 " + candidates + ")"));
    }
}
