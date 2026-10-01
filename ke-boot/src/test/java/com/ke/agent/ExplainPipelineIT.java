package com.ke.agent;

import com.jayway.jsonpath.JsonPath;
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
 * 讲解服务流水线端到端（FR-S02 / 02 §5.2）：
 * POST /api/agent/runs（202 QUEUED）→ 虚拟线程异步执行（受限检索 → LLM → Jackson 绑定 ExplainOutput，
 * 失败重试一次 → artifact(EXPLAIN) + citation(object_type=agent_run) 落库 → agent_run 回写 DONE/artifact_ids）
 * → GET /api/agent/runs/{id} 读终态与 artifact。
 * 网关用 StubLlmGateway（profile explain-test，@Primary 压过 test 的 MockLlmGateway），按测试脚本应答；
 * 直连 WSL ke_test（@ItDb 类前清库重建），Awaitility 等待异步终态。
 * Task 20 起 submit 消费每日配额（Redis db15 的 user:quota:{userId} 键，跨类残留而 ke_test 用户 id
 * 每类从 1 重排）——类前 RedisFlush flushdb，与 ke_user 同步归零，避免历史配额键 429 污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "explain-test"})
@ItDb
@ExtendWith(RedisFlush.class)
class ExplainPipelineIT {

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

    // ---------- 数据准备 ----------

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

    /** 注册（API）→ 按需 JdbcTemplate 提权 → 登录拿 accessToken */
    private String newUserToken(String phone, String nickname, String role) {
        ResponseEntity<String> reg = http.postForEntity("/api/auth/register",
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\",\"nickname\":\"" + nickname + "\"}", null),
                String.class);
        assertThat(reg.getStatusCode().value()).as("register body=%s", reg.getBody()).isEqualTo(201);
        if (!"EXPLORER".equals(role)) {
            jdbc.update("update ke_user set role=? where phone=?", role, phone);
        }
        ResponseEntity<String> login = http.postForEntity("/api/auth/login",
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\"}", null), String.class);
        assertThat(login.getStatusCode().value()).as("login body=%s", login.getBody()).isEqualTo(200);
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    /** 直插知识单元（绕开 CSV 导入），返回 id。licenseExpire 传 null = 永久授权 */
    private long insertAsset(String title, String extract, java.time.LocalDate licenseExpire) {
        jdbc.update("insert into knowledge_asset (kind,title,license,license_expire,content_extract) values (?,?,?,?,?)",
                "book", title, null, licenseExpire, extract);
        return jdbc.queryForObject("select id from knowledge_asset where title=?", Long.class, title);
    }

    /** 直插 PUBLISHED 卡 + 版本（sources 直给 JSON），返回 cardVersionId */
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

    /** 属主用户的会话 + 挂卡节点，返回 {sessionId, nodeId} */
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

    private long submitRun(String token, long cardVersionId, Long sessionId, Long nodeId, String question, String level) {
        String body = "{\"cardVersionId\":" + cardVersionId
                + (sessionId == null ? "" : ",\"sessionId\":" + sessionId)
                + (nodeId == null ? "" : ",\"nodeId\":" + nodeId)
                + ",\"question\":\"" + question + "\",\"level\":\"" + level + "\"}";
        ResponseEntity<String> res = http.postForEntity("/api/agent/runs", json(body, token), String.class);
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
    void explainHappyPath() {
        long assetId = insertAsset("《岳麓书院志》", "书院创建于唐开宝年间，朱熹曾在此讲学，" + "史".repeat(200), null);
        long versionId = insertPublishedCard("岳麓书院",
                "[{\"assetId\":" + assetId + ",\"title\":\"《岳麓书院志》\",\"locator\":\"第1页\",\"license\":null}]");
        String token = newUserToken("13811100001", "甲", "EXPLORER");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset(StubLlmGateway.validOutput(assetId));
        long runId = submitRun(token, versionId, ids[0], ids[1], "岳麓书院的历史？", "SIMPLE");

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.model")).isNotBlank();

        // artifact.content_json = {output: ExplainOutput, sources: 检索快照}
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.output.summary")).isEqualTo("讲解摘要");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.output.sections[0].claimType")).isEqualTo("FACT");
        int cited = ((Number) JsonPath.read(res.getBody(), "$.data.artifact.output.sections[0].citations[0]")).intValue();
        assertThat(cited).isEqualTo((int) assetId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.sources['" + assetId + "']")).contains("岳麓书院");

        // artifact_ids 回写 agent_run、artifact 落库、citation(object_type=agent_run) 行存在
        String artifactIds = jdbc.queryForObject("select artifact_ids::text from agent_run where id=?", String.class, runId);
        Integer artifactId = jdbc.queryForObject("select id from artifact where session_id=? and type='EXPLAIN' order by id desc limit 1",
                Integer.class, ids[0]);
        assertThat(artifactIds).contains(String.valueOf(artifactId));
        Integer citationRows = jdbc.queryForObject(
                "select count(*) from citation where object_type='agent_run' and object_id=? and asset_id=?",
                Integer.class, runId, assetId);
        assertThat(citationRows).isEqualTo(1);
    }

    @Test
    void llmBadJsonRetriedOnce() {
        long assetId = insertAsset("《重试资料》", "可检索的内容摘录。", null);
        long versionId = insertPublishedCard("重试卡",
                "[{\"assetId\":" + assetId + ",\"title\":\"《重试资料》\",\"locator\":\"第2页\",\"license\":null}]");
        String token = newUserToken("13811100002", "乙", "EXPLORER");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset("这不是JSON{{{", StubLlmGateway.validOutput(assetId));
        long runId = submitRun(token, versionId, ids[0], ids[1], "再讲一遍？", "DEEP");

        assertThat((String) JsonPath.read(awaitTerminal(token, runId).getBody(), "$.data.status")).isEqualTo("DONE");
        assertThat(StubLlmGateway.CALLS.get()).isEqualTo(2);
    }

    @Test
    void bothBadJsonFailed() {
        long versionId = insertPublishedCard("坏输出卡", null);
        String token = newUserToken("13811100003", "丙", "EXPLORER");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset("bad{{{", "still-bad<<<");
        long runId = submitRun(token, versionId, ids[0], ids[1], "为什么？", "CHILD");

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.error")).isNotBlank();
        assertThat(StubLlmGateway.CALLS.get()).isEqualTo(2);
    }

    @Test
    void unauthenticatedRejected() {
        long versionId = insertPublishedCard("匿名卡", null);
        ResponseEntity<String> res = http.postForEntity("/api/agent/runs",
                json("{\"cardVersionId\":" + versionId + ",\"question\":\"?\",\"level\":\"SIMPLE\"}", null), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void foreignSessionRejected() {
        long versionId = insertPublishedCard("越权卡", null);
        String ownerToken = newUserToken("13811100004", "丁", "EXPLORER");
        long[] ids = newSessionWithNode(ownerToken, versionId);

        String strangerToken = newUserToken("13811100005", "戊", "EXPLORER");
        ResponseEntity<String> res = http.postForEntity("/api/agent/runs",
                json("{\"cardVersionId\":" + versionId + ",\"sessionId\":" + ids[0]
                        + ",\"question\":\"?\",\"level\":\"SIMPLE\"}", strangerToken), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(403);
    }

    @Test
    void nodeIdWithoutSessionRejected() {
        // review P5-18 回归：nodeId 不带 sessionId 的 POST 会绕过属主校验块，
        // 把 run 附着到任意人节点（跨租户污染 + 存在性泄露）→ 必须同步 400 且不产生 run
        long versionId = insertPublishedCard("附着卡", null);
        String victimToken = newUserToken("13811100007", "庚", "EXPLORER");
        long[] victimIds = newSessionWithNode(victimToken, versionId);

        String attackerToken = newUserToken("13811100008", "辛", "EXPLORER");
        StubLlmGateway.reset(StubLlmGateway.validOutput(0));
        ResponseEntity<String> res = http.postForEntity("/api/agent/runs",
                json("{\"cardVersionId\":" + versionId + ",\"nodeId\":" + victimIds[1]
                        + ",\"question\":\"?\",\"level\":\"SIMPLE\"}", attackerToken), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(400);

        // 受害者节点的 run 列表不含该提交（恶意请求未创建任何 run）
        ResponseEntity<String> list = http.exchange("/api/agent/runs?nodeId=" + victimIds[1],
                HttpMethod.GET, bearer(victimToken), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        List<Integer> runIds = JsonPath.read(list.getBody(), "$.data[*].runId");
        assertThat(runIds).isEmpty();
    }

    @Test
    void retrievalEmptyStillWorks() {
        long versionId = insertPublishedCard("无资料卡", null); // sources 为空 → 检索空 map
        String token = newUserToken("13811100006", "己", "EXPLORER");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset("{\"summary\":\"无资料讲解\",\"sections\":[{\"body\":\"谨慎作答\",\"claimType\":\"GEN\",\"citations\":[]}],\"openQuestions\":[],\"evidenceGaps\":[\"缺少史料\"]}");
        long runId = submitRun(token, versionId, ids[0], ids[1], "没有资料也能答吗？", "SIMPLE");

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.output.summary")).isEqualTo("无资料讲解");

        // 简版节点 run 列表（Task 22 消费）
        ResponseEntity<String> list = http.exchange("/api/agent/runs?nodeId=" + ids[1],
                HttpMethod.GET, bearer(token), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        List<Integer> runIds = JsonPath.read(list.getBody(), "$.data[*].runId");
        assertThat(runIds).contains((int) runId);
    }

    @Test
    void outOfBoundCitationStrippedKeepsFact() {
        // Task 19 引用校验：混合引用 [真实资料, 越界 99999] → 剥离越界、保留真实 → 仍 FACT；
        // 剥离留痕 run.error（不置 FAILED），citation 落表只有真实资料
        long assetId = insertAsset("《混合引用资料》", "书院建于唐代的书证摘录。", null);
        long versionId = insertPublishedCard("混合引用卡",
                "[{\"assetId\":" + assetId + ",\"title\":\"《混合引用资料》\",\"locator\":\"第1页\",\"license\":null}]");
        String token = newUserToken("13811100011", "子", "EXPLORER");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset(StubLlmGateway.factOutput(assetId, 99999L));
        long runId = submitRun(token, versionId, ids[0], ids[1], "书院建于何时？", "SIMPLE");

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("DONE");
        // artifact 中该段 citations 不含 99999，且还有有效引用 → 保持 FACT
        List<Number> citations = JsonPath.read(res.getBody(), "$.data.artifact.output.sections[0].citations[*]");
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).longValue()).isEqualTo(assetId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.output.sections[0].claimType")).isEqualTo("FACT");
        // 旁路留痕
        assertThat((String) JsonPath.read(res.getBody(), "$.data.error")).contains("剥离");
        // citation 落表：只落真实资料，越界 id 不落
        Integer realRows = jdbc.queryForObject(
                "select count(*) from citation where object_type='agent_run' and object_id=? and asset_id=?",
                Integer.class, runId, assetId);
        Integer phantomRows = jdbc.queryForObject(
                "select count(*) from citation where object_type='agent_run' and object_id=? and asset_id=99999",
                Integer.class, runId);
        assertThat(realRows).isEqualTo(1);
        assertThat(phantomRows).isZero();
    }

    @Test
    void allOutOfBoundsFactDowngraded() {
        // Task 19 降级：FACT 段引用全部越界（99999）→ 剥离后无有效引用 → 降级 SYNTHESIS，citations 空
        long assetId = insertAsset("《全越界资料》", "真实存在但未被引用的资料。", null);
        long versionId = insertPublishedCard("全越界降级卡",
                "[{\"assetId\":" + assetId + ",\"title\":\"《全越界资料》\",\"locator\":\"第2页\",\"license\":null}]");
        String token = newUserToken("13811100012", "丑", "EXPLORER");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset(StubLlmGateway.factOutput(99999L));
        long runId = submitRun(token, versionId, ids[0], ids[1], "谁建的书院？", "DEEP");

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("DONE");
        // 降级 SYNTHESIS（段落档位自表达），citations 空数组
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.output.sections[0].claimType")).isEqualTo("SYNTHESIS");
        List<Number> citations = JsonPath.read(res.getBody(), "$.data.artifact.output.sections[0].citations[*]");
        assertThat(citations).isEmpty();
        assertThat((String) JsonPath.read(res.getBody(), "$.data.error")).contains("剥离");
        // 越界 id 不落 citation 表
        Integer rows = jdbc.queryForObject(
                "select count(*) from citation where object_type='agent_run' and object_id=?",
                Integer.class, runId);
        assertThat(rows).isZero();
    }

    @Test
    void nullSectionToleratedInPipeline() {
        // Task 19 回归：LLM 输出 sections 含 null 段 → 校验器原位保留不 NPE，run 仍 DONE；
        // 展示层（落 artifact 前）过滤 null：artifact.sections 不含 null
        long versionId = insertPublishedCard("空段卡", null);
        String token = newUserToken("13811100013", "寅", "EXPLORER");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset("{\"summary\":\"空段讲解\",\"sections\":[null,"
                + "{\"body\":\"谨慎作答\",\"claimType\":\"GEN\",\"citations\":[]}"
                + "],\"openQuestions\":[],\"evidenceGaps\":[]}");
        long runId = submitRun(token, versionId, ids[0], ids[1], "空段怎么算？", "SIMPLE");

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("DONE");
        // artifact.sections 过滤掉 null 后只剩 1 段（GEN）
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.output.sections.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.output.sections[0].claimType")).isEqualTo("GEN");
        // 无越界引用 → 无旁路留痕（error 字段 non_null 序列化下整个省略）
        assertThat(res.getBody()).doesNotContain("\"error\"");
    }
}
