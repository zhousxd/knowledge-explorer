package com.ke.entry;

import com.jayway.jsonpath.JsonPath;
import com.ke.agent.StubLlmGateway;
import com.ke.support.ItDb;
import com.ke.support.RedisFlush;
import org.awaitility.Awaitility;
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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口保存/试运行/双通道发布端到端（FR-N02–N07，Task 28）：
 * POST /api/entries {cardId, config, scope}——保存路径必过 EntryConfigValidator（越权 assetScope
 * → 400 清单，没有例外路径）；PRIVATE 直接 ACTIVE；PUBLIC 直接 ACTIVE 但挂 review_task(ENTRY)，
 * approve 维持 ACTIVE、reject 置 DISABLED（P1-4 ReviewService 队列复用）。
 * POST /api/entries/{id}/test——LINK_CARD 400；服务入口=无会话真实执行一次（P5-18：不落 artifact
 * 只回状态），配额消耗，entry.test_total +1；非作者 403。
 * GET /api/entries/mine——我的入口（状态/所属卡题/试运行次数）。
 * PUT /api/entries/{id}/scope——PRIVATE→PUBLIC 挂审核，PUBLIC→PRIVATE 直接，非作者 403。
 * 网关用 StubLlmGateway（explain-test），直连 WSL ke_test，Awaitility 等待异步终态。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "explain-test"})
@ItDb
@ExtendWith(RedisFlush.class)
class EntryMutationIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetStub() {
        StubLlmGateway.reset();
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

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    /** 注册（EXPLORER 默认角色）+ 登录拿 accessToken；role 非空时先提权再登录 */
    private String newUserToken(String phone, String nickname, String role) {
        ResponseEntity<String> reg = http.postForEntity("/api/auth/register",
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\",\"nickname\":\"" + nickname + "\"}", null),
                String.class);
        assertThat(reg.getStatusCode().value()).as("register body=%s", reg.getBody()).isEqualTo(201);
        if (role != null && !"EXPLORER".equals(role)) {
            jdbc.update("update ke_user set role=? where phone=?", role, phone);
        }
        ResponseEntity<String> login = http.postForEntity("/api/auth/login",
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\"}", null), String.class);
        assertThat(login.getStatusCode().value()).as("login body=%s", login.getBody()).isEqualTo(200);
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    private long userId(String phone) {
        return jdbc.queryForObject("select id from ke_user where phone=?", Long.class, phone);
    }

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

    /** 服务入口 config JSON（AGENT_SERVICE，EXPLAIN 或 COMPARE） */
    private static String serviceConfig(String name, String serviceType, long assetId) {
        return "{\"name\":\"" + name + "\",\"type\":\"AGENT_SERVICE\",\"goal\":\"讲清讲会制度\","
                + "\"serviceType\":\"" + serviceType + "\",\"assetScope\":[" + assetId + "],"
                + "\"outputSpec\":\"摘要+分节正文\"}";
    }

    private ResponseEntity<String> save(String token, long cardId, String configJson, String scope) {
        String body = "{\"cardId\":" + cardId + (configJson == null ? "" : ",\"config\":" + configJson)
                + (scope == null ? "" : ",\"scope\":\"" + scope + "\"") + "}";
        return http.postForEntity("/api/entries", json(body, token), String.class);
    }

    private long pendingEntryReviewId(String editorToken, long entryId) {
        AtomicReference<Long> found = new AtomicReference<>();
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<String> res = http.exchange(
                    "/api/wb/reviews?status=PENDING&objectType=ENTRY", HttpMethod.GET,
                    bearer(editorToken), String.class);
            assertThat(res.getStatusCode().value()).as("queue body=%s", res.getBody()).isEqualTo(200);
            List<Integer> ids = JsonPath.read(res.getBody(), "$.data.items[?(@.objectId == " + entryId + ")].id");
            assertThat(ids).as("entry %s should be queued: %s", entryId, res.getBody()).isNotEmpty();
            found.set(ids.get(0).longValue());
        });
        return found.get();
    }

    /** 轮询 GET run 直至终态（无会话 run 不落 artifact，只回状态） */
    private String awaitTerminal(String token, long runId) {
        AtomicReference<String> body = new AtomicReference<>();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = http.exchange("/api/agent/runs/" + runId, HttpMethod.GET,
                    bearer(token), String.class);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            String status = JsonPath.read(res.getBody(), "$.data.status");
            assertThat(status).isIn("DONE", "FAILED", "TIMEOUT");
            body.set(res.getBody());
        });
        return body.get();
    }

    // ---------- 保存：私人/公共/校验拦截 ----------

    @Test
    void savePrivateEntryActiveImmediately() {
        long assetId = insertAsset("《保存岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("保存私人岳麓卡", "academy",
                "[{\"assetId\":" + assetId + ",\"title\":\"《保存岳麓志》\",\"locator\":\"第1页\"}]");
        String token = newUserToken("13800008001", "保存者甲", null);

        ResponseEntity<String> res = save(token, card, serviceConfig("讲讲岳麓书院", "EXPLAIN", assetId), "PRIVATE");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(201);
        long entryId = ((Number) JsonPath.read(res.getBody(), "$.data.entryId")).longValue();
        assertThat((String) JsonPath.read(res.getBody(), "$.data.scope")).isEqualTo("PRIVATE");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("ACTIVE");

        // 私人入口在卡入口列表对作者可见（mine=true, scope=PRIVATE）
        ResponseEntity<String> list = http.exchange("/api/cards/" + card + "/entries", HttpMethod.GET,
                bearer(token), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        List<Boolean> mines = JsonPath.read(list.getBody(),
                "$.data.defaultEntries[?(@.id == " + entryId + ")].mine");
        assertThat(mines).containsExactly(true);

        // mine 列表：含状态/所属卡题/试运行次数(0)
        ResponseEntity<String> mine = http.exchange("/api/entries/mine", HttpMethod.GET,
                bearer(token), String.class);
        assertThat(mine.getStatusCode().value()).as("body=%s", mine.getBody()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(mine.getBody(), "$.data[0].id")).longValue()).isEqualTo(entryId);
        assertThat((String) JsonPath.read(mine.getBody(), "$.data[0].cardTitle")).isEqualTo("保存私人岳麓卡");
        assertThat((String) JsonPath.read(mine.getBody(), "$.data[0].status")).isEqualTo("ACTIVE");
        assertThat((int) JsonPath.read(mine.getBody(), "$.data[0].testTotal")).isZero();

        // 不挂审核任务
        assertThat(jdbc.queryForObject(
                "select count(*) from review_task where object_type='ENTRY' and object_id=?",
                Long.class, entryId)).isZero();
    }

    @Test
    void savePublicEntryQueuesReviewAndApproveKeepsActive() {
        long assetId = insertAsset("《送审岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("送审公共岳麓卡", "academy",
                "[{\"assetId\":" + assetId + ",\"title\":\"《送审岳麓志》\",\"locator\":\"第1页\"}]");
        String author = newUserToken("13800008002", "送审者乙", null);
        String editor = newUserToken("13800008003", "审入口的编辑", "EDITOR");

        ResponseEntity<String> res = save(author, card, serviceConfig("公共讲岳麓", "EXPLAIN", assetId), "PUBLIC");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(201);
        long entryId = ((Number) JsonPath.read(res.getBody(), "$.data.entryId")).longValue();
        // 决策:PUBLIC 入口创建即 ACTIVE,但挂 PENDING 审核
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("ACTIVE");

        long reviewId = pendingEntryReviewId(editor, entryId);
        ResponseEntity<String> approve = http.postForEntity("/api/wb/reviews/" + reviewId + "/approve",
                json("{}", editor), String.class);
        assertThat(approve.getStatusCode().value()).as("body=%s", approve.getBody()).isEqualTo(200);

        // approve 分支:入口保持 ACTIVE(scope=PUBLIC 已可见,审核通过即维持)
        assertThat((String) JsonPath.read(approve.getBody(), "$.data.status")).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select status from entry where id=?", String.class, entryId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void rejectPublicEntryDisablesIt() {
        long assetId = insertAsset("《驳回岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("驳回公共岳麓卡", "academy",
                "[{\"assetId\":" + assetId + ",\"title\":\"《驳回岳麓志》\",\"locator\":\"第1页\"}]");
        String author = newUserToken("13800008004", "送审者丙", null);
        String editor = newUserToken("13800008005", "驳回入口的编辑", "EDITOR");

        long entryId = ((Number) JsonPath.read(
                save(author, card, serviceConfig("驳回讲岳麓", "EXPLAIN", assetId), "PUBLIC").getBody(),
                "$.data.entryId")).longValue();
        long reviewId = pendingEntryReviewId(editor, entryId);

        ResponseEntity<String> reject = http.postForEntity("/api/wb/reviews/" + reviewId + "/reject",
                json("{\"notes\":\"出处不实\"}", editor), String.class);
        assertThat(reject.getStatusCode().value()).as("body=%s", reject.getBody()).isEqualTo(200);
        // reject 分支:入口 DISABLED(作者卡页不再可见)
        assertThat(jdbc.queryForObject("select status from entry where id=?", String.class, entryId))
                .isEqualTo("DISABLED");
    }

    @Test
    void saveRejectsOverreachAssetScopeWithViolationList() {
        long assetId = insertAsset("《拦截岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("拦截岳麓卡", "academy",
                "[{\"assetId\":" + assetId + ",\"title\":\"《拦截岳麓志》\",\"locator\":\"第1页\"}]");
        String token = newUserToken("13800008006", "保存者丁", null);

        // 越权 assetScope(99999 不在授权集):保存端硬拦截 400 + 清单消息,没有草稿期的自动收窄
        ResponseEntity<String> res = save(token, card, serviceConfig("越权入口", "EXPLAIN", 99999L), "PRIVATE");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((int) JsonPath.read(res.getBody(), "$.code")).isEqualTo(400);
        assertThat((String) JsonPath.read(res.getBody(), "$.message")).contains("资料范围越权").contains("99999");
        assertThat(jdbc.queryForObject("select count(*) from entry where card_id=?", Long.class, card)).isZero();
    }

    @Test
    void saveSameThemeLinkCardWithoutRelationParts() {
        long source = publishedCard("同链上篇卡", "academy", null);
        long target = publishedCard("同链下篇卡", "academy", null);
        String token = newUserToken("13800008007", "保存者戊", null);

        // 同主题 LINK_CARD:三要件须空(与 Validator/RelationGuard 同则),直接保存
        String config = "{\"name\":\"学规下篇\",\"type\":\"LINK_CARD\",\"targetCardId\":" + target + "}";
        ResponseEntity<String> res = save(token, source, config, "PRIVATE");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(201);
        assertThat(((Number) JsonPath.read(res.getBody(), "$.data.entryId")).longValue()).isPositive();
    }

    // ---------- 试运行 ----------

    @Test
    void testRunExecutesOnceAndCounts() {
        long assetId = insertAsset("《试运行岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("试运行岳麓卡", "academy",
                "[{\"assetId\":" + assetId + ",\"title\":\"《试运行岳麓志》\",\"locator\":\"第1页\"}]");
        String token = newUserToken("13800008008", "试运行者甲", null);
        StubLlmGateway.reset(StubLlmGateway.validOutput(assetId));
        long entryId = ((Number) JsonPath.read(
                save(token, card, serviceConfig("试运行讲岳麓", "EXPLAIN", assetId), "PRIVATE").getBody(),
                "$.data.entryId")).longValue();

        ResponseEntity<String> res = http.postForEntity("/api/entries/" + entryId + "/test",
                json("{}", token), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(202);
        long runId = ((Number) JsonPath.read(res.getBody(), "$.data.runId")).longValue();

        // 无会话 run(P5-18):真实执行到 DONE,不落 artifact
        String body = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(body, "$.data.status")).isEqualTo("DONE");
        assertThat(body).doesNotContain("\"artifact\"");

        // 计数:test_total +1(非 LINK_CARD 服务入口)
        ResponseEntity<String> mine = http.exchange("/api/entries/mine", HttpMethod.GET,
                bearer(token), String.class);
        assertThat((int) JsonPath.read(mine.getBody(), "$.data[0].testTotal")).isEqualTo(1);
    }

    @Test
    void testLinkCardEntryRejectedAndOthersForbidden() {
        long source = publishedCard("试运行链上卡", "academy", null);
        long target = publishedCard("试运行链下卡", "academy", null);
        String author = newUserToken("13800008009", "试运行者乙", null);
        String other = newUserToken("13800008010", "试运行者丙", null);

        String config = "{\"name\":\"链接下篇\",\"type\":\"LINK_CARD\",\"targetCardId\":" + target + "}";
        long entryId = ((Number) JsonPath.read(save(author, source, config, "PRIVATE").getBody(),
                "$.data.entryId")).longValue();

        // LINK_CARD:链接类入口无需试运行
        ResponseEntity<String> res = http.postForEntity("/api/entries/" + entryId + "/test",
                json("{}", author), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((String) JsonPath.read(res.getBody(), "$.message")).contains("链接类入口无需试运行");

        // 非作者:403(存在性由作者侧 404 语义不适用于本人可见的私人入口——直接 403)
        ResponseEntity<String> forbidden = http.postForEntity("/api/entries/" + entryId + "/test",
                json("{}", other), String.class);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
    }

    // ---------- scope 切换 ----------

    @Test
    void scopeSwitchQueuesReviewThenBackDirect() {
        long assetId = insertAsset("《切换岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("切换岳麓卡", "academy",
                "[{\"assetId\":" + assetId + ",\"title\":\"《切换岳麓志》\",\"locator\":\"第1页\"}]");
        String author = newUserToken("13800008011", "切换者甲", null);
        long entryId = ((Number) JsonPath.read(
                save(author, card, serviceConfig("切换讲岳麓", "EXPLAIN", assetId), "PRIVATE").getBody(),
                "$.data.entryId")).longValue();
        String editor = newUserToken("13800008012", "切换审核编辑", "EDITOR");

        // PRIVATE→PUBLIC:挂审核,入口保持 ACTIVE
        ResponseEntity<String> toPublic = http.exchange("/api/entries/" + entryId + "/scope",
                HttpMethod.PUT, json("{\"scope\":\"PUBLIC\"}", author), String.class);
        assertThat(toPublic.getStatusCode().value()).as("body=%s", toPublic.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(toPublic.getBody(), "$.data.scope")).isEqualTo("PUBLIC");
        long reviewId = pendingEntryReviewId(editor, entryId);
        assertThat(jdbc.queryForObject("select status from entry where id=?", String.class, entryId))
                .isEqualTo("ACTIVE");
        http.postForEntity("/api/wb/reviews/" + reviewId + "/approve", json("{}", editor), String.class);

        // PUBLIC→PRIVATE:直接,不再挂审核
        ResponseEntity<String> toPrivate = http.exchange("/api/entries/" + entryId + "/scope",
                HttpMethod.PUT, json("{\"scope\":\"PRIVATE\"}", author), String.class);
        assertThat(toPrivate.getStatusCode().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(toPrivate.getBody(), "$.data.scope")).isEqualTo("PRIVATE");
        assertThat(jdbc.queryForObject(
                "select count(*) from review_task where object_type='ENTRY' and object_id=? and status='PENDING'",
                Long.class, entryId)).isZero();

        // 非作者改 scope:403
        ResponseEntity<String> forbidden = http.exchange("/api/entries/" + entryId + "/scope",
                HttpMethod.PUT, json("{\"scope\":\"PUBLIC\"}", newUserToken("13800008013", "切换者乙", null)),
                String.class);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
    }
}
