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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「帮我比较」输出通道端到端（FR-S06 一期 / Task 23）：
 * POST /api/agent/runs 带 serviceType=COMPARE → ExplainService 比较分支（提示词切换为
 * compare-prompts 的结构化对比输出）→ 绑定 CompareOutput + 落库前结构校验（cells 行数=维度数、
 * 行宽=对象数；非法重试 1 次仍非法 → FAILED）→ artifact(type=COMPARE_CARD,
 * content_json={type,data,sources,disclaimer,audit},citations=assetId 过 CitationSanitizer 同规则）
 * → agent_run DONE。GET run 响应带 serviceType（前端 DONE 分流用），serviceType 落 input_json。
 * 网关与流水线基建同 {@link ExplainPipelineIT}（StubLlmGateway + ke_test 清库 + Redis db15 flush）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "explain-test"})
@ItDb
@ExtendWith(RedisFlush.class)
class CompareOutputIT {

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

    // ---------- 数据准备（与 ExplainPipelineIT 同构，自包含） ----------

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

    private long submitRun(String token, long cardVersionId, long sessionId, long nodeId,
                           String question, String serviceType) {
        String body = "{\"cardVersionId\":" + cardVersionId
                + ",\"sessionId\":" + sessionId
                + ",\"nodeId\":" + nodeId
                + ",\"question\":\"" + question + "\",\"level\":\"SIMPLE\""
                + (serviceType == null ? "" : ",\"serviceType\":\"" + serviceType + "\"") + "}";
        ResponseEntity<String> res = http.postForEntity("/api/agent/runs", json(body, token), String.class);
        assertThat(res.getStatusCode().value()).as("submit body=%s", res.getBody()).isEqualTo(202);
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

    /** 合法比较输出 JSON：2 对象 × 2 维度 → cells 2 行 × 2 列；citations 混入越界 99999 供校验场景 */
    private static String validCompareOutput(long assetId) {
        return "{\"objects\":[\"岳麓书院\",\"白鹿洞书院\"],\"dimensions\":[\"创办时间\",\"所在地\"],"
                + "\"cells\":[[\"976 年（北宋）\",\"940 年（南唐）\"],[\"湖南长沙\",\"江西九江\"]],"
                + "\"citations\":[" + assetId + ",99999]}";
    }

    /** 结构非法：cells 2 行 ≠ 维度数 1（落库前结构校验必拦） */
    private static final String INVALID_CELLS_COMPARE_OUTPUT =
            "{\"objects\":[\"甲\",\"乙\"],\"dimensions\":[\"年代\"],"
                    + "\"cells\":[[\"976 年\",\"1161 年\"],[\"宋\",\"清\"]],\"citations\":[]}";

    // ---------- 用例 ----------

    @Test
    void compareRunProducesCompareCardArtifact() {
        long assetId = insertAsset("《书院比较资料》", "岳麓书院创建于北宋开宝九年，白鹿洞书院肇于南唐升元年间，" + "史".repeat(120));
        long versionId = insertPublishedCard("书院对比卡",
                "[{\"assetId\":" + assetId + ",\"title\":\"《书院比较资料》\",\"locator\":\"第1页\",\"license\":null}]");
        String token = newUserToken("13811200001", "比一比");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset(validCompareOutput(assetId));
        long runId = submitRun(token, versionId, ids[0], ids[1], "比较:岳麓书院与白鹿洞书院", "COMPARE");

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("DONE");
        // GET run 响应带 serviceType（前端 DONE 分流依据）
        assertThat((String) JsonPath.read(res.getBody(), "$.data.serviceType")).isEqualTo("COMPARE");

        // artifact.content_json = {type:'COMPARE_CARD', data:{objects,dimensions,cells,citations}, sources, disclaimer, audit}
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.type")).isEqualTo("COMPARE_CARD");
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.data.objects.length()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.data.dimensions.length()")).isEqualTo(2);
        // cells 形状：行数=维度数(2)、行宽=对象数(2)
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.data.cells.length()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.data.cells[0].length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.data.cells[1][0]")).isEqualTo("湖南长沙");
        // citations=assetId 过 sanitize：越界 99999 被剥离，只剩真实资料
        assertThat(((Number) JsonPath.read(res.getBody(), "$.data.artifact.data.citations[0]")).longValue())
                .isEqualTo(assetId);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.data.citations.length()")).isEqualTo(1);
        // 出处快照 + 生成标识 + 审计旁注（复用出处清单/脚注渲染）
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.sources['" + assetId + "']")).contains("书院");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.disclaimer")).isNotBlank();
        assertThat((Integer) JsonPath.read(res.getBody(), "$.data.artifact.audit.stripped")).isEqualTo(1);

        // 落库：agent_run.service_type / input_json.serviceType / artifact.type 列 / citation 行（只落真实资料）
        assertThat(jdbc.queryForObject("select service_type from agent_run where id=?", String.class, runId))
                .isEqualTo("COMPARE");
        String inputJson = jdbc.queryForObject("select input_json::text from agent_run where id=?", String.class, runId);
        assertThat((String) JsonPath.read(inputJson, "$.serviceType")).isEqualTo("COMPARE");
        assertThat(jdbc.queryForObject(
                "select type from artifact where session_id=? and type='COMPARE_CARD'", String.class, ids[0]))
                .isEqualTo("COMPARE_CARD");
        Integer citationRows = jdbc.queryForObject(
                "select count(*) from citation where object_type='agent_run' and object_id=? and asset_id=?",
                Integer.class, runId, assetId);
        assertThat(citationRows).isEqualTo(1);
        Integer phantomRows = jdbc.queryForObject(
                "select count(*) from citation where object_type='agent_run' and object_id=? and asset_id=99999",
                Integer.class, runId);
        assertThat(phantomRows).isZero();
    }

    @Test
    void invalidCellsShapeRetriedOnceThenFailed() {
        long versionId = insertPublishedCard("比较结构卡", null);
        String token = newUserToken("13811200002", "比二比");
        long[] ids = newSessionWithNode(token, versionId);

        StubLlmGateway.reset(INVALID_CELLS_COMPARE_OUTPUT);
        long runId = submitRun(token, versionId, ids[0], ids[1], "比较:甲与乙", "COMPARE");

        ResponseEntity<String> res = awaitTerminal(token, runId);
        // 结构非法与 JSON 绑定失败同路：重试 1 次（共 2 次调用），仍非法 → FAILED
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.error")).contains("结构");
        assertThat(StubLlmGateway.CALLS.get()).isEqualTo(2);
        // 失败 run 不落 artifact
        Integer artifacts = jdbc.queryForObject(
                "select count(*) from artifact where session_id=?", Integer.class, ids[0]);
        assertThat(artifacts).isZero();
    }

    @Test
    void illegalServiceTypeRejected() {
        long versionId = insertPublishedCard("白名单卡", null);
        String token = newUserToken("13811200003", "比三比");
        long[] ids = newSessionWithNode(token, versionId);

        ResponseEntity<String> res = http.postForEntity("/api/agent/runs",
                json("{\"cardVersionId\":" + versionId + ",\"sessionId\":" + ids[0]
                        + ",\"nodeId\":" + ids[1] + ",\"question\":\"?\",\"level\":\"SIMPLE\""
                        + ",\"serviceType\":\"ORGANIZE\"}", token), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((String) JsonPath.read(res.getBody(), "$.message")).contains("EXPLAIN/COMPARE");
    }

    @Test
    void explainDefaultUnaffected() {
        // 不带 serviceType 的既有提交语义不变：默认 EXPLAIN，artifact 仍为讲解形状
        long versionId = insertPublishedCard("默认讲解卡", null);
        String token = newUserToken("13811200004", "讲四讲");
        long[] ids = newSessionWithNode(token, versionId);

        long runId = submitRun(token, versionId, ids[0], ids[1], "讲清楚:岳麓书院", null);

        ResponseEntity<String> res = awaitTerminal(token, runId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.serviceType")).isEqualTo("EXPLAIN");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.artifact.output.summary")).isEqualTo("讲解摘要");
    }
}
