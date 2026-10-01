package com.ke.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.ke.agent.StubLlmGateway;
import com.ke.support.ItDb;
import com.ke.support.RedisFlush;
import org.awaitility.Awaitility;
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
 * 全旅程端到端验收（01 §4 七步串联，验收 A1–A5，MVP 核心验收测试）。
 * 单条测试方法 {@link #fullJourneyA1toA5()} 按七步注释分段，每段断言对应验收要点，全程走 HTTP API
 * （不 mock 域逻辑）：
 * <ol>
 *   <li>A1① 编辑账号发布图文卡+公共入口「为什么建在这里」（AGENT_SERVICE/EXPLAIN，经审核 ACTIVE）→
 *       读者登录 → 挂卡节点 → 点入口 submitRun(EXPLAIN) → DONE → artifact.output.summary 非空且
 *       sections[0].citations 含挂接资产（有出处，citation 落表）；</li>
 *   <li>A1② 在讲解卡追问比较 → addNode(城南书院) + submitRun(COMPARE, parentRunId) → DONE →
 *       COMPARE_CARD artifact、cells 形状合法（行数=维度数、行宽=对象数）；</li>
 *   <li>A1③ 时间线入口（LINK_CARD→时间线卡）→ addNode(entryId, 时间线卡) → 该节点 isNewKnowledge=true
 *       （第 3 个新知识对象，树中 isNewKnowledge 计数=3，验收 A1 通过）；</li>
 *   <li>A2 GET 树回看 → 回到节点1 挂新子（建筑空间卡）→ 原链路节点逐字段不变、新节点挂节点1 下、
 *       树出现分支；</li>
 *   <li>A3 POST /api/entries/nl-draft → 草稿字段齐（violations 空）→ 草稿配置原样 PRIVATE 保存 →
 *       卡入口列表可见（mine）→ POST /{id}/test 试运行 DONE；</li>
 *   <li>A4 POST /api/agent/summarize 勾选两支 → DONE → REPORT artifact（branchView 2 支）→
 *       POST /api/shares → 匿名 GET /s/{token} → 快照标题/节点数/入口与所选节点一致；</li>
 *   <li>A5 第二用户（新注册）POST /s/{token}/continue → 新 sessionId、origin_share_id 非空、
 *       副本树节点数=快照节点数；原会话零写入（updated_at/节点数前后相等）。</li>
 * </ol>
 * 网关用 StubLlmGateway（profile explain-test，@Primary 压过 test 的 MockLlmGateway）：六个应答按
 * 全旅程发生序一次性入队（讲解/比较/草稿分类/草稿抽取/入口试运行/成果整理），CALLS 逐步断言钉住
 * 队列对齐；直连 WSL ke_test（@ItDb 类前清库重建），RedisFlush 清 db15（配额键跨类残留），
 * Awaitility 等异步终态。运行节奏与其他 explain-test IT 一致：Stub 每类独占、无并发测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "explain-test"})
@ItDb
@ExtendWith(RedisFlush.class)
class JourneyIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    private static final String READER_PHONE = "13895500003";
    private static final String RECEIVER_PHONE = "13895500004";

    private static final String TEXT_CONTENT =
            "{\"summary\":\"卡片摘要\",\"sections\":[{\"h\":\"缘起\",\"body\":\"书院正文。\"}],\"related\":[]}";
    private static final String TIMELINE_CONTENT =
            "{\"events\":[{\"year\":\"976 年\",\"title\":\"朱洞创建书院\",\"body\":\"岳麓书院肇建。\"}]}";

    @Test
    void fullJourneyA1toA5() throws Exception {
        // ---------- 造数：编辑账号经工作台 API 发布四张卡（各挂 1 资产），两个公共入口经审核生效 ----------
        String creator = newUserToken("13895500001", "旅程创作者", "CREATOR");
        String editor = newUserToken("13895500002", "旅程编辑", "EDITOR");
        long assetA = insertAsset("《岳麓书院志》", "岳麓书院创建于北宋开宝九年，朱熹张栻曾在此讲学，" + "史".repeat(120));
        long assetB = insertAsset("《城南书院志》", "城南书院为张栻所创，与岳麓书院隔江相望，" + "史".repeat(120));
        long assetC = insertAsset("《书院人物志》", "朱熹、张栻、王夫之皆与岳麓书院有深厚渊源，" + "史".repeat(120));
        long assetD = insertAsset("《书院建筑志》", "书院沿山而建，讲堂居中，御书楼藏于后，" + "史".repeat(120));
        long[] yuelu = publishCard(creator, editor, "岳麓书院", "TEXT", TEXT_CONTENT,
                "[{\"assetId\":" + assetA + ",\"title\":\"《岳麓书院志》\",\"locator\":\"第1页\",\"license\":null}]");
        long[] chengnan = publishCard(creator, editor, "城南书院", "TEXT", TEXT_CONTENT,
                "[{\"assetId\":" + assetB + ",\"title\":\"《城南书院志》\",\"locator\":\"第1页\",\"license\":null}]");
        long[] timeline = publishCard(creator, editor, "书院人物时间线", "TIMELINE", TIMELINE_CONTENT,
                "[{\"assetId\":" + assetC + ",\"title\":\"《书院人物志》\",\"locator\":\"第2页\",\"license\":null}]");
        long[] architecture = publishCard(creator, editor, "书院建筑空间", "TEXT", TEXT_CONTENT,
                "[{\"assetId\":" + assetD + ",\"title\":\"《书院建筑志》\",\"locator\":\"第3页\",\"license\":null}]");

        long explainEntry = createApprovedEntry(creator, editor, yuelu[0], "{\"name\":\"为什么建在这里\","
                + "\"type\":\"AGENT_SERVICE\",\"goal\":\"为什么建在这里？\",\"serviceType\":\"EXPLAIN\","
                + "\"relationLabel\":\"深入了解\",\"assetScope\":[" + assetA + "],\"outputSpec\":\"摘要+分节正文\"}");
        long timelineEntry = createApprovedEntry(creator, editor, yuelu[0], "{\"name\":\"哪些人物与这里有关\","
                + "\"type\":\"LINK_CARD\",\"targetCardId\":" + timeline[0] + "}");

        // 六段网关应答按全旅程发生序入队：①讲解 ②比较 ③草稿分类 ④草稿抽取 ⑤入口试运行 ⑥成果整理
        String compareOutput = "{\"objects\":[\"岳麓书院\",\"城南书院\"],\"dimensions\":[\"创办时间\",\"创建者\"],"
                + "\"cells\":[[\"976 年（北宋）\",\"1161 年（南宋）\"],[\"朱洞\",\"张栻\"]],\"citations\":[" + assetB + "]}";
        String draftConfigJson = "{\"name\":\"家庭 60 分钟文化路线\",\"goal\":\"给第一次来的家庭设计 60 分钟文化探索路线\","
                + "\"serviceType\":\"EXPLAIN\",\"assetScope\":[" + assetA + "],\"outputSpec\":\"路线+要点清单\"}";
        String summarizeOutput = "{\"keyFindings\":[{\"body\":\"关键发现:书院沿山而建、讲堂居中，讲学制度影响千年。\","
                + "\"claimType\":\"FACT\",\"citations\":[" + assetA + "]}],"
                + "\"openQuestions\":[\"书院经费如何长期维系？\"]}";
        StubLlmGateway.reset(
                StubLlmGateway.validOutput(assetA),
                compareOutput,
                "EXPLAIN",
                draftConfigJson,
                StubLlmGateway.validOutput(assetA),
                summarizeOutput);

        // ---------- 步骤 1（A1①）：读者登录 → 打开图文卡 → 点入口得讲解卡（含出处） ----------
        String reader = newUserToken(READER_PHONE, "旅程读者", "EXPLORER");

        // 卡入口列表：两个 PUBLIC 入口可见（非本人 → mine=false）
        ResponseEntity<String> entriesRes = http.exchange("/api/cards/" + yuelu[0] + "/entries", HttpMethod.GET,
                bearer(reader), String.class);
        assertThat(entriesRes.getStatusCode().value()).as("entries body=%s", entriesRes.getBody()).isEqualTo(200);
        List<String> entryNames = JsonPath.read(entriesRes.getBody(), "$.data.defaultEntries[*].name");
        assertThat(entryNames).contains("为什么建在这里", "哪些人物与这里有关");
        assertThat((List<Boolean>) JsonPath.read(entriesRes.getBody(),
                "$.data.defaultEntries[?(@.name == '为什么建在这里')].mine")).containsExactly(false);

        long sessionId = createSession(reader, "academy", "探一探岳麓书院");
        long node1 = addNode(reader, sessionId, yuelu[1], null, null, "岳麓书院为何建在这里？");
        JsonNode tree1 = treeNodes(reader, sessionId);
        assertThat(nodeById(tree1, node1).path("isNewKnowledge").asBoolean())
                .as("第 1 个新知识对象：岳麓书院卡").isTrue();
        assertThat(nodeById(tree1, node1).path("cardTitle").asText()).isEqualTo("岳麓书院");

        long run1 = submitRun(reader, yuelu[1], sessionId, node1, null, null, "为什么建在这里？");
        String run1Body = awaitRun(reader, run1);
        assertThat(StubLlmGateway.CALLS.get()).as("队列对齐：仅讲解 1 次调用").isEqualTo(1);
        assertThat((String) JsonPath.read(run1Body, "$.data.serviceType")).isEqualTo("EXPLAIN");
        assertThat((String) JsonPath.read(run1Body, "$.data.artifact.output.summary")).isNotBlank();
        // 有出处：FACT 段引用挂接资产 + 出处快照 + citation 落表
        assertThat(((Number) JsonPath.read(run1Body, "$.data.artifact.output.sections[0].citations[0]")).longValue())
                .isEqualTo(assetA);
        assertThat((String) JsonPath.read(run1Body, "$.data.artifact.sources['" + assetA + "']")).contains("岳麓书院");
        assertThat(jdbc.queryForObject(
                "select count(*) from citation where object_type='agent_run' and object_id=? and asset_id=?",
                Integer.class, run1, assetA)).isEqualTo(1);

        // ---------- 步骤 2（A1②）：在讲解卡追问比较 → COMPARE_CARD ----------
        long node2 = addNode(reader, sessionId, chengnan[1], null, node1, "和城南书院什么关系？");
        long run2 = submitRun(reader, chengnan[1], sessionId, node2, run1, "COMPARE", "和城南书院什么关系？");
        String run2Body = awaitRun(reader, run2);
        assertThat(StubLlmGateway.CALLS.get()).as("队列对齐：讲解+比较").isEqualTo(2);
        assertThat((String) JsonPath.read(run2Body, "$.data.serviceType")).isEqualTo("COMPARE");
        assertThat((String) JsonPath.read(run2Body, "$.data.artifact.type")).isEqualTo("COMPARE_CARD");
        assertThat((Integer) JsonPath.read(run2Body, "$.data.artifact.data.objects.length()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(run2Body, "$.data.artifact.data.dimensions.length()")).isEqualTo(2);
        // cells 形状合法：行数=维度数(2)、行宽=对象数(2)
        assertThat((Integer) JsonPath.read(run2Body, "$.data.artifact.data.cells.length()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(run2Body, "$.data.artifact.data.cells[0].length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(run2Body, "$.data.artifact.data.cells[1][0]")).isEqualTo("朱洞");
        // 追问链：parentRunId 记录本次比较源自 run1
        assertThat((Integer) JsonPath.read(run2Body, "$.data.submitContext.parentRunId")).isEqualTo((int) run1);

        // ---------- 步骤 3（A1③）：点时间线入口 → 第 3 个新知识对象，验收 A1 通过 ----------
        long node3 = addNode(reader, sessionId, timeline[1], timelineEntry, node2, "哪些人物与这里有关？");
        JsonNode tree3 = treeNodes(reader, sessionId);
        assertThat(nodeById(tree3, node3).path("isNewKnowledge").asBoolean())
                .as("时间线卡=第 3 个新知识对象").isTrue();
        assertThat(nodeById(tree3, node3).path("entryId").asLong()).isEqualTo(timelineEntry);
        assertThat(newKnowledgeCount(tree3)).as("A1：一次会话 3 次有新信息的探索").isEqualTo(3);

        // ---------- 步骤 4（A2）：回节点1 挂新子 → 原链路不变、新分支存在 ----------
        JsonNode treeBefore = treeNodes(reader, sessionId);
        JsonNode chain1 = nodeById(treeBefore, node1);
        JsonNode chain2 = nodeById(treeBefore, node2);
        JsonNode chain3 = nodeById(treeBefore, node3);

        long node4 = addNode(reader, sessionId, architecture[1], null, node1, "换个方向：建筑空间");
        JsonNode tree4 = treeNodes(reader, sessionId);
        assertThat(nodeById(tree4, node4).path("parentNodeId").asLong()).as("新节点挂在节点1 下").isEqualTo(node1);
        assertThat(nodeById(tree4, node4).path("isNewKnowledge").asBoolean()).isTrue();
        // 原链路节点逐字段不变（含卡题/入口/新知标记/访问时间）
        assertThat(nodeById(tree4, node1)).as("原链路节点1 不变").isEqualTo(chain1);
        assertThat(nodeById(tree4, node2)).as("原链路节点2 不变").isEqualTo(chain2);
        assertThat(nodeById(tree4, node3)).as("原链路节点3 不变").isEqualTo(chain3);
        // 树出现分支：节点1 下挂两支（城南链 + 建筑支）
        long childrenOfNode1 = 0;
        for (JsonNode n : tree4) {
            if (n.path("parentNodeId").asLong(Long.MIN_VALUE) == node1) {
                childrenOfNode1++;
            }
        }
        assertThat(childrenOfNode1).as("节点1 分支出两支").isGreaterThanOrEqualTo(2);
        assertThat(newKnowledgeCount(tree4)).isEqualTo(4);

        // ---------- 步骤 5（A3）：一句话入口草稿 → PRIVATE 保存 → 入口列表可见 → 试运行 DONE ----------
        ResponseEntity<String> draftRes = http.exchange("/api/entries/nl-draft", HttpMethod.POST,
                json("{\"cardId\":" + yuelu[0] + ",\"text\":\"给第一次来的家庭设计 60 分钟文化探索路线\"}", reader),
                String.class);
        assertThat(draftRes.getStatusCode().value()).as("draft body=%s", draftRes.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(draftRes.getBody(), "$.data.intent")).isEqualTo("EXPLAIN");
        assertThat((int) JsonPath.read(draftRes.getBody(), "$.data.violations.length()")).isZero();
        JsonNode draftConfig = readData(draftRes.getBody()).path("config");
        assertThat(draftConfig.path("type").asText()).isEqualTo("AGENT_SERVICE");
        assertThat(draftConfig.path("serviceType").asText()).isEqualTo("EXPLAIN");
        assertThat(draftConfig.path("name").asText()).isNotBlank();
        assertThat(draftConfig.path("goal").asText()).isNotBlank();
        assertThat(draftConfig.path("outputSpec").asText()).isNotBlank();
        assertThat(draftConfig.path("assetScope").size()).isEqualTo(1);
        assertThat(((Number) draftConfig.path("assetScope").get(0).asLong()).longValue()).isEqualTo(assetA);
        assertThat(StubLlmGateway.CALLS.get()).as("草稿=分类+抽取两次调用").isEqualTo(4);

        // 草稿配置原样保存为个人入口（PRIVATE 即 ACTIVE）
        ResponseEntity<String> saved = http.postForEntity("/api/entries",
                json("{\"cardId\":" + yuelu[0] + ",\"config\":" + draftConfig.toString() + ",\"scope\":\"PRIVATE\"}",
                        reader), String.class);
        assertThat(saved.getStatusCode().value()).as("save entry body=%s", saved.getBody()).isEqualTo(201);
        long privateEntry = ((Number) JsonPath.read(saved.getBody(), "$.data.entryId")).longValue();
        assertThat((String) JsonPath.read(saved.getBody(), "$.data.scope")).isEqualTo("PRIVATE");
        assertThat((String) JsonPath.read(saved.getBody(), "$.data.status")).isEqualTo("ACTIVE");

        // 卡入口列表含该入口（mine=true）
        ResponseEntity<String> mineRes = http.exchange("/api/cards/" + yuelu[0] + "/entries", HttpMethod.GET,
                bearer(reader), String.class);
        assertThat(mineRes.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Boolean>) JsonPath.read(mineRes.getBody(),
                "$.data.defaultEntries[?(@.id == " + privateEntry + ")].mine")).containsExactly(true);

        // 试运行 → 202 {runId, testTotal=1} → 轮询 DONE（无会话 run，状态即试运行结果）
        ResponseEntity<String> testRes = http.postForEntity("/api/entries/" + privateEntry + "/test",
                bearer(reader), String.class);
        assertThat(testRes.getStatusCode().value()).as("entry test body=%s", testRes.getBody()).isEqualTo(202);
        long testRun = ((Number) JsonPath.read(testRes.getBody(), "$.data.runId")).longValue();
        assertThat((int) JsonPath.read(testRes.getBody(), "$.data.testTotal")).isEqualTo(1);
        awaitRun(reader, testRun);
        assertThat(StubLlmGateway.CALLS.get()).isEqualTo(5);

        // ---------- 步骤 6（A4）：整理发现（勾两支分支头）→ REPORT → 分享 → 匿名快照一致 ----------
        // 两支=城南链（node2 子树：node2→node3）与建筑支（node4）；node1 是两支的共同祖先不勾选
        ResponseEntity<String> sumRes = http.postForEntity("/api/agent/summarize",
                json("{\"sessionId\":" + sessionId + ",\"nodeIds\":[" + node2 + "," + node4 + "]}", reader),
                String.class);
        assertThat(sumRes.getStatusCode().value()).as("summarize body=%s", sumRes.getBody()).isEqualTo(202);
        long sumRun = ((Number) JsonPath.read(sumRes.getBody(), "$.data.runId")).longValue();
        String sumBody = awaitRun(reader, sumRun);
        assertThat(StubLlmGateway.CALLS.get()).isEqualTo(6);
        assertThat((String) JsonPath.read(sumBody, "$.data.artifact.type")).isEqualTo("REPORT");
        assertThat((Integer) JsonPath.read(sumBody, "$.data.artifact.branchView.length()")).isEqualTo(2);
        assertThat((List<String>) JsonPath.read(sumBody, "$.data.artifact.branchView[0].nodeTitles[*]"))
                .containsExactly("城南书院", "书院人物时间线");
        assertThat((List<String>) JsonPath.read(sumBody, "$.data.artifact.branchView[1].nodeTitles[*]"))
                .containsExactly("书院建筑空间");

        String shareToken = createShare(reader, sessionId,
                "[" + node1 + "," + node2 + "," + node3 + "," + node4 + "]");
        ResponseEntity<String> anonView = http.exchange("/s/" + shareToken, HttpMethod.GET, noAuth(), String.class);
        assertThat(anonView.getStatusCode().value()).as("anon view body=%s", anonView.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(anonView.getBody(), "$.data.title")).isEqualTo("湖湘书院全旅程");
        assertThat((String) JsonPath.read(anonView.getBody(), "$.data.summary")).isEqualTo("书院四问一报告");
        // 快照与所选节点一致：4 节点、标题=卡题（与 REPORT 分支视图同源）、树序
        assertThat((List<String>) JsonPath.read(anonView.getBody(), "$.data.snapshot.nodes[*].title"))
                .containsExactly("岳麓书院", "城南书院", "书院人物时间线", "书院建筑空间");
        assertThat((Boolean) JsonPath.read(anonView.getBody(), "$.data.snapshot.nodes[0].removed")).isFalse();
        // 来源/入口：卡上 2 个 PUBLIC 入口 + 分享者本人 PRIVATE 入口均可见
        assertThat((List<String>) JsonPath.read(anonView.getBody(), "$.data.snapshot.nodes[0].entries[*].name"))
                .containsExactly("为什么建在这里", "哪些人物与这里有关", "家庭 60 分钟文化路线");

        // ---------- 步骤 7（A5）：第二用户接续 → 副本=快照、原会话零写入 ----------
        String receiver = newUserToken(RECEIVER_PHONE, "旅程接续者", "EXPLORER");
        long receiverId = jdbc.queryForObject("select id from ke_user where phone=?", Long.class, RECEIVER_PHONE);

        // 零写入断言基线：原会话 updated_at + 节点数
        Object originUpdatedAtBefore = jdbc.queryForObject(
                "select updated_at from exploration_session where id=?", Object.class, sessionId);
        long originNodeCountBefore = jdbc.queryForObject(
                "select count(*) from path_node where session_id=?", Long.class, sessionId);

        ResponseEntity<String> cont = http.postForEntity("/s/" + shareToken + "/continue", bearer(receiver),
                String.class);
        assertThat(cont.getStatusCode().value()).as("continue body=%s", cont.getBody()).isEqualTo(200);
        long newSessionId = ((Number) JsonPath.read(cont.getBody(), "$.data.sessionId")).longValue();
        assertThat(newSessionId).isNotEqualTo(sessionId);
        assertThat(((Number) JsonPath.read(cont.getBody(), "$.data.nodeCount")).longValue())
                .as("副本节点数=快照节点数").isEqualTo(4);

        // 副本归属与来源归属
        java.util.Map<String, Object> copyRow = jdbc.queryForMap(
                "select user_id, origin_share_id from exploration_session where id=?", newSessionId);
        assertThat(((Number) copyRow.get("user_id")).longValue()).isEqualTo(receiverId);
        assertThat(copyRow.get("origin_share_id")).as("origin_share_id 非空记源").isNotNull();

        // 副本树=快照：4 节点、根=岳麓卡、各卡版本在副本内首现均为新知
        JsonNode copyTree = treeNodes(receiver, newSessionId);
        assertThat(copyTree.size()).isEqualTo(4);
        assertThat(copyTree.get(0).path("parentNodeId").isMissingNode()).as("副本根节点").isTrue();
        assertThat(copyTree.get(0).path("cardTitle").asText()).isEqualTo("岳麓书院");
        assertThat(newKnowledgeCount(copyTree)).isEqualTo(4);

        // 原会话零写入（A5）
        Object originUpdatedAtAfter = jdbc.queryForObject(
                "select updated_at from exploration_session where id=?", Object.class, sessionId);
        assertThat(originUpdatedAtAfter).as("原会话 updated_at 不变").isEqualTo(originUpdatedAtBefore);
        assertThat(jdbc.queryForObject("select count(*) from path_node where session_id=?", Long.class, sessionId))
                .as("原会话节点数不变").isEqualTo(originNodeCountBefore);

        // ---------- 附加：埋点链路（analytics_event 含 service_run / share_continue 行） ----------
        assertThat(jdbc.queryForObject("select count(*) from analytics_event "
                + "where event_type='service_run' and payload->>'status'='DONE'", Integer.class))
                .as("讲解/比较/入口试运行/整理 4 次服务运行终态").isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from analytics_event "
                + "where event_type='service_run' and payload->>'serviceType'='COMPARE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from analytics_event where event_type='share_create'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from analytics_event where event_type='share_view'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from analytics_event "
                + "where event_type='share_continue' and payload->>'newSessionId'='" + newSessionId + "'",
                Integer.class)).as("接续埋点指向新会话").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from analytics_event "
                + "where event_type='node_visit' and payload->>'isNewKnowledge'='true'", Integer.class))
                .as("4 个挂卡节点均记新知访问").isEqualTo(4);
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

    private HttpEntity<Void> noAuth() {
        return new HttpEntity<>(new HttpHeaders());
    }

    /** 注册（API）→ 非 EXPLORER 角色经 JdbcTemplate 提权 → 登录拿 accessToken */
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

    /** 直插知识单元（永久授权），返回 id（title 由调用方保证唯一） */
    private long insertAsset(String title, String extract) {
        jdbc.update("insert into knowledge_asset (kind,title,license,license_expire,content_extract) values (?,?,?,?,?)",
                "book", title, null, null, extract);
        return jdbc.queryForObject("select id from knowledge_asset where title=?", Long.class, title);
    }

    /** 工作台 API 建卡+送审+发布（creator 建并送审、editor 发布），返回 {cardId, currentVersionId} */
    private long[] publishCard(String creator, String editor, String title, String templateType,
                               String content, String sources) {
        String body = "{\"theme\":\"academy\",\"templateType\":\"" + templateType + "\",\"title\":\"" + title
                + "\",\"content\":" + content + ",\"sources\":" + sources + "}";
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards", json(body, creator), String.class);
        assertThat(created.getStatusCode().value()).as("create card body=%s", created.getBody()).isEqualTo(201);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        assertThat(http.exchange("/api/wb/cards/" + cardId + "/submit", HttpMethod.POST, json("{}", creator),
                String.class).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> published = http.exchange("/api/wb/cards/" + cardId + "/publish", HttpMethod.POST,
                json("{}", editor), String.class);
        assertThat(published.getStatusCode().value()).as("publish body=%s", published.getBody()).isEqualTo(200);
        Long versionId = jdbc.queryForObject("select current_version_id from card where id=?", Long.class, cardId);
        assertThat(versionId).as("published card must have current_version_id").isNotNull();
        return new long[]{cardId, versionId};
    }

    /** 创建 PUBLIC 入口（PENDING）→ 编辑审核通过（ACTIVE），返回 entryId（01：公共入口维护者确认后发布） */
    private long createApprovedEntry(String creator, String editor, long cardId, String configJson) {
        ResponseEntity<String> res = http.postForEntity("/api/entries",
                json("{\"cardId\":" + cardId + ",\"config\":" + configJson + ",\"scope\":\"PUBLIC\"}", creator),
                String.class);
        assertThat(res.getStatusCode().value()).as("entry create body=%s", res.getBody()).isEqualTo(201);
        long entryId = ((Number) JsonPath.read(res.getBody(), "$.data.entryId")).longValue();
        List<Integer> reviewIds = JsonPath.read(http.exchange(
                        "/api/wb/reviews?status=PENDING&objectType=ENTRY", HttpMethod.GET, bearer(editor), String.class)
                        .getBody(),
                "$.data.items[?(@.objectId == " + entryId + ")].id");
        assertThat(reviewIds).as("entry %s 应挂审核队列", entryId).isNotEmpty();
        ResponseEntity<String> approve = http.postForEntity("/api/wb/reviews/" + reviewIds.get(0) + "/approve",
                json("{}", editor), String.class);
        assertThat(approve.getStatusCode().value()).as("approve body=%s", approve.getBody()).isEqualTo(200);
        return entryId;
    }

    /** POST /api/sessions → 201 {sessionId} */
    private long createSession(String token, String theme, String goal) {
        ResponseEntity<String> res = http.postForEntity("/api/sessions",
                json("{\"theme\":\"" + theme + "\",\"goal\":\"" + goal + "\"}", token), String.class);
        assertThat(res.getStatusCode().value()).as("create session body=%s", res.getBody()).isEqualTo(201);
        return ((Number) JsonPath.read(res.getBody(), "$.data.sessionId")).longValue();
    }

    /** POST /api/sessions/{id}/nodes → 200，返回新节点 id（cardVersionId/entryId/parentNodeId 均可选） */
    private long addNode(String token, long sessionId, Long cardVersionId, Long entryId, Long parentNodeId,
                         String question) {
        StringBuilder body = new StringBuilder("{\"questionText\":\"").append(question).append("\"");
        if (cardVersionId != null) {
            body.append(",\"cardVersionId\":").append(cardVersionId);
        }
        if (entryId != null) {
            body.append(",\"entryId\":").append(entryId);
        }
        if (parentNodeId != null) {
            body.append(",\"parentNodeId\":").append(parentNodeId);
        }
        body.append("}");
        ResponseEntity<String> res = http.postForEntity("/api/sessions/" + sessionId + "/nodes",
                json(body.toString(), token), String.class);
        assertThat(res.getStatusCode().value()).as("add node body=%s", res.getBody()).isEqualTo(200);
        return ((Number) JsonPath.read(res.getBody(), "$.data.nodeId")).longValue();
    }

    /** POST /api/agent/runs → 202 {runId}（serviceType 缺省 EXPLAIN，parentRunId 可选） */
    private long submitRun(String token, long cardVersionId, long sessionId, long nodeId, Long parentRunId,
                           String serviceType, String question) {
        String body = "{\"cardVersionId\":" + cardVersionId + ",\"sessionId\":" + sessionId
                + ",\"nodeId\":" + nodeId
                + (parentRunId == null ? "" : ",\"parentRunId\":" + parentRunId)
                + ",\"question\":\"" + question + "\",\"level\":\"SIMPLE\""
                + (serviceType == null ? "" : ",\"serviceType\":\"" + serviceType + "\"") + "}";
        ResponseEntity<String> res = http.postForEntity("/api/agent/runs", json(body, token), String.class);
        assertThat(res.getStatusCode().value()).as("submit run body=%s", res.getBody()).isEqualTo(202);
        return ((Number) JsonPath.read(res.getBody(), "$.data.runId")).longValue();
    }

    /** 轮询 GET run 直至终态并断言 DONE，返回末次响应体 */
    private String awaitRun(String token, long runId) {
        AtomicReference<String> body = new AtomicReference<>();
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ResponseEntity<String> res = http.exchange("/api/agent/runs/" + runId, HttpMethod.GET,
                    bearer(token), String.class);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            String status = JsonPath.read(res.getBody(), "$.data.status");
            assertThat(status).isIn("DONE", "FAILED", "TIMEOUT");
            body.set(res.getBody());
        });
        assertThat((String) JsonPath.read(body.get(), "$.data.status")).isEqualTo("DONE");
        return body.get();
    }

    /** POST /api/shares（整会话勾选集+标题摘要）→ 201，返回 token */
    private String createShare(String token, long sessionId, String nodeIdsJson) {
        ResponseEntity<String> res = http.postForEntity("/api/shares",
                json("{\"objectType\":\"SESSION\",\"objectId\":" + sessionId + ",\"nodeIds\":" + nodeIdsJson
                        + ",\"title\":\"湖湘书院全旅程\",\"summary\":\"书院四问一报告\"}", token), String.class);
        assertThat(res.getStatusCode().value()).as("share create body=%s", res.getBody()).isEqualTo(201);
        return JsonPath.read(res.getBody(), "$.data.token");
    }

    /** GET /api/sessions/{id} 的 data.nodes（树序递归展开） */
    private JsonNode treeNodes(String token, long sessionId) {
        ResponseEntity<String> res = http.exchange("/api/sessions/" + sessionId, HttpMethod.GET,
                bearer(token), String.class);
        assertThat(res.getStatusCode().value()).as("tree body=%s", res.getBody()).isEqualTo(200);
        return readData(res.getBody()).path("nodes");
    }

    /** 响应体 data 节点 → JsonNode */
    private static JsonNode readData(String body) {
        try {
            return new ObjectMapper().readTree(body).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON: " + body, e);
        }
    }

    /** 按 nodeId 定位树节点（找不到即抛错，不静默） */
    private static JsonNode nodeById(JsonNode nodes, long nodeId) {
        for (JsonNode n : nodes) {
            if (n.path("nodeId").asLong() == nodeId) {
                return n;
            }
        }
        throw new IllegalStateException("树中找不到节点 " + nodeId + ": " + nodes);
    }

    /** 树中 isNewKnowledge=true 的节点数（01 A1 语义：不同知识对象数） */
    private static long newKnowledgeCount(JsonNode nodes) {
        long count = 0;
        for (JsonNode n : nodes) {
            if (n.path("isNewKnowledge").asBoolean(false)) {
                count++;
            }
        }
        return count;
    }
}
