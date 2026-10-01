package com.ke.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

import com.jayway.jsonpath.JsonPath;
import com.ke.agent.StubLlmGateway;
import com.ke.support.ItDb;
import com.ke.support.RedisFlush;

/**
 * 埋点事件与 6 指标（FR-O05 / 01 §5，Task 33）端到端：
 * - GET /api/wb/metrics（EDITOR/OPERATOR 门槛，EXPLORER → 403）一次返回六键（最近 7 天窗口）：
 *   deepenRate / artifactSaveRate / shareContinueRate / entrySuccessRate / sourceCompleteRate /
 *   avgLatencyMs + avgCost；
 * - 空数据 → 200 全键 null（不 500）；
 * - 造数（直插 analytics_event + agent_run）→ 六键精确值；
 * - 各域接线：真实业务流（建会话/追节点/收藏/保存入口/分享创建-浏览-接续）逐类型落 analytics_event；
 *   真实讲解/整理 run（StubLlmGateway）→ service_run / artifact_save 事件，且指标由真实事件复算成立。
 * 网关与流水线基建同 {@link com.ke.agent.SummarizeIT}（StubLlmGateway + ke_test 清库 + Redis db15 flush）；
 * 用例按 @Order 串行（空窗断言必须先于一切造数，指标断言必须先于接线用例）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "explain-test"})
@ItDb
@ExtendWith(RedisFlush.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricsIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    private static final String TEXT_CONTENT =
            "{\"summary\":\"卡片摘要\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\"}],\"related\":[]}";

    @BeforeEach
    void resetStub() {
        StubLlmGateway.reset(StubLlmGateway.validOutput(0));
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

    /** 直插 analytics_event 一行（payload 原样 JSON 文本），返回 id */
    private long insertEvent(String eventType, String payload) {
        jdbc.update("insert into analytics_event (user_id, event_type, payload) values (?, ?, ?::jsonb)",
                null, eventType, payload);
        return jdbc.queryForObject(
                "select id from analytics_event where event_type=? order by id desc limit 1", Long.class, eventType);
    }

    /** 直插 agent_run 一行（created_at 默认 now()，落在 7 天窗口内），返回 id */
    private long insertRun(Long sessionId, Long nodeId, String status, String artifactIds, Integer latencyMs) {
        jdbc.update("insert into agent_run (session_id, node_id, service_type, status, artifact_ids, latency_ms) "
                        + "values (?, ?, 'EXPLAIN', ?, ?::jsonb, ?)",
                sessionId, nodeId, status, artifactIds, latencyMs);
        return jdbc.queryForObject("select id from agent_run order by id desc limit 1", Long.class);
    }

    private long insertAsset(String title, String extract) {
        jdbc.update("insert into knowledge_asset (kind,title,license,content_extract) values (?,?,?,?)",
                "book", title, null, extract);
        return jdbc.queryForObject("select id from knowledge_asset where title=?", Long.class, title);
    }

    /** 直插 PUBLISHED 卡 + 版本（sources 直给 JSON，可空）+ current_version_id 回填，返回 cardId */
    private long publishedCard(String title, String sourcesJson) {
        jdbc.update("insert into card(theme,template_type,title,status,summary_text,sort) values(?,?,?,?,?,?)",
                "academy", "TEXT", title, "PUBLISHED", title + "摘要", 0);
        long cardId = jdbc.queryForObject("select id from card where title=?", Long.class, title);
        jdbc.update("insert into card_version(card_id,version_no,content_json,sources) values(?,1,?,?)",
                cardId, TEXT_CONTENT, sourcesJson);
        jdbc.update("update card set current_version_id=(select id from card_version where card_id=? and version_no=1) "
                + "where id=?", cardId, cardId);
        return cardId;
    }

    /** 服务入口 config JSON（AGENT_SERVICE，EXPLAIN） */
    private static String serviceConfig(String name, long assetId) {
        return "{\"name\":\"" + name + "\",\"type\":\"AGENT_SERVICE\",\"goal\":\"讲清讲会制度\","
                + "\"serviceType\":\"EXPLAIN\",\"assetScope\":[" + assetId + "],\"outputSpec\":\"摘要+分节正文\"}";
    }

    private long newSession(String token) {
        ResponseEntity<String> created = http.postForEntity("/api/sessions",
                json("{\"theme\":\"academy\",\"goal\":\"弄懂书院制度\"}", token), String.class);
        assertThat(created.getStatusCode().value()).as("session body=%s", created.getBody()).isEqualTo(201);
        return ((Number) JsonPath.read(created.getBody(), "$.data.sessionId")).longValue();
    }

    private long addNode(String token, long sessionId, long cardVersionId) {
        ResponseEntity<String> node = http.postForEntity("/api/sessions/" + sessionId + "/nodes",
                json("{\"questionText\":\"书院为何兴于唐?\",\"cardVersionId\":" + cardVersionId + "}", token), String.class);
        assertThat(node.getStatusCode().value()).as("node body=%s", node.getBody()).isEqualTo(200);
        return ((Number) JsonPath.read(node.getBody(), "$.data.nodeId")).longValue();
    }

    private long submitExplain(String token, long cardVersionId, long sessionId, long nodeId) {
        String body = "{\"cardVersionId\":" + cardVersionId + ",\"sessionId\":" + sessionId
                + ",\"nodeId\":" + nodeId + ",\"question\":\"讲讲书院学规\",\"level\":\"SIMPLE\"}";
        ResponseEntity<String> res = http.postForEntity("/api/agent/runs", json(body, token), String.class);
        assertThat(res.getStatusCode().value()).as("explain body=%s", res.getBody()).isEqualTo(202);
        return ((Number) JsonPath.read(res.getBody(), "$.data.runId")).longValue();
    }

    private long submitSummarize(String token, long sessionId, long nodeId) {
        ResponseEntity<String> res = http.postForEntity("/api/agent/summarize",
                json("{\"sessionId\":" + sessionId + ",\"nodeIds\":[" + nodeId + "]}", token), String.class);
        assertThat(res.getStatusCode().value()).as("summarize body=%s", res.getBody()).isEqualTo(202);
        return ((Number) JsonPath.read(res.getBody(), "$.data.runId")).longValue();
    }

    /** 轮询 GET run 直至终态 */
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

    /** 等待直到出现指定类型的埋点事件（接线断言的异步安全网） */
    private void awaitEvent(String eventType) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(eventCount(eventType)).as("event %s", eventType).isPositive());
    }

    private long eventCount(String eventType) {
        return jdbc.queryForObject("select count(*) from analytics_event where event_type=?", Long.class, eventType);
    }

    /** 事件 payload 的结构化读取（取该类型最新一行；payload 列 JSONB，::text 渲染带空格，用 JsonPath 取值避免串比对） */
    private Object payloadField(String eventType, String path) {
        String text = jdbc.queryForObject(
                "select payload::text from analytics_event where event_type=? order by id desc limit 1",
                String.class, eventType);
        return JsonPath.read(text, "$." + path);
    }

    private ResponseEntity<String> getMetrics(String token) {
        return http.exchange("/api/wb/metrics", HttpMethod.GET, bearer(token), String.class);
    }

    private static double metricValue(String body, String key) {
        return ((Number) JsonPath.read(body, "$.data." + key)).doubleValue();
    }

    /** 整理输出 JSON（StubLlmGateway 用） */
    private static String summarizeOutput(long assetId) {
        return "{\"keyFindings\":[{\"body\":\"关键发现:书院制度与经费保障互为表里\",\"claimType\":\"FACT\",\"citations\":["
                + assetId + "]}],\"openQuestions\":[\"经费何来?\"]}";
    }

    // ---------- 用例 ----------

    @Test
    @Order(1)
    void emptyWindowReturnsAllNullKeysWithoutError() {
        String editor = newUserToken("13800009001", "看板编辑", "EDITOR");
        ResponseEntity<String> res = getMetrics(editor);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isZero();
        // 空数据：七键齐全且全为 null（@JsonInclude(ALWAYS) 保证键存在），不 500
        for (String key : List.of("deepenRate", "artifactSaveRate", "shareContinueRate", "entrySuccessRate",
                "sourceCompleteRate", "avgLatencyMs", "avgCost")) {
            assertThat(JsonPath.<Object>read(res.getBody(), "$.data." + key))
                    .as("empty metric %s should be null: %s", key, res.getBody()).isNull();
        }
    }

    @Test
    @Order(2)
    void editorReadsSixSeededMetrics() {
        String editor = newUserToken("13800009002", "看板编辑乙", "EDITOR");

        // analytics_event 造数：2 node_visit（1 新知）→ deepen=0.5；
        // service_run DONE(真实会话) + artifact_save → saveRate=1/1=1.0；view 2 / continue 1 → 0.5
        long editorId = jdbc.queryForObject("select id from ke_user where phone=?", Long.class, "13800009002");
        jdbc.update("insert into exploration_session (user_id, theme, goal) values (?, 'academy', '指标造数')", editorId);
        long sessionId = jdbc.queryForObject("select id from exploration_session where user_id=?", Long.class, editorId);
        insertEvent("node_visit", "{\"sessionId\":" + sessionId + ",\"isNewKnowledge\":true}");
        insertEvent("node_visit", "{\"sessionId\":" + sessionId + ",\"isNewKnowledge\":false}");
        insertEvent("service_run", "{\"serviceType\":\"EXPLAIN\",\"status\":\"DONE\",\"latencyMs\":100,\"sessionId\":"
                + sessionId + "}");
        insertEvent("artifact_save", "{\"sessionId\":" + sessionId + ",\"type\":\"REPORT\"}");
        insertEvent("share_view", "{\"shareId\":1}");
        insertEvent("share_view", "{\"shareId\":2}");
        insertEvent("share_continue", "{\"originShareId\":1}");

        // agent_run 造数：无会话 run 2 条（1 DONE）→ entrySuccess=0.5；
        // DONE 2 条（1 条带 artifact）→ sourceComplete=0.5；latency 100/300 → avg=200
        insertRun(null, null, "DONE", "[11]", 100);
        insertRun(null, null, "FAILED", null, null);
        insertRun(sessionId, null, "DONE", "[]", 300);
        // 干扰项：8 天前的无会话 run 不进 7 天窗口
        jdbc.update("insert into agent_run (session_id, node_id, service_type, status, created_at) "
                + "values (null, null, 'EXPLAIN', 'FAILED', now() - interval '8 days')");

        ResponseEntity<String> res = getMetrics(editor);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isZero();
        assertThat(metricValue(res.getBody(), "deepenRate")).isEqualTo(0.5);
        assertThat(metricValue(res.getBody(), "artifactSaveRate")).isEqualTo(1.0);
        assertThat(metricValue(res.getBody(), "shareContinueRate")).isEqualTo(0.5);
        assertThat(metricValue(res.getBody(), "entrySuccessRate")).isEqualTo(0.5);
        assertThat(metricValue(res.getBody(), "sourceCompleteRate")).isEqualTo(0.5);
        assertThat(metricValue(res.getBody(), "avgLatencyMs")).isEqualTo(200.0);
        assertThat(JsonPath.<Object>read(res.getBody(), "$.data.avgCost"))
                .as("cost 未采集（网关二期）应恒 null: %s", res.getBody()).isNull();
    }

    @Test
    @Order(3)
    void explorerForbidden() {
        String explorer = newUserToken("13800009003", "访客看板", "EXPLORER");
        ResponseEntity<String> res = getMetrics(explorer);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(res.getBody(), "$.traceId")).isNotBlank();
    }

    @Test
    @Order(4)
    void businessFlowsWriteEvents() {
        // 基线（@Order(2) 已造数，同类共享库，计数一律按增量断言）
        long sessionStartBase = eventCount("session_start");
        long nodeVisitBase = eventCount("node_visit");
        long favoriteBase = eventCount("favorite");
        long entryCreateBase = eventCount("entry_create");
        long shareCreateBase = eventCount("share_create");
        long shareViewBase = eventCount("share_view");
        long shareContinueBase = eventCount("share_continue");

        long assetId = insertAsset("《埋点岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("埋点岳麓卡",
                "[{\"assetId\":" + assetId + ",\"title\":\"《埋点岳麓志》\",\"locator\":\"第1页\"}]");
        Long versionId = jdbc.queryForObject("select current_version_id from card where id=?", Long.class, card);
        String author = newUserToken("13800009004", "埋点探索者", "EXPLORER");
        long authorId = jdbc.queryForObject("select id from ke_user where phone=?", Long.class, "13800009004");

        // 建会话 → session_start；追节点（首现卡版本）→ node_visit isNewKnowledge=true
        long sessionId = newSession(author);
        awaitEvent("session_start");
        assertThat(eventCount("session_start")).isEqualTo(sessionStartBase + 1);
        assertThat(jdbc.queryForObject(
                "select user_id from analytics_event where event_type='session_start' order by id desc limit 1",
                Long.class)).isEqualTo(authorId);
        assertThat(((Number) payloadField("session_start", "sessionId")).longValue()).isEqualTo(sessionId);
        assertThat((String) payloadField("session_start", "theme")).isEqualTo("academy");
        long nodeId = addNode(author, sessionId, versionId);
        awaitEvent("node_visit");
        assertThat(eventCount("node_visit")).isEqualTo(nodeVisitBase + 1);
        assertThat((Boolean) payloadField("node_visit", "isNewKnowledge")).isTrue();
        assertThat(((Number) payloadField("node_visit", "sessionId")).longValue()).isEqualTo(sessionId);
        assertThat(((Number) payloadField("node_visit", "nodeId")).longValue()).isEqualTo(nodeId);

        // 收藏 → favorite
        ResponseEntity<String> fav = http.exchange("/api/cards/" + card + "/favorite", HttpMethod.POST,
                json("{}", author), String.class);
        assertThat(fav.getStatusCode().value()).as("fav body=%s", fav.getBody()).isEqualTo(200);
        awaitEvent("favorite");
        assertThat(eventCount("favorite")).isEqualTo(favoriteBase + 1);
        assertThat(((Number) payloadField("favorite", "cardId")).longValue()).isEqualTo(card);

        // 保存入口 → entry_create（scope/testTotal 在 payload）
        ResponseEntity<String> entry = http.postForEntity("/api/entries",
                json("{\"cardId\":" + card + ",\"config\":" + serviceConfig("埋点讲岳麓", assetId)
                        + ",\"scope\":\"PRIVATE\"}", author), String.class);
        assertThat(entry.getStatusCode().value()).as("entry body=%s", entry.getBody()).isEqualTo(201);
        awaitEvent("entry_create");
        assertThat(eventCount("entry_create")).isEqualTo(entryCreateBase + 1);
        assertThat((String) payloadField("entry_create", "scope")).isEqualTo("PRIVATE");
        assertThat((Integer) payloadField("entry_create", "testTotal")).isZero();

        // 分享创建 → 匿名浏览 → 接续副本
        ResponseEntity<String> share = http.postForEntity("/api/shares",
                json("{\"objectType\":\"SESSION\",\"objectId\":" + sessionId + ",\"nodeIds\":[" + nodeId + "],"
                        + "\"title\":\"埋点分享\"}", author), String.class);
        assertThat(share.getStatusCode().value()).as("share body=%s", share.getBody()).isEqualTo(201);
        String token = JsonPath.read(share.getBody(), "$.data.token");
        awaitEvent("share_create");
        assertThat(eventCount("share_create")).isEqualTo(shareCreateBase + 1);
        assertThat(((Number) payloadField("share_create", "sessionId")).longValue()).isEqualTo(sessionId);

        ResponseEntity<String> view = http.getForEntity("/s/" + token, String.class);
        assertThat(view.getStatusCode().value()).isEqualTo(200);
        awaitEvent("share_view");
        assertThat(eventCount("share_view")).isEqualTo(shareViewBase + 1);
        // 匿名浏览：user_id 为 null
        assertThat(jdbc.queryForObject(
                "select user_id from analytics_event where event_type='share_view' order by id desc limit 1",
                Long.class)).isNull();

        String receiver = newUserToken("13800009005", "埋点接续者", "EXPLORER");
        ResponseEntity<String> cont = http.postForEntity("/s/" + token + "/continue", json("{}", receiver),
                String.class);
        assertThat(cont.getStatusCode().value()).as("continue body=%s", cont.getBody()).isEqualTo(200);
        awaitEvent("share_continue");
        assertThat(eventCount("share_continue")).isEqualTo(shareContinueBase + 1);
        assertThat(payloadField("share_continue", "originShareId")).isNotNull();
    }

    @Test
    @Order(5)
    void realRunsWriteServiceRunAndArtifactSaveAndFeedMetrics() {
        long serviceRunBase = eventCount("service_run");
        long artifactSaveBase = eventCount("artifact_save");
        long assetId = insertAsset("《运行岳麓志》", "书院创建于唐开宝年间。");
        long card = publishedCard("运行岳麓卡",
                "[{\"assetId\":" + assetId + ",\"title\":\"《运行岳麓志》\",\"locator\":\"第1页\"}]");
        Long versionId = jdbc.queryForObject("select current_version_id from card where id=?", Long.class, card);
        String author = newUserToken("13800009006", "运行探索者", "EXPLORER");
        long sessionId = newSession(author);
        long nodeId = addNode(author, sessionId, versionId);

        // 真实讲解 run → DONE → service_run(EXPLAIN, DONE, sessionId, latencyMs)
        StubLlmGateway.reset(StubLlmGateway.validOutput(assetId));
        long explainRun = submitExplain(author, versionId, sessionId, nodeId);
        assertThat((String) JsonPath.read(awaitTerminal(author, explainRun), "$.data.status")).isEqualTo("DONE");
        awaitEvent("service_run");
        assertThat(eventCount("service_run")).isGreaterThanOrEqualTo(serviceRunBase + 1);
        assertThat((String) payloadField("service_run", "serviceType")).isEqualTo("EXPLAIN");
        assertThat((String) payloadField("service_run", "status")).isEqualTo("DONE");
        assertThat(((Number) payloadField("service_run", "sessionId")).longValue()).isEqualTo(sessionId);
        assertThat(payloadField("service_run", "latencyMs")).isNotNull();

        // 真实整理 run → DONE → service_run(SUMMARIZE, DONE) + artifact_save
        StubLlmGateway.reset(summarizeOutput(assetId));
        long summarizeRun = submitSummarize(author, sessionId, nodeId);
        assertThat((String) JsonPath.read(awaitTerminal(author, summarizeRun), "$.data.status")).isEqualTo("DONE");
        awaitEvent("artifact_save");
        assertThat(eventCount("artifact_save")).isEqualTo(artifactSaveBase + 1);
        assertThat(eventCount("service_run")).isGreaterThanOrEqualTo(serviceRunBase + 2);
        assertThat(((Number) payloadField("artifact_save", "sessionId")).longValue()).isEqualTo(sessionId);
        assertThat((String) payloadField("artifact_save", "type")).isEqualTo("REPORT");

        // 六键由真实事件复算（跨用例累计，@Order 确定性）：
        // deepen：node_visit 4 条（造数 2 含 1 假 + 本类 2 条全真）→ 3/4=0.75
        // saveRate：artifact_save 2 条 / DONE 会话 {造数会话, 本会话}=2 → 1.0
        // shareContinue：continue 2 / view 3（造数 1/2 + 用例 4 各 1）→ 2/3
        // entrySuccess：无会话 run=造数 2 条（1 DONE）→ 0.5
        // sourceComplete：DONE run 4 条（造数带 artifact 1 + 本类 2）→ 3/4=0.75
        // avgLatency：造数 100/300 + 两次 stub run → ≥100
        ResponseEntity<String> res = getMetrics(newUserToken("13800009007", "看板编辑丙", "EDITOR"));
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat(metricValue(res.getBody(), "deepenRate")).isEqualTo(0.75);
        assertThat(metricValue(res.getBody(), "artifactSaveRate")).isEqualTo(1.0);
        assertThat(metricValue(res.getBody(), "shareContinueRate")).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(metricValue(res.getBody(), "entrySuccessRate")).isEqualTo(0.5);
        assertThat(metricValue(res.getBody(), "sourceCompleteRate")).isEqualTo(0.75);
        assertThat(metricValue(res.getBody(), "avgLatencyMs")).isGreaterThanOrEqualTo(100.0);
        assertThat(JsonPath.<Object>read(res.getBody(), "$.data.avgCost"))
                .as("cost 未采集（网关二期）应恒 null: %s", res.getBody()).isNull();
    }
}
