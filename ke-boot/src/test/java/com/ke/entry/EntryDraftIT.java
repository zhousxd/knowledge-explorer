package com.ke.entry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.ke.agent.StubLlmGateway;
import com.ke.service.llm.ModelTier;
import com.ke.support.ItDb;
import com.ke.support.RedisFlush;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口草稿端到端（FR-N01/N02/N06/N07，{@link com.ke.service.entry.EntryDraftService} + DraftController）：
 * POST /api/entries/nl-draft {cardId, text}，认证即可（EXPLORER 可为自己起草）。
 * 网关用 StubLlmGateway（profile explain-test，@Primary 压过 test 的 MockLlmGateway），按序应答：
 * 第一次 classify（ROUTER 档）、第二次生成档抽取（GENERATOR 档）。直连 WSL ke_test（@ItDb）；
 * 类前 RedisFlush flushdb（注册/登录/频控共享 db15 键空间，跨类残留一并清零）。
 * 钉住：EXPLAIN 合法抽取全通过；OUT_OF_SCOPE 替代建议；越权 assetScope 自动收窄（FR-N06：
 * 行为与文档一致——过滤越权 id 后返回收窄配置）；LINK_CARD 站内检索命中目标卡（跳过自身）；
 * text 超 200 字 400。
 * 造数据注意：@ItDb 按类清库，类内各用例的卡/资料标题唯一（select by title 断言唯一行）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "explain-test"})
@ItDb
@ExtendWith(RedisFlush.class)
class EntryDraftIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetStub() {
        StubLlmGateway.reset("OUT_OF_SCOPE");
    }

    // ---------- helpers ----------

    private HttpEntity<String> json(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    /** 注册（EXPLORER 默认角色即可，草稿端点仅要求认证）+ 登录拿 accessToken */
    private String newUserToken(String phone, String nickname) {
        ResponseEntity<String> reg = http.postForEntity("/api/auth/register",
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\",\"nickname\":\"" + nickname + "\"}", null),
                String.class);
        assertThat(reg.getStatusCode().value()).as("register body=%s", reg.getBody()).isEqualTo(201);
        ResponseEntity<String> login = http.postForEntity("/api/auth/login",
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\"}", null), String.class);
        assertThat(login.getStatusCode().value()).as("login body=%s", login.getBody()).isEqualTo(200);
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    /** 直插知识单元（永久授权），返回 id（title 由调用方保证类内唯一） */
    private long insertAsset(String title, String extract) {
        jdbc.update("insert into knowledge_asset (kind,title,license,content_extract) values (?,?,?,?)",
                "book", title, null, extract);
        return jdbc.queryForObject("select id from knowledge_asset where title=?", Long.class, title);
    }

    /** 直插 PUBLISHED 卡 + 版本（sources 直给 JSON，可空）+ current_version_id 回填，返回 cardId */
    private long publishedCard(String title, String theme, String sourcesJson) {
        jdbc.update("insert into card(theme,template_type,title,status,summary_text,sort) values(?,?,?,?,?,?)",
                theme, "TEXT", title, "PUBLISHED", title + "摘要", 0);
        long cardId = jdbc.queryForObject("select id from card where title=?", Long.class, title);
        jdbc.update("insert into card_version(card_id,version_no,content_json,sources) values(?,1,?,?)",
                cardId, "{\"summary\":\"" + title + "摘要\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文。\"}],\"related\":[]}",
                sourcesJson);
        jdbc.update("update card set current_version_id=(select id from card_version where card_id=? and version_no=1) "
                + "where id=?", cardId, cardId);
        return cardId;
    }

    /** 生成档抽取应答：draft.prompt.system 约定的 JSON 形状（assetScope 可含任意 id，含越权） */
    private static String draftJson(String name, String serviceType, String goal, Long... assetIds) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < assetIds.length; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append(assetIds[i]);
        }
        return "{\"name\":\"" + name + "\",\"goal\":\"" + goal + "\",\"serviceType\":\"" + serviceType
                + "\",\"assetScope\":[" + ids + "],\"outputSpec\":\"摘要+分节正文\"}";
    }

    private ResponseEntity<String> draft(String token, long cardId, String text) {
        return http.exchange("/api/entries/nl-draft", HttpMethod.POST,
                json("{\"cardId\":" + cardId + ",\"text\":\"" + text + "\"}", token), String.class);
    }

    /** 响应体 data 节点 → JsonNode：non_null 序列化下 null 字段整个缺席，JsonPath 会抛
     *  PathNotFound，故 config/advice 的有无用 has() 断言 */
    private static JsonNode data(String body) {
        try {
            return new ObjectMapper().readTree(body).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON: " + body, e);
        }
    }

    // ---------- 用例 ----------

    @Test
    void explainDraftHappyPath() {
        long assetId = insertAsset("《草稿讲解岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("草稿讲解岳麓卡", "academy",
                "[{\"assetId\":" + assetId + ",\"title\":\"《草稿讲解岳麓志》\",\"locator\":\"第1页\"}]");
        String token = newUserToken("13800007001", "起草者甲");
        StubLlmGateway.reset("EXPLAIN", draftJson("讲讲岳麓书院", "EXPLAIN", "讲清讲会制度", assetId));

        ResponseEntity<String> res = draft(token, card, "深入讲讲岳麓书院的讲会制度");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.intent")).isEqualTo("EXPLAIN");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.config.name")).isEqualTo("讲讲岳麓书院");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.config.type")).isEqualTo("AGENT_SERVICE");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.config.serviceType")).isEqualTo("EXPLAIN");
        assertThat(((Number) JsonPath.read(res.getBody(), "$.data.config.assetScope[0]")).longValue())
                .isEqualTo(assetId);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.violations.length()")).isEqualTo(0);

        // 生成面：分类走 ROUTER，抽取走 GENERATOR；提示词带授权资料清单（id:标题）
        assertThat(StubLlmGateway.CALLS.get()).isEqualTo(2);
        assertThat(StubLlmGateway.lastCommand().tier()).isEqualTo(ModelTier.GENERATOR);
        assertThat(StubLlmGateway.lastCommand().system()).contains("EXPLAIN").contains("assetScope");
        assertThat(StubLlmGateway.lastCommand().user())
                .contains("深入讲讲岳麓书院的讲会制度")
                .contains("- " + assetId + ":《草稿讲解岳麓志》");
    }

    @Test
    void outOfScopeAdvice() {
        long card = publishedCard("草稿越界订票卡", "academy", null);
        String token = newUserToken("13800007002", "起草者乙");
        StubLlmGateway.reset("OUT_OF_SCOPE");

        ResponseEntity<String> res = draft(token, card, "帮我订机票");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.intent")).isEqualTo("OUT_OF_SCOPE");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.advice")).contains("暂不支持");
        assertThat(data(res.getBody()).has("config")).isFalse();
        // 只有分类一次调用，不做抽取
        assertThat(StubLlmGateway.CALLS.get()).isEqualTo(1);
    }

    @Test
    void overreachAssetsFilteredOrReported() {
        long assetId = insertAsset("《草稿越权岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("草稿越权岳麓卡", "academy",
                "[{\"assetId\":" + assetId + ",\"title\":\"《草稿越权岳麓志》\",\"locator\":\"第1页\"}]");
        String token = newUserToken("13800007003", "起草者丙");
        // LLM 越清单幻觉 id 99999：自动收窄（FR-N06），返回过滤后的配置而非拒绝
        StubLlmGateway.reset("EXPLAIN",
                draftJson("讲讲岳麓书院", "EXPLAIN", "讲清讲会制度", assetId, 99999L));

        ResponseEntity<String> res = draft(token, card, "深入讲讲岳麓书院");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.violations.length()")).isEqualTo(0);
        assertThat(data(res.getBody()).has("config")).isTrue();
        assertThat((int) JsonPath.read(res.getBody(), "$.data.config.assetScope.length()")).isEqualTo(1);
        assertThat(((Number) JsonPath.read(res.getBody(), "$.data.config.assetScope[0]")).longValue())
                .isEqualTo(assetId);
        // 行为与文档一致：越权 id 收窄剔除，不出现在任何输出里
        assertThat(res.getBody()).doesNotContain("99999");
    }

    @Test
    void linkCardDraft() {
        // 两张已发布卡标题都含检索词（q=text 前 20 字 ILIKE '%q%' 命中两张）→ top 1 为所属卡自身，
        // 跳过后取目标卡；两卡同主题 → 三要件须空，校验通过出 config
        long source = publishedCard("草稿白鹿洞书院学规（上）", "academy", null);
        long target = publishedCard("草稿白鹿洞书院学规（下）", "academy", null);
        String token = newUserToken("13800007004", "起草者丁");
        StubLlmGateway.reset("LINK_CARD");

        ResponseEntity<String> res = draft(token, source, "草稿白鹿洞书院学规");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.intent")).isEqualTo("LINK_CARD");
        assertThat(data(res.getBody()).has("config")).isTrue();
        assertThat(((Number) JsonPath.read(res.getBody(), "$.data.config.targetCardId")).longValue())
                .isEqualTo(target);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.config.type")).isEqualTo("LINK_CARD");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.config.name"))
                .startsWith("关于").hasSizeLessThanOrEqualTo(30);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.violations.length()")).isEqualTo(0);
    }

    @Test
    void textTooLongRejected() {
        long card = publishedCard("草稿超长卡", "academy", null);
        String token = newUserToken("13800007005", "起草者戊");
        StubLlmGateway.reset("EXPLAIN");

        ResponseEntity<String> res = draft(token, card, "讲".repeat(201));
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((int) JsonPath.read(res.getBody(), "$.code")).isEqualTo(400);
        // 参数校验在分类之前：不打网关
        assertThat(StubLlmGateway.CALLS.get()).isZero();
    }
}
