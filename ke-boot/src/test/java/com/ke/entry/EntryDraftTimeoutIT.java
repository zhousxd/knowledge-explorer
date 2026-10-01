package com.ke.entry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.ke.agent.StubLlmGateway;
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
 * 入口草稿超时护栏端到端（A3 30s 交互保障，{@code ke.agent.draft-classify/extract-timeout-seconds}）：
 * nl-draft 的两次 LLM 调用无超时护栏时连接挂起=线程无限占用，超时阈值压短（分类 2s/抽取 1s）+
 * Stub 延迟（{@link StubLlmGateway#setDelayMs}，静态配置，{@code reset} 每用例还原）钉住两条路径：
 * 分类超时 → 安全侧兜底 OUT_OF_SCOPE（替代建议，不 500）；抽取超时 → 不重试、FAILED 语义
 * （violations「配置生成超时」+config=null，intent 保持分类结果）。
 * 造数据同 {@link EntryDraftIT}：直插 PUBLISHED 卡，@ItDb 按类清库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ke.agent.draft-classify-timeout-seconds=2", "ke.agent.draft-extract-timeout-seconds=1"})
@ActiveProfiles({"test", "explain-test"})
@ItDb
@ExtendWith(RedisFlush.class)
class EntryDraftTimeoutIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetStub() {
        // reset 同时清 DELAY_MS：延迟是静态配置，用例内显式设置、用例间互不残留
        StubLlmGateway.reset();
    }

    // ---------- helpers（与 EntryDraftIT 同形） ----------

    private HttpEntity<String> json(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

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

    /** 直插知识单元（永久授权），返回 id（title 类内唯一） */
    private long insertAsset(String title, String extract) {
        jdbc.update("insert into knowledge_asset (kind,title,license,content_extract) values (?,?,?,?)",
                "book", title, null, extract);
        return jdbc.queryForObject("select id from knowledge_asset where title=?", Long.class, title);
    }

    private long publishedCard(String title, String theme, long assetId) {
        jdbc.update("insert into card(theme,template_type,title,status,summary_text,sort) values(?,?,?,?,?,?)",
                theme, "TEXT", title, "PUBLISHED", title + "摘要", 0);
        long cardId = jdbc.queryForObject("select id from card where title=?", Long.class, title);
        jdbc.update("insert into card_version(card_id,version_no,content_json,sources) values(?,1,?,?)",
                cardId, "{\"summary\":\"" + title + "摘要\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文。\"}],\"related\":[]}",
                "[{\"assetId\":" + assetId + ",\"title\":\"《超时挂接志》\",\"locator\":\"第1页\"}]");
        jdbc.update("update card set current_version_id=(select id from card_version where card_id=? and version_no=1) "
                + "where id=?", cardId, cardId);
        return cardId;
    }

    private ResponseEntity<String> draft(String token, long cardId, String text) {
        return http.exchange("/api/entries/nl-draft", HttpMethod.POST,
                json("{\"cardId\":" + cardId + ",\"text\":\"" + text + "\"}", token), String.class);
    }

    /** 响应体 data 节点 → JsonNode（non_null 序列化下 config/advice 的有无用 has() 断言） */
    private static JsonNode data(String body) {
        try {
            return new ObjectMapper().readTree(body).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON: " + body, e);
        }
    }

    // ---------- 用例 ----------

    @Test
    void classifyTimeoutFallsBackToOutOfScope() {
        // Stub 延迟 3s > 分类阈值 2s：分类超时不打抽取，兜底 OUT_OF_SCOPE + 替代建议（HTTP 200 非 500）
        long assetId = insertAsset("《超时分类志》", "书院创建于唐开宝年间。");
        long card = publishedCard("超时分类岳麓卡", "academy", assetId);
        String token = newUserToken("13800009001", "超时者甲");
        StubLlmGateway.reset("EXPLAIN");
        StubLlmGateway.setDelayMs(3000);

        ResponseEntity<String> res = draft(token, card, "深入讲讲岳麓书院的讲会制度");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.intent")).isEqualTo("OUT_OF_SCOPE");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.advice")).contains("暂不支持");
        assertThat(data(res.getBody()).has("config")).isFalse();
        // 分类 1 次即超时返回，抽取不发起
        assertThat(StubLlmGateway.CALLS.get()).isEqualTo(1);
    }

    @Test
    void extractTimeoutReturnsFailedSemanticsViolations() {
        // Stub 延迟 1.5s：分类（阈值 2s）放行、抽取（阈值 1s）超时——不重试，FAILED 语义：
        // intent 保持 EXPLAIN + violations 提示重试 + config=null
        long assetId = insertAsset("《超时抽取志》", "书院创建于唐开宝年间。");
        long card = publishedCard("超时抽取岳麓卡", "academy", assetId);
        String token = newUserToken("13800009002", "超时者乙");
        StubLlmGateway.reset("EXPLAIN", "{\"name\":\"讲讲岳麓书院\",\"goal\":\"讲清讲会制度\","
                + "\"serviceType\":\"EXPLAIN\",\"assetScope\":[" + assetId + "],\"outputSpec\":\"摘要+分节正文\"}");
        StubLlmGateway.setDelayMs(1500);

        ResponseEntity<String> res = draft(token, card, "深入讲讲岳麓书院的讲会制度");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.intent")).isEqualTo("EXPLAIN");
        assertThat((int) JsonPath.read(res.getBody(), "$.data.violations.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.violations[0]")).contains("配置生成超时");
        assertThat(data(res.getBody()).has("config")).isFalse();
        // 分类 1 次 + 抽取 1 次超时（不重试），共 2 次调用
        assertThat(StubLlmGateway.CALLS.get()).isEqualTo(2);
    }
}
