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
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成果整理服务 + 未决疑问清单端到端（FR-S03/E07/E09 / Task 24）：
 * POST /api/agent/summarize {sessionId, nodeIds[]}（202 QUEUED）→ 异步执行（逐选中根 selectSubtree
 * 装材料：子树内卡题+提问文本+讲解 artifact 的 sections 文本与遗留 openQuestions，资产全集=各讲解
 * artifact.sources 并集 → GENERATOR 提示词 → 绑定 SummaryOutput → 敏感词过滤 + CitationSanitizer
 * （allowed=材料资产全集，FACT 空引降级同则）→ artifact(type=REPORT, content_json={type,keyFindings,
 * openQuestions,branchView,sources,disclaimer,audit}，branchView 从 pathNode 树结构生成非 LLM）→ DONE。
 * GET /api/sessions/{id}/open-questions 聚合本会话全部 EXPLAIN DONE run 的 openQuestions（扁平、逆时序）；
 * 断点续探 ResumeSession.openQuestionCount 用同一聚合的真实条数（P4-16 恒 0 的遗留兑现）。
 * 网关与流水线基建同 {@link ExplainPipelineIT}（StubLlmGateway + ke_test 清库 + Redis db15 flush）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "explain-test"})
@ItDb
@ExtendWith(RedisFlush.class)
class SummarizeIT {

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

    // ---------- 数据准备（与 ExplainPipelineIT / CompareOutputIT 同构，自包含） ----------

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

    private long newSession(String token) {
        ResponseEntity<String> created = http.postForEntity("/api/sessions",
                json("{\"theme\":\"academy\",\"goal\":\"弄懂书院制度\"}", token), String.class);
        assertThat(created.getStatusCode().value()).as("session body=%s", created.getBody()).isEqualTo(201);
        return ((Number) JsonPath.read(created.getBody(), "$.data.sessionId")).longValue();
    }

    private long addNode(String token, long sessionId, Long cardVersionId, String question, Long parentNodeId) {
        String body = "{\"questionText\":\"" + question + "\""
                + (cardVersionId == null ? "" : ",\"cardVersionId\":" + cardVersionId)
                + (parentNodeId == null ? "" : ",\"parentNodeId\":" + parentNodeId) + "}";
        ResponseEntity<String> node = http.postForEntity("/api/sessions/" + sessionId + "/nodes",
                json(body, token), String.class);
        assertThat(node.getStatusCode().value()).as("node body=%s", node.getBody()).isEqualTo(200);
        return ((Number) JsonPath.read(node.getBody(), "$.data.nodeId")).longValue();
    }

    private long submitExplain(String token, long cardVersionId, long sessionId, long nodeId, String question) {
        String body = "{\"cardVersionId\":" + cardVersionId + ",\"sessionId\":" + sessionId
                + ",\"nodeId\":" + nodeId + ",\"question\":\"" + question + "\",\"level\":\"SIMPLE\"}";
        ResponseEntity<String> res = http.postForEntity("/api/agent/runs", json(body, token), String.class);
        assertThat(res.getStatusCode().value()).as("explain body=%s", res.getBody()).isEqualTo(202);
        return ((Number) JsonPath.read(res.getBody(), "$.data.runId")).longValue();
    }

    private long submitSummarize(String token, long sessionId, List<Long> nodeIds) {
        String ids = nodeIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        String body = "{\"sessionId\":" + sessionId + ",\"nodeIds\":[" + ids + "]}";
        ResponseEntity<String> res = http.postForEntity("/api/agent/summarize", json(body, token), String.class);
        assertThat(res.getStatusCode().value()).as("summarize body=%s", res.getBody()).isEqualTo(202);
        return ((Number) JsonPath.read(res.getBody(), "$.data.runId")).longValue();
    }

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

    /** 讲解输出 JSON（带指定 openQuestion，供疑问清单聚合场景） */
    private static String explainOutput(long assetId, String openQuestion) {
        return "{\"summary\":\"讲解摘要\",\"sections\":[{\"body\":\"依据资料的正文\",\"claimType\":\"FACT\",\"citations\":["
                + assetId + "]}],\"openQuestions\":[\"" + openQuestion + "\"],\"evidenceGaps\":[]}";
    }

    /** 整理输出 JSON：citations 混入越界 99999 供校验场景；openQuestions 含重复项供服务端去重场景 */
    private static String summarizeOutput(long assetId, String openQuestion) {
        return "{\"keyFindings\":[{\"body\":\"关键发现:书院制度与经费保障互为表里\",\"claimType\":\"FACT\",\"citations\":["
                + assetId + ",99999]}],\"openQuestions\":[\"" + openQuestion + "\",\"" + openQuestion + "\"]}";
    }

    // ---------- 用例 ----------

    @Test
    void summarizeTwoBranches() {
        // 会话 2 支：支 1 = node1(cardA) → node1b(cardB)；支 2 = node2(cardB)。先跑 2 个 EXPLAIN DONE 产 openQuestions
        long assetA = insertAsset("《书院史料甲》", "岳麓书院创建于北宋开宝九年，朱熹曾在此讲学。");
        long assetB = insertAsset("《书院史料乙》", "书院经费主要来自学田租赋与官绅捐输。");
        long cardA = insertPublishedCard("岳麓书院",
                "[{\"assetId\":" + assetA + ",\"title\":\"《书院史料甲》\",\"locator\":\"第1页\",\"license\":null}]");
        long cardB = insertPublishedCard("书院经费",
                "[{\"assetId\":" + assetB + ",\"title\":\"《书院史料乙》\",\"locator\":\"第1页\",\"license\":null}]");
        String token = newUserToken("13811400001", "总一总");
        long sessionId = newSession(token);
        long node1 = addNode(token, sessionId, cardA, "岳麓书院为何建在山中？", null);
        addNode(token, sessionId, cardB, "书院经费从哪里来？", node1);
        long node2 = addNode(token, sessionId, cardB, "书院山长如何产生？", null);

        StubLlmGateway.reset(explainOutput(assetA, "问题甲"), explainOutput(assetB, "问题乙"),
                summarizeOutput(assetA, "问题甲"));
        awaitTerminal(token, submitExplain(token, cardA, sessionId, node1, "讲讲岳麓书院的历史"));
        awaitTerminal(token, submitExplain(token, cardB, sessionId, node2, "讲讲书院的经费"));
        long runId = submitSummarize(token, sessionId, List.of(node1, node2));

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.serviceType")).isEqualTo("SUMMARIZE");

        // REPORT artifact：branchView 2 支（从 pathNode 树生成，非 LLM）——支 1 含子节点、支 2 单节点
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.type")).isEqualTo("REPORT");
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.branchView.length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.branchView[0].rootNodeTitle")).isEqualTo("岳麓书院");
        List<String> branch1Nodes = JsonPath.read(res.getBody(), "$.data.artifact.branchView[0].nodeTitles[*]");
        assertThat(branch1Nodes).containsExactly("岳麓书院", "书院经费");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.branchView[1].rootNodeTitle")).isEqualTo("书院经费");
        List<String> branch2Nodes = JsonPath.read(res.getBody(), "$.data.artifact.branchView[1].nodeTitles[*]");
        assertThat(branch2Nodes).containsExactly("书院经费");

        // openQuestions 去重合并：Stub 返回重复 2 条 → artifact 只剩 1 条
        List<String> questions = JsonPath.read(res.getBody(), "$.data.artifact.openQuestions[*]");
        assertThat(questions).containsExactly("问题甲");
        // keyFindings citations 越界剥离：[assetA, 99999] → [assetA]，仍 FACT
        assertThat(((Number) JsonPath.read(res.getBody(), "$.data.artifact.keyFindings[0].citations[0]")).longValue())
                .isEqualTo(assetA);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.keyFindings[0].citations.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.keyFindings[0].claimType")).isEqualTo("FACT");
        // 审计旁注 + 旁路留痕 + 生成标识
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.audit.stripped")).isEqualTo(1);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.error")).contains("剥离");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.disclaimer")).isNotBlank();
        // 材料资产快照可回溯（两个讲解的 sources 并集）
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.sources['" + assetA + "']")).contains("岳麓书院");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.sources['" + assetB + "']")).contains("书院经费");

        // 落库：service_type / input_json.nodeIds / artifact.type 列 / citation 行（只落真实资料）
        assertThat(jdbc.queryForObject("select service_type from agent_run where id=?", String.class, runId))
                .isEqualTo("SUMMARIZE");
        String inputJson = jdbc.queryForObject("select input_json::text from agent_run where id=?", String.class, runId);
        List<Number> nodeIds = JsonPath.read(inputJson, "$.nodeIds[*]");
        assertThat(nodeIds).extracting(Number::longValue).containsExactly(node1, node2);
        assertThat(jdbc.queryForObject(
                "select type from artifact where session_id=? and type='REPORT'", String.class, sessionId))
                .isEqualTo("REPORT");
        Integer realRows = jdbc.queryForObject(
                "select count(*) from citation where object_type='agent_run' and object_id=? and asset_id=?",
                Integer.class, runId, assetA);
        Integer phantomRows = jdbc.queryForObject(
                "select count(*) from citation where object_type='agent_run' and object_id=? and asset_id=99999",
                Integer.class, runId);
        assertThat(realRows).isEqualTo(1);
        assertThat(phantomRows).isZero();
    }

    @Test
    void summarizeValidatesOwnership() {
        long cardVersionId = insertPublishedCard("越权整理卡", null);
        String ownerToken = newUserToken("13811400002", "总二总");
        long ownerSession = newSession(ownerToken);
        long ownerNode = addNode(ownerToken, ownerSession, cardVersionId, "我的节点？", null);

        String strangerToken = newUserToken("13811400003", "总三总");
        long strangerSession = newSession(strangerToken);
        long strangerNode = addNode(strangerToken, strangerSession, cardVersionId, "他人节点？", null);

        // 他人会话 → 403（不区分泄露）
        ResponseEntity<String> foreign = http.postForEntity("/api/agent/summarize",
                json("{\"sessionId\":" + ownerSession + ",\"nodeIds\":[" + ownerNode + "]}", strangerToken), String.class);
        assertThat(foreign.getStatusCode().value()).as("foreign body=%s", foreign.getBody()).isEqualTo(403);

        // nodeIds 空 → 400
        ResponseEntity<String> empty = http.postForEntity("/api/agent/summarize",
                json("{\"sessionId\":" + ownerSession + ",\"nodeIds\":[]}", ownerToken), String.class);
        assertThat(empty.getStatusCode().value()).as("empty body=%s", empty.getBody()).isEqualTo(400);
        assertThat((String) JsonPath.read(empty.getBody(), "$.message")).contains("nodeIds");

        // 节点不属于该会话 → 400
        ResponseEntity<String> alien = http.postForEntity("/api/agent/summarize",
                json("{\"sessionId\":" + ownerSession + ",\"nodeIds\":[" + strangerNode + "]}", ownerToken), String.class);
        assertThat(alien.getStatusCode().value()).as("alien body=%s", alien.getBody()).isEqualTo(400);
        assertThat((String) JsonPath.read(alien.getBody(), "$.message")).contains("节点不属于该会话");

        // 无效请求不产生任何 run
        Integer runs = jdbc.queryForObject(
                "select count(*) from agent_run where session_id=?", Integer.class, ownerSession);
        assertThat(runs).isZero();
    }

    @Test
    void summarizeOversizeNodeIdsRejected() {
        long cardVersionId = insertPublishedCard("超限整理卡", null);
        String token = newUserToken("13811400004", "总四总");
        long sessionId = newSession(token);
        long nodeId = addNode(token, sessionId, cardVersionId, "唯一节点？", null);

        List<Long> nodeIds = LongStream.rangeClosed(0, 50).map(i -> nodeId).boxed().toList();
        String ids = nodeIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        ResponseEntity<String> res = http.postForEntity("/api/agent/summarize",
                json("{\"sessionId\":" + sessionId + ",\"nodeIds\":[" + ids + "]}", token), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((String) JsonPath.read(res.getBody(), "$.message")).contains("50");
    }

    @Test
    void openQuestionsAggregated() {
        // 两条讲解各产一条 openQuestion → GET open-questions 扁平逆时序；断点续探未决计数=真实条数（P4-16 兑现）
        long assetId = insertAsset("《疑问史料》", "书院讲会制度仅存部分记载，细节尚待考。");
        long cardVersionId = insertPublishedCard("书院讲会",
                "[{\"assetId\":" + assetId + ",\"title\":\"《疑问史料》\",\"locator\":\"第1页\",\"license\":null}]");
        String token = newUserToken("13811400005", "问一问");
        long sessionId = newSession(token);
        long node1 = addNode(token, sessionId, cardVersionId, "讲会由谁主持？", null);
        long node2 = addNode(token, sessionId, cardVersionId, "讲会多久一开？", null);

        StubLlmGateway.reset(explainOutput(assetId, "疑问一"), explainOutput(assetId, "疑问二"));
        long run1 = submitExplain(token, cardVersionId, sessionId, node1, "讲讲讲会主持");
        awaitTerminal(token, run1);
        long run2 = submitExplain(token, cardVersionId, sessionId, node2, "讲讲讲会频次");
        awaitTerminal(token, run2);

        ResponseEntity<String> res = http.exchange("/api/sessions/" + sessionId + "/open-questions",
                HttpMethod.GET, bearer(token), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        List<String> questions = JsonPath.read(res.getBody(), "$.data.questions[*].question");
        // 逆时序：后完成的讲解排前；不去重（保留各讲解原貌）
        assertThat(questions).containsExactly("疑问二", "疑问一");
        assertThat(((Number) JsonPath.read(res.getBody(), "$.data.questions[0].runId")).longValue()).isEqualTo(run2);
        assertThat(((Number) JsonPath.read(res.getBody(), "$.data.questions[1].runId")).longValue()).isEqualTo(run1);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.questions[0].collectedAt")).isNotBlank();

        // 断点续探 openQuestionCount=同一聚合的条数（>0，恒 0 遗留兑现）
        ResponseEntity<String> latest = http.exchange("/api/sessions/latest", HttpMethod.GET, bearer(token), String.class);
        assertThat(latest.getStatusCode().value()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(latest.getBody(), "$.data.openQuestionCount")).longValue()).isEqualTo(2);

        // 他人会话 → 403
        String strangerToken = newUserToken("13811400006", "问二问");
        ResponseEntity<String> foreign = http.exchange("/api/sessions/" + sessionId + "/open-questions",
                HttpMethod.GET, bearer(strangerToken), String.class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(403);
    }
}
