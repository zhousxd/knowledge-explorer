package com.ke.agent;

import com.jayway.jsonpath.JsonPath;
import com.ke.service.quota.QuotaService;
import com.ke.support.ItDb;
import com.ke.support.RedisFlush;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 智能服务护栏端到端（FR-S04/S13，Task 20）：
 * - 配额（FR-S04）：QuotaService Redis INCR，30 次/日（ke.quota.daily-limit），超限 submit → 429 envelope；
 * - 超时（FR-S13）：ke.agent.llm-timeout-seconds=1 + Stub delay 3s → run 置 TIMEOUT（不重试）；
 * - 配额查询：GET /api/me/quota → {used,limit,remaining,resetAt}。
 * 网关用 StubLlmGateway（profile explain-test）；直连 WSL ke_test（@ItDb）+ Redis db15（RedisFlush）。
 * 超时阈值压到 1s（真实阈值 60s 测试等不起），Stub delay 3s 稳定越过阈值。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ke.agent.llm-timeout-seconds=1")
@ActiveProfiles({"test", "explain-test"})
@ItDb
@ExtendWith(RedisFlush.class)
class GuardrailIT {

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;

    private static final String TEXT_CONTENT =
            "{\"summary\":\"卡片摘要\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\"}],\"related\":[]}";

    @BeforeEach
    void resetStub() {
        StubLlmGateway.reset(StubLlmGateway.validOutput(0));
    }

    // ---------- 数据准备（与 ExplainPipelineIT 同形） ----------

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

    /** 注册 → 登录拿 accessToken */
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

    private long userIdByPhone(String phone) {
        return jdbc.queryForObject("select id from ke_user where phone=?", Long.class, phone);
    }

    private long insertAsset(String title, String extract) {
        jdbc.update("insert into knowledge_asset (kind,title,license,license_expire,content_extract) values (?,?,?,?,?)",
                "book", title, null, null, extract);
        return jdbc.queryForObject("select id from knowledge_asset where title=?", Long.class, title);
    }

    private long insertPublishedCard(String title, String sourcesJson) {
        jdbc.update("insert into card (theme,template_type,title,status) values (?,?,?,?)",
                "academy", "TEXT", title, "PUBLISHED");
        long cardId = jdbc.queryForObject("select id from card where title=?", Long.class, title);
        jdbc.update("insert into card_version (card_id,version_no,content_json,sources) values (?,?,?,?)",
                cardId, 1, TEXT_CONTENT, sourcesJson);
        Long versionId = jdbc.queryForObject("select id from card_version where card_id=?", Long.class, cardId);
        jdbc.update("update card set current_version_id=? where id=?", versionId, cardId);
        return versionId;
    }

    private long[] newSessionWithNode(String token, long cardVersionId) {
        ResponseEntity<String> created = http.postForEntity("/api/sessions",
                json("{\"theme\":\"academy\",\"goal\":\"弄懂书院制度\"}", token), String.class);
        long sessionId = ((Number) JsonPath.read(created.getBody(), "$.data.sessionId")).longValue();
        ResponseEntity<String> node = http.postForEntity("/api/sessions/" + sessionId + "/nodes",
                json("{\"cardVersionId\":" + cardVersionId + ",\"questionText\":\"岳麓书院为何建在山中？\"}", token),
                String.class);
        assertThat(node.getStatusCode().value()).as("node body=%s", node.getBody()).isEqualTo(200);
        long nodeId = ((Number) JsonPath.read(node.getBody(), "$.data.nodeId")).longValue();
        return new long[]{sessionId, nodeId};
    }

    private ResponseEntity<String> submit(String token, long cardVersionId, Long sessionId, Long nodeId,
                                          String question, String level) {
        String body = "{\"cardVersionId\":" + cardVersionId
                + (sessionId == null ? "" : ",\"sessionId\":" + sessionId)
                + (nodeId == null ? "" : ",\"nodeId\":" + nodeId)
                + ",\"question\":\"" + question + "\",\"level\":\"" + level + "\"}";
        return http.postForEntity("/api/agent/runs", json(body, token), String.class);
    }

    private long submitOk(String token, long cardVersionId, long[] ids, String question) {
        ResponseEntity<String> res = submit(token, cardVersionId, ids[0], ids[1], question, "SIMPLE");
        assertThat(res.getStatusCode().value()).as("submit body=%s", res.getBody()).isEqualTo(202);
        return ((Number) JsonPath.read(res.getBody(), "$.data.runId")).longValue();
    }

    /** 轮询 GET run 直至终态（DONE/FAILED/TIMEOUT），返回末次响应 */
    private ResponseEntity<String> awaitTerminal(String token, long runId) {
        AtomicReference<ResponseEntity<String>> ref = new AtomicReference<>();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = http.exchange("/api/agent/runs/" + runId, HttpMethod.GET, bearer(token), String.class);
            ref.set(res);
            String status = JsonPath.read(res.getBody(), "$.data.status");
            assertThat(status).isIn("DONE", "FAILED", "TIMEOUT");
        });
        return ref.get();
    }

    // ---------- 用例 ----------

    @Test
    void quotaBlocks31stCall() {
        // 决策：不真跑 31 次串行流水线——直接 Redis 预置 quota key=30（当日已用满），
        // 第 1 次提交即 429；另一未触限用户提交不受影响（配额按用户隔离）
        long assetId = insertAsset("《配额资料》", "书院讲学记录摘录。");
        long versionId = insertPublishedCard("配额卡",
                "[{\"assetId\":" + assetId + ",\"title\":\"《配额资料》\",\"locator\":\"第1页\",\"license\":null}]");
        String token = newUserToken("13833300001", "甲");
        long uid = userIdByPhone("13833300001");
        long[] ids = newSessionWithNode(token, versionId);
        redis.opsForValue().set(QuotaService.keyOf(uid, LocalDate.now()), "30");

        ResponseEntity<String> blocked = submit(token, versionId, ids[0], ids[1], "还会讲吗？", "SIMPLE");
        assertThat(blocked.getStatusCode().value()).as("body=%s", blocked.getBody()).isEqualTo(429);

        String otherToken = newUserToken("13833300002", "乙");
        StubLlmGateway.reset(StubLlmGateway.validOutput(assetId));
        long runId = submitOk(otherToken, versionId, newSessionWithNode(otherToken, versionId), "正常提交不受他人配额影响");
        assertThat((String) JsonPath.read(awaitTerminal(otherToken, runId).getBody(), "$.data.status")).isEqualTo("DONE");
    }

    @Test
    void rateLimitedText() {
        // 429 envelope：HTTP 429 + code=429 + 消息含「已用完」（04 §8.6 文案）
        long versionId = insertPublishedCard("限流文案卡", null);
        String token = newUserToken("13833300003", "丙");
        long uid = userIdByPhone("13833300003");
        long[] ids = newSessionWithNode(token, versionId);
        redis.opsForValue().set(QuotaService.keyOf(uid, LocalDate.now()), "30");

        ResponseEntity<String> res = submit(token, versionId, ids[0], ids[1], "？", "SIMPLE");
        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(429);
        assertThat((String) JsonPath.read(res.getBody(), "$.message")).contains("已用完");
    }

    @Test
    void llmTimeoutTimesOut() {
        // Stub delay 3s > 阈值 1s → run 置 TIMEOUT（RUNNING→TIMEOUT 合法迁移），error 固定文案；
        // 超时不触发重试（LLM 已耗满预算）：CALLS 恰为 1
        long assetId = insertAsset("《超时资料》", "内容摘录。");
        long versionId = insertPublishedCard("超时卡",
                "[{\"assetId\":" + assetId + ",\"title\":\"《超时资料》\",\"locator\":\"第1页\",\"license\":null}]");
        String token = newUserToken("13833300004", "丁");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset(StubLlmGateway.validOutput(assetId));
        StubLlmGateway.setDelayMs(3000);
        long runId = submitOk(token, versionId, ids, "等不到的回答？");

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("TIMEOUT");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.error")).contains("服务超时");
        assertThat(StubLlmGateway.CALLS.get()).as("超时不重试").isEqualTo(1);
    }

    @Test
    void quotaRemainingEndpoint() {
        // GET /api/me/quota：提交前 {used:0,limit:30,remaining:30,resetAt=当日 24:00}；提交一次后 used=1
        long versionId = insertPublishedCard("配额查询卡", null);
        String token = newUserToken("13833300007", "庚");
        long[] ids = newSessionWithNode(token, versionId);

        ResponseEntity<String> before = http.exchange("/api/me/quota", HttpMethod.GET, bearer(token), String.class);
        assertThat(before.getStatusCode().value()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(before.getBody(), "$.data.used")).isZero();
        assertThat((Integer) JsonPath.read(before.getBody(), "$.data.limit")).isEqualTo(30);
        assertThat((Integer) JsonPath.read(before.getBody(), "$.data.remaining")).isEqualTo(30);
        OffsetDateTime resetAt = OffsetDateTime.parse((String) JsonPath.read(before.getBody(), "$.data.resetAt"));
        assertThat(resetAt.getHour()).as("resetAt 应为当日 24:00（次日零点）").isZero();
        assertThat(resetAt.getMinute()).isZero();
        assertThat(resetAt.toLocalDate()).isEqualTo(LocalDate.now().plusDays(1));

        long runId = submitOk(token, versionId, ids, "消耗一次配额");
        assertThat((String) JsonPath.read(awaitTerminal(token, runId).getBody(), "$.data.status")).isEqualTo("DONE");

        ResponseEntity<String> after = http.exchange("/api/me/quota", HttpMethod.GET, bearer(token), String.class);
        assertThat((Integer) JsonPath.read(after.getBody(), "$.data.used")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(after.getBody(), "$.data.remaining")).isEqualTo(29);
    }
}
