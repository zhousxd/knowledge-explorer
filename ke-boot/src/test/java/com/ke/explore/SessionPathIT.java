package com.ke.explore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
import com.ke.infra.mapper.PathNodeMapper;
import com.ke.support.ItDb;

/**
 * 会话与路径树（FR-E01/E03/E05）：/api/sessions 端点族 + 递归子树（Phase 3 终审钉死的 dangerous seam）。
 * - 恒需认证：匿名一律 401（SecurityConfig anyRequest().authenticated() 兜底）；
 * - 三分语义：GET /api/sessions/{id} 匿名 401 / 非属主 403「无权访问该会话」/ 不存在 404「会话不存在」；
 * - GET /api/sessions/latest：404=无会话（P3-13 冻结契约），匿名 401；
 * - 分支=对历史节点再挂子（A2 数据面，parent_node_id 自然成树，不建新表）；
 * - isNewKnowledge=MVP 规则：同会话内该 card_version_id 首次出现=true（01 A1 语义）；
 * - updated_at 应用层维护：latest 与列表按 updated_at DESC, id DESC。
 *
 * 角色账号：API 注册后经 JdbcTemplate 提权再重新登录（照 FavoriteIT 模式）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class SessionPathIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PathNodeMapper pathNodes;

    // ---------- helpers ----------

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<String> jsonWithToken(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<String> bearerJson(String token, String body) {
        return jsonWithToken(body, token);
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
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\",\"nickname\":\"" + nickname + "\"}"),
                String.class);
        assertThat(reg.getStatusCode().value()).as("register body=%s", reg.getBody()).isEqualTo(201);
        if (!"EXPLORER".equals(role)) {
            jdbc.update("update ke_user set role=? where phone=?", role, phone);
        }
        ResponseEntity<String> login = http.postForEntity("/api/auth/login",
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\"}"), String.class);
        assertThat(login.getStatusCode().value()).as("login body=%s", login.getBody()).isEqualTo(200);
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    private static String textContent(String summary) {
        return "{\"summary\":\"" + summary + "\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\"}],\"related\":[]}";
    }

    /** 建卡（TEXT）+ 送审 + 发布，返回 {cardId, currentVersionId} */
    private long[] publishCard(String creatorToken, String editorToken, String title, String summary) {
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken("{\"theme\":\"academy\",\"templateType\":\"TEXT\",\"title\":\"" + title
                        + "\",\"content\":" + textContent(summary) + "}", creatorToken), String.class);
        assertThat(created.getStatusCode().value()).as("create body=%s", created.getBody()).isEqualTo(201);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        ResponseEntity<String> submitted = http.exchange("/api/wb/cards/" + cardId + "/submit", HttpMethod.POST,
                jsonWithToken("{}", creatorToken), String.class);
        assertThat(submitted.getStatusCode().value()).as("submit body=%s", submitted.getBody()).isEqualTo(200);
        ResponseEntity<String> published = http.exchange("/api/wb/cards/" + cardId + "/publish", HttpMethod.POST,
                jsonWithToken("{}", editorToken), String.class);
        assertThat(published.getStatusCode().value()).as("publish body=%s", published.getBody()).isEqualTo(200);
        Long versionId = jdbc.queryForObject("select current_version_id from card where id=?", Long.class, cardId);
        assertThat(versionId).as("published card must have current_version_id").isNotNull();
        return new long[] { cardId, versionId };
    }

    /** POST /api/sessions {theme,goal} → 201 {sessionId} */
    private long createSession(String token, String theme, String goal) {
        ResponseEntity<String> created = http.postForEntity("/api/sessions",
                bearerJson(token, "{\"theme\":\"" + theme + "\",\"goal\":\"" + goal + "\"}"), String.class);
        assertThat(created.getStatusCode().value()).as("create session body=%s", created.getBody()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(created.getBody(), "$.code")).isZero();
        return ((Number) JsonPath.read(created.getBody(), "$.data.sessionId")).longValue();
    }

    /** POST /api/sessions/{id}/nodes → 200，返回新节点 id */
    @SuppressWarnings("unchecked")
    private long addNode(String token, long sessionId, Long cardVersionId, Long parentNodeId, String question) {
        StringBuilder body = new StringBuilder("{");
        if (cardVersionId != null) body.append("\"cardVersionId\":").append(cardVersionId).append(",");
        if (parentNodeId != null) body.append("\"parentNodeId\":").append(parentNodeId).append(",");
        body.append("\"questionText\":\"").append(question).append("\"}");
        ResponseEntity<String> resp = http.postForEntity("/api/sessions/" + sessionId + "/nodes",
                bearerJson(token, body.toString()), String.class);
        assertThat(resp.getStatusCode().value()).as("add node body=%s", resp.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isZero();
        return ((Number) JsonPath.read(resp.getBody(), "$.data.nodeId")).longValue();
    }

    private ResponseEntity<String> getSession(String token, long sessionId) {
        return http.exchange("/api/sessions/" + sessionId, HttpMethod.GET,
                token == null ? noAuth() : bearer(token), String.class);
    }

    // ---------- scenarios ----------

    /** 建会话 → 顺序挂 3 节点成链 → 树含 3 节点、卡题正确、首节点 isNewKnowledge=true */
    @Test
    void createAndChainThreeNodes() {
        String creator = newUserToken("13800160001", "路径创作者", "CREATOR");
        String editor = newUserToken("13800160002", "路径编辑", "EDITOR");
        String reader = newUserToken("13800160003", "路径读者", "EXPLORER");
        long v1 = publishCard(creator, editor, "岳麓书院", "书院摘要")[1];
        long v2 = publishCard(creator, editor, "马王堆汉墓", "汉墓摘要")[1];
        long v3 = publishCard(creator, editor, "湘江夜话", "夜话摘要")[1];

        long sid = createSession(reader, "academy", "了解湖湘书院");
        long n1 = addNode(reader, sid, v1, null, "岳麓书院的缘起是什么");
        long n2 = addNode(reader, sid, v2, n1, "汉代考古有什么发现");
        long n3 = addNode(reader, sid, v3, n2, "湘江流域有何故事");

        ResponseEntity<String> tree = getSession(reader, sid);
        assertThat(tree.getStatusCode().value()).as("tree body=%s", tree.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(tree.getBody(), "$.code")).isZero();
        assertThat(((Number) JsonPath.read(tree.getBody(), "$.data.sessionId")).longValue()).isEqualTo(sid);
        assertThat((String) JsonPath.read(tree.getBody(), "$.data.theme")).isEqualTo("academy");
        assertThat((String) JsonPath.read(tree.getBody(), "$.data.goal")).isEqualTo("了解湖湘书院");
        assertThat((String) JsonPath.read(tree.getBody(), "$.data.explainLevel")).isEqualTo("SIMPLE");
        assertThat((String) JsonPath.read(tree.getBody(), "$.data.status")).isEqualTo("ACTIVE");

        assertThat(((java.util.List<Object>) JsonPath.read(tree.getBody(), "$.data.nodes")).size()).isEqualTo(3);
        // 成链：n1 为根，n2 挂 n1，n3 挂 n2；visited_at,id 稳定排序。
        // 根节点 parentNodeId 为 null，jackson non_null 会省略该键 → 经节点 Map 取值断言。
        assertThat(((Number) JsonPath.read(tree.getBody(), "$.data.nodes[0].nodeId")).longValue()).isEqualTo(n1);
        assertThat(((java.util.Map<?, ?>) JsonPath.read(tree.getBody(), "$.data.nodes[0]")).get("parentNodeId")).isNull();
        assertThat(((Number) JsonPath.read(tree.getBody(), "$.data.nodes[1].nodeId")).longValue()).isEqualTo(n2);
        assertThat(((Number) JsonPath.read(tree.getBody(), "$.data.nodes[1].parentNodeId")).longValue()).isEqualTo(n1);
        assertThat(((Number) JsonPath.read(tree.getBody(), "$.data.nodes[2].nodeId")).longValue()).isEqualTo(n3);
        assertThat(((Number) JsonPath.read(tree.getBody(), "$.data.nodes[2].parentNodeId")).longValue()).isEqualTo(n2);
        // 卡题批量带出
        assertThat((String) JsonPath.read(tree.getBody(), "$.data.nodes[0].cardTitle")).isEqualTo("岳麓书院");
        assertThat((String) JsonPath.read(tree.getBody(), "$.data.nodes[1].cardTitle")).isEqualTo("马王堆汉墓");
        assertThat((String) JsonPath.read(tree.getBody(), "$.data.nodes[2].cardTitle")).isEqualTo("湘江夜话");
        // 每张卡版本首次访问 → isNewKnowledge=true
        assertThat((Boolean) JsonPath.read(tree.getBody(), "$.data.nodes[0].isNewKnowledge")).isTrue();
        assertThat((Boolean) JsonPath.read(tree.getBody(), "$.data.nodes[1].isNewKnowledge")).isTrue();
        assertThat((Boolean) JsonPath.read(tree.getBody(), "$.data.nodes[2].isNewKnowledge")).isTrue();
    }

    /** A2 数据面：对节点 1 再挂节点 4 → 树含 4 节点、节点 1 有 2 子；latest 返回该会话 nodeCount=4 */
    @Test
    void branchOnHistoryNode() {
        String creator = newUserToken("13800160004", "分支创作者", "CREATOR");
        String editor = newUserToken("13800160005", "分支编辑", "EDITOR");
        String reader = newUserToken("13800160006", "分支读者", "EXPLORER");
        long versionId = publishCard(creator, editor, "可分支的书院", "分支摘要")[1];

        long sid = createSession(reader, "academy", "分支探索");
        long n1 = addNode(reader, sid, versionId, null, "第一问");
        long n2 = addNode(reader, sid, versionId, n1, "第二问");
        long n3 = addNode(reader, sid, versionId, n2, "第三问");
        // 回到历史节点 n1 分支
        long n4 = addNode(reader, sid, versionId, n1, "换个方向再问");

        ResponseEntity<String> tree = getSession(reader, sid);
        assertThat(((java.util.List<Object>) JsonPath.read(tree.getBody(), "$.data.nodes")).size()).isEqualTo(4);
        // 节点 1 有 2 个子（n2 与 n4）；根节点无 parentNodeId 键（null 省略），经 Map 取值
        long childrenOfN1 = 0;
        for (int i = 0; i < 4; i++) {
            Object parent = ((java.util.Map<?, ?>) JsonPath.read(tree.getBody(), "$.data.nodes[" + i + "]"))
                    .get("parentNodeId");
            if (parent instanceof Number num && num.longValue() == n1) {
                childrenOfN1++;
            }
        }
        assertThat(childrenOfN1).isEqualTo(2);
        assertThat(((Number) JsonPath.read(tree.getBody(), "$.data.nodes[3].nodeId")).longValue()).isEqualTo(n4);

        // 递归 CTE（成果整理/分享共用）：从分叉点 n1 出发 = n1 及其全部后代（n2 的链与 n4）；
        // 从链中段 n2 出发则裁剪为 {n2, n3} —— 任意节点向下子树语义验证
        assertThat(pathNodes.selectSubtree(sid, n1)
                .stream().map(com.ke.infra.entity.PathNodeEntity::getId).toList())
                .containsExactly(n1, n2, n3, n4);
        java.util.List<com.ke.infra.entity.PathNodeEntity> mid = pathNodes.selectSubtree(sid, n2);
        assertThat(mid.stream().map(com.ke.infra.entity.PathNodeEntity::getId).toList())
                .containsExactly(n2, n3);
        assertThat(mid.get(0).getParentNodeId()).isEqualTo(n1);

        // latest：返回该会话，nodeCount=4
        ResponseEntity<String> latest = http.exchange("/api/sessions/latest", HttpMethod.GET, bearer(reader), String.class);
        assertThat(latest.getStatusCode().value()).as("latest body=%s", latest.getBody()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(latest.getBody(), "$.data.sessionId")).longValue()).isEqualTo(sid);
        assertThat(((Number) JsonPath.read(latest.getBody(), "$.data.nodeCount")).longValue()).isEqualTo(4);
        assertThat(((Number) JsonPath.read(latest.getBody(), "$.data.branchCount")).longValue()).isEqualTo(1);
        assertThat((String) JsonPath.read(latest.getBody(), "$.data.title")).isEqualTo("可分支的书院");
        assertThat((String) JsonPath.read(latest.getBody(), "$.data.lastVisitedAt")).isNotBlank();
        assertThat(((Number) JsonPath.read(latest.getBody(), "$.data.openQuestionCount")).longValue()).isZero();
    }

    /** 三分访问控制：匿名 401；非属主 403「无权访问该会话」；不存在 404「会话不存在」 */
    @Test
    void threeWayAccessControl() {
        String owner = newUserToken("13800160007", "会话属主", "EXPLORER");
        String stranger = newUserToken("13800160008", "无关用户", "EXPLORER");
        long sid = createSession(owner, "cuisine", "属主的会话");

        // 匿名 → 401 envelope
        ResponseEntity<String> anon = getSession(null, sid);
        assertThat(anon.getStatusCode().value()).as("anon body=%s", anon.getBody()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(anon.getBody(), "$.code")).isEqualTo(401);

        // 非属主 → 403 envelope，不泄露会话细节
        ResponseEntity<String> other = getSession(stranger, sid);
        assertThat(other.getStatusCode().value()).as("other body=%s", other.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(other.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(other.getBody(), "$.message")).contains("无权访问该会话");

        // 不存在 → 404 envelope「会话不存在」（不得混用 403）
        ResponseEntity<String> missing = getSession(owner, 999999L);
        assertThat(missing.getStatusCode().value()).as("missing body=%s", missing.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(missing.getBody(), "$.code")).isEqualTo(404);
        assertThat((String) JsonPath.read(missing.getBody(), "$.message")).contains("会话不存在");

        // 写路径同则：非属主挂节点 / 改 explain_level 均 403
        ResponseEntity<String> otherNode = http.postForEntity("/api/sessions/" + sid + "/nodes",
                bearerJson(stranger, "{\"questionText\":\"蹭一下\"}"), String.class);
        assertThat(otherNode.getStatusCode().value()).as("other node body=%s", otherNode.getBody()).isEqualTo(403);
        ResponseEntity<String> otherLevel = http.exchange("/api/sessions/" + sid + "/explain-level", HttpMethod.PUT,
                bearerJson(stranger, "{\"level\":\"CHILD\"}"), String.class);
        assertThat(otherLevel.getStatusCode().value()).as("other level body=%s", otherLevel.getBody()).isEqualTo(403);
    }

    /** latest：新用户无会话 → 404 envelope（P3-13 冻结契约）；匿名 → 401 */
    @Test
    void latestEmpty404() {
        String fresh = newUserToken("13800160009", "无会话用户", "EXPLORER");
        ResponseEntity<String> resp = http.exchange("/api/sessions/latest", HttpMethod.GET, bearer(fresh), String.class);
        assertThat(resp.getStatusCode().value()).as("latest body=%s", resp.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(404);

        ResponseEntity<String> anon = http.exchange("/api/sessions/latest", HttpMethod.GET, noAuth(), String.class);
        assertThat(anon.getStatusCode().value()).as("anon latest body=%s", anon.getBody()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(anon.getBody(), "$.code")).isEqualTo(401);
    }

    /** addNode：parentNodeId 属于别的会话（或不存在）→ 400 envelope */
    @Test
    void addNodeValidatesParent() {
        String creator = newUserToken("13800160010", "父校验创作", "CREATOR");
        String editor = newUserToken("13800160011", "父校验编辑", "EDITOR");
        String userA = newUserToken("13800160012", "用户甲", "EXPLORER");
        String userB = newUserToken("13800160013", "用户乙", "EXPLORER");
        long versionId = publishCard(creator, editor, "父校验卡", "父校验摘要")[1];

        long sidA = createSession(userA, "academy", "甲的会话");
        long sidB = createSession(userB, "sound", "乙的会话");
        long nodeA = addNode(userA, sidA, versionId, null, "甲的第一问");

        // 乙把甲的节点当父 → 400
        ResponseEntity<String> cross = http.postForEntity("/api/sessions/" + sidB + "/nodes",
                bearerJson(userB, "{\"parentNodeId\":" + nodeA + ",\"questionText\":\"借父\"}"), String.class);
        assertThat(cross.getStatusCode().value()).as("cross body=%s", cross.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(cross.getBody(), "$.code")).isEqualTo(400);

        // 不存在的父 → 400
        ResponseEntity<String> ghost = http.postForEntity("/api/sessions/" + sidB + "/nodes",
                bearerJson(userB, "{\"parentNodeId\":999999,\"questionText\":\"借父\"}"), String.class);
        assertThat(ghost.getStatusCode().value()).as("ghost body=%s", ghost.getBody()).isEqualTo(400);
    }

    /** 仅可挂已发布卡：DRAFT 卡版本挂进会话 → 400「卡片未发布」（版本存在，与「卡片版本不存在」400 语义分立，
     *  防草稿版本经树接口读出卡题绕过公开卡 404 可见性）；发布后同版本照常成功（既有 PUBLISHED 场景即证，此处复验闭环） */
    @Test
    void addNodeRejectsUnpublishedCardVersion() {
        String creator = newUserToken("13800160021", "未发卡创作", "CREATOR");
        String editor = newUserToken("13800160022", "未发卡编辑", "EDITOR");
        String reader = newUserToken("13800160023", "未发卡读者", "EXPLORER");
        // 造 DRAFT 卡（建卡即落 version 1，不送审不发布）
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken("{\"theme\":\"academy\",\"templateType\":\"TEXT\",\"title\":\"待发布卡\",\"content\":"
                        + textContent("待发布摘要") + "}", creator), String.class);
        assertThat(created.getStatusCode().value()).as("create body=%s", created.getBody()).isEqualTo(201);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        Long draftVersionId = jdbc.queryForObject(
                "select id from card_version where card_id=? order by id limit 1", Long.class, cardId);
        assertThat(draftVersionId).as("draft card must have version 1").isNotNull();

        long sid = createSession(reader, "academy", "未发卡会话");
        ResponseEntity<String> rejected = http.postForEntity("/api/sessions/" + sid + "/nodes",
                bearerJson(reader, "{\"cardVersionId\":" + draftVersionId + ",\"questionText\":\"偷看草稿\"}"),
                String.class);
        assertThat(rejected.getStatusCode().value()).as("rejected body=%s", rejected.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(rejected.getBody(), "$.code")).isEqualTo(400);
        assertThat((String) JsonPath.read(rejected.getBody(), "$.message")).contains("卡片未发布");
        // 400 不落节点：树仍为空
        ResponseEntity<String> tree = getSession(reader, sid);
        assertThat(((java.util.List<Object>) JsonPath.read(tree.getBody(), "$.data.nodes")).size()).isZero();

        // 送审 + 发布后，同一版本照常挂载成功（PUBLISHED 放行，不误伤正常路径）
        assertThat(http.exchange("/api/wb/cards/" + cardId + "/submit", HttpMethod.POST,
                jsonWithToken("{}", creator), String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.exchange("/api/wb/cards/" + cardId + "/publish", HttpMethod.POST,
                jsonWithToken("{}", editor), String.class).getStatusCode().value()).isEqualTo(200);
        Long publishedVersionId = jdbc.queryForObject(
                "select current_version_id from card where id=?", Long.class, cardId);
        ResponseEntity<String> ok = http.postForEntity("/api/sessions/" + sid + "/nodes",
                bearerJson(reader, "{\"cardVersionId\":" + publishedVersionId + ",\"questionText\":\"发布后访问\"}"),
                String.class);
        assertThat(ok.getStatusCode().value()).as("ok body=%s", ok.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(ok.getBody(), "$.code")).isZero();
        assertThat((String) JsonPath.read(ok.getBody(), "$.data.cardTitle")).isEqualTo("待发布卡");
        assertThat((Boolean) JsonPath.read(ok.getBody(), "$.data.isNewKnowledge")).isTrue();
    }

    /** isNewKnowledge：同卡版本同会话第二次访问=false；跨会话重新=true */
    @Test
    void repeatVisitNotNewKnowledge() {
        String creator = newUserToken("13800160014", "重访创作", "CREATOR");
        String editor = newUserToken("13800160015", "重访编辑", "EDITOR");
        String reader = newUserToken("13800160016", "重访读者", "EXPLORER");
        long versionId = publishCard(creator, editor, "重访卡", "重访摘要")[1];

        long sid = createSession(reader, "academy", "重访会话");
        long n1 = addNode(reader, sid, versionId, null, "第一次问");
        long n2 = addNode(reader, sid, versionId, n1, "第二次问");

        ResponseEntity<String> tree = getSession(reader, sid);
        assertThat((Boolean) JsonPath.read(tree.getBody(), "$.data.nodes[0].isNewKnowledge")).isTrue();
        assertThat((Boolean) JsonPath.read(tree.getBody(), "$.data.nodes[1].isNewKnowledge")).isFalse();

        // 新会话首次访问同一版本 → 重新为 true（按会话内首次出现计）
        long sid2 = createSession(reader, "academy", "第二个会话");
        addNode(reader, sid2, versionId, null, "新会话再问");
        ResponseEntity<String> tree2 = getSession(reader, sid2);
        assertThat((Boolean) JsonPath.read(tree2.getBody(), "$.data.nodes[0].isNewKnowledge")).isTrue();
    }

    /** explain_level 更新：SIMPLE→CHILD 后 GET 带出 CHILD；非法值 → 400 envelope */
    @Test
    void explainLevelUpdate() {
        String reader = newUserToken("13800160017", "讲解度用户", "EXPLORER");
        long sid = createSession(reader, "sound", "讲解度会话");
        assertThat((String) JsonPath.read(getSession(reader, sid).getBody(), "$.data.explainLevel")).isEqualTo("SIMPLE");

        ResponseEntity<String> updated = http.exchange("/api/sessions/" + sid + "/explain-level", HttpMethod.PUT,
                bearerJson(reader, "{\"level\":\"CHILD\"}"), String.class);
        assertThat(updated.getStatusCode().value()).as("update body=%s", updated.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(updated.getBody(), "$.data.explainLevel")).isEqualTo("CHILD");
        assertThat((String) JsonPath.read(getSession(reader, sid).getBody(), "$.data.explainLevel")).isEqualTo("CHILD");

        ResponseEntity<String> illegal = http.exchange("/api/sessions/" + sid + "/explain-level", HttpMethod.PUT,
                bearerJson(reader, "{\"level\":\"HARD\"}"), String.class);
        assertThat(illegal.getStatusCode().value()).as("illegal body=%s", illegal.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(illegal.getBody(), "$.code")).isEqualTo(400);
    }

    /** 列表分页：12 会话 size=5 → items5/total12；updated_at DESC（最新在前），应用层维护 updated_at 生效 */
    @Test
    void listPaginates() throws Exception {
        String reader = newUserToken("13800160018", "列表用户", "EXPLORER");
        long first = 0;
        long second = 0;
        long last = 0;
        for (int i = 1; i <= 12; i++) {
            long sid = createSession(reader, "academy", "第" + i + "个会话");
            if (i == 1) {
                first = sid;
            }
            if (i == 2) {
                second = sid;
            }
            if (i == 12) {
                last = sid;
            }
        }
        // 排序仅观 updated_at（应用钟）：创建间隔毫秒级，钉钟断言防抖（env-addendum §4.7 双时钟）
        Thread.sleep(30);
        // 对最早会话 PUT explain_level → updated_at 应用层刷新 → 应跳到列表最前
        ResponseEntity<String> bump = http.exchange("/api/sessions/" + first + "/explain-level", HttpMethod.PUT,
                bearerJson(reader, "{\"level\":\"DEEP\"}"), String.class);
        assertThat(bump.getStatusCode().value()).as("bump body=%s", bump.getBody()).isEqualTo(200);

        ResponseEntity<String> page1 = http.exchange("/api/sessions?page=1&size=5", HttpMethod.GET,
                bearer(reader), String.class);
        assertThat(page1.getStatusCode().value()).as("page1 body=%s", page1.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(page1.getBody(), "$.data.total")).isEqualTo(12);
        assertThat((Integer) JsonPath.read(page1.getBody(), "$.data.page")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(page1.getBody(), "$.data.size")).isEqualTo(5);
        assertThat(((java.util.List<Object>) JsonPath.read(page1.getBody(), "$.data.items")).size()).isEqualTo(5);
        // updated_at 被应用层刷新过的最早会话排最前
        assertThat(((Number) JsonPath.read(page1.getBody(), "$.data.items[0].sessionId")).longValue()).isEqualTo(first);
        // 其余按创建逆序（updated_at DESC，同刻以 id DESC 破平）：第 12 个（最后创建）紧随其后
        assertThat(((Number) JsonPath.read(page1.getBody(), "$.data.items[1].sessionId")).longValue()).isEqualTo(last);
        // 列表行形状：theme/goal/title/status/nodeCount/branchCount/lastVisitedAt
        assertThat((String) JsonPath.read(page1.getBody(), "$.data.items[0].goal")).isEqualTo("第1个会话");
        assertThat((String) JsonPath.read(page1.getBody(), "$.data.items[0].status")).isEqualTo("ACTIVE");
        assertThat(((Number) JsonPath.read(page1.getBody(), "$.data.items[0].nodeCount")).longValue()).isZero();

        // 第 2 页 5 条
        ResponseEntity<String> page2 = http.exchange("/api/sessions?page=2&size=5", HttpMethod.GET,
                bearer(reader), String.class);
        assertThat(((java.util.List<Object>) JsonPath.read(page2.getBody(), "$.data.items")).size()).isEqualTo(5);
        // 末页剩 2 条，最后一条是最早的第二个会话
        ResponseEntity<String> page3 = http.exchange("/api/sessions?page=3&size=5", HttpMethod.GET,
                bearer(reader), String.class);
        assertThat(((java.util.List<Object>) JsonPath.read(page3.getBody(), "$.data.items")).size()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(page3.getBody(), "$.data.items[1].sessionId")).longValue()).isEqualTo(second);
    }

    /** latest：最新节点为纯追问节点（无卡版本）→ 200，title 走 goal→「新探索」兜底（回归：List.of(null) NPE） */
    @Test
    void latestPureQuestionNodeFallsBackTitle() {
        String reader = newUserToken("13800160019", "追问用户", "EXPLORER");
        long sid = createSession(reader, "academy", "学习书院");
        addNode(reader, sid, null, null, "书院是什么");

        ResponseEntity<String> latest = http.exchange("/api/sessions/latest", HttpMethod.GET, bearer(reader), String.class);
        assertThat(latest.getStatusCode().value()).as("latest body=%s", latest.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(latest.getBody(), "$.code")).isZero();
        assertThat(((Number) JsonPath.read(latest.getBody(), "$.data.sessionId")).longValue()).isEqualTo(sid);
        assertThat((String) JsonPath.read(latest.getBody(), "$.data.title")).isEqualTo("学习书院");
        assertThat((String) JsonPath.read(latest.getBody(), "$.data.lastVisitedAt")).isNotBlank();
        assertThat(((Number) JsonPath.read(latest.getBody(), "$.data.nodeCount")).longValue()).isEqualTo(1);

        // 无 goal 会话的纯追问 → title 兜底「新探索」，且 latest 取 updated_at 最新的会话
        ResponseEntity<String> created = http.postForEntity("/api/sessions",
                bearerJson(reader, "{\"theme\":\"sound\"}"), String.class);
        assertThat(created.getStatusCode().value()).as("create body=%s", created.getBody()).isEqualTo(201);
        long sid2 = ((Number) JsonPath.read(created.getBody(), "$.data.sessionId")).longValue();
        addNode(reader, sid2, null, null, "再问一句");
        ResponseEntity<String> latest2 = http.exchange("/api/sessions/latest", HttpMethod.GET, bearer(reader), String.class);
        assertThat(latest2.getStatusCode().value()).as("latest2 body=%s", latest2.getBody()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(latest2.getBody(), "$.data.sessionId")).longValue()).isEqualTo(sid2);
        assertThat((String) JsonPath.read(latest2.getBody(), "$.data.title")).isEqualTo("新探索");
    }

    /** 全追问会话列表：无任何卡版本 → listMine 不 500，title 走兜底（回归：Map.of().get(null) NPE） */
    @Test
    void listMineAllQuestionNodesNoNpe() {
        String reader = newUserToken("13800160020", "纯追问列表用户", "EXPLORER");
        long sid1 = createSession(reader, "cuisine", "湘菜问一");
        long sid2 = createSession(reader, "cuisine", "湘菜问二");
        addNode(reader, sid1, null, null, "臭豆腐为何黑");
        addNode(reader, sid2, null, null, "剁椒鱼头怎样蒸");

        ResponseEntity<String> list = http.exchange("/api/sessions", HttpMethod.GET, bearer(reader), String.class);
        assertThat(list.getStatusCode().value()).as("list body=%s", list.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(list.getBody(), "$.code")).isZero();
        assertThat((Integer) JsonPath.read(list.getBody(), "$.data.total")).isEqualTo(2);
        // 最新会话在前，title 兜底为 goal；nodeCount=1、branchCount=0
        assertThat(((Number) JsonPath.read(list.getBody(), "$.data.items[0].sessionId")).longValue()).isEqualTo(sid2);
        assertThat((String) JsonPath.read(list.getBody(), "$.data.items[0].title")).isEqualTo("湘菜问二");
        assertThat(((Number) JsonPath.read(list.getBody(), "$.data.items[0].nodeCount")).longValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(list.getBody(), "$.data.items[0].branchCount")).longValue()).isZero();
        assertThat((String) JsonPath.read(list.getBody(), "$.data.items[1].title")).isEqualTo("湘菜问一");
    }
}
