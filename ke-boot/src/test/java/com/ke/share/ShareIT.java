package com.ke.share;

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
import com.ke.support.ItDb;

/**
 * 分享创建/撤销/免登录浏览（FR-H01–H04/H06，02 §9「有权阅读≠有权转发」数据面）：
 * - POST /api/shares（认证）：勾选节点 ⊆ 本会话（P7-29 钉②：外会话节点 → 400，快照按选择集字面量裁剪），
 *   快照 = 节点+标题+来源（**裁决钉①：不含 findings/artifacts**，SnapshotJson 形状已豁免，此处 JSON 级断言）；
 * - GET /s/{token}（匿名 permitAll）：内容=快照（A4 数据面）；撤销/不存在统一 404 不泄露存在性；
 * - DELETE /api/shares/{token}（属主）：撤销置 revoked=true，已撤销再撤幂等 200；
 * - token：SecureRandom Base62 21 位不可枚举，两次生成不同；
 * - 快照视图规则 wiring（Task 29 SnapshotFilter）：DRAFT 版本 → removed 占位、入口 ACTIVE+PUBLIC/分享者本人。
 *
 * 角色账号：API 注册后经 JdbcTemplate 提权再重新登录（照 SessionPathIT 模式）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class ShareIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

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

    private long userIdByPhone(String phone) {
        return jdbc.queryForObject("select id from ke_user where phone=?", Long.class, phone);
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
                jsonWithToken("{\"theme\":\"" + theme + "\",\"goal\":\"" + goal + "\"}", token), String.class);
        assertThat(created.getStatusCode().value()).as("create session body=%s", created.getBody()).isEqualTo(201);
        return ((Number) JsonPath.read(created.getBody(), "$.data.sessionId")).longValue();
    }

    /** POST /api/sessions/{id}/nodes → 200，返回新节点 id */
    private long addNode(String token, long sessionId, Long cardVersionId, Long parentNodeId, String question) {
        StringBuilder body = new StringBuilder("{");
        if (cardVersionId != null) {
            body.append("\"cardVersionId\":").append(cardVersionId).append(",");
        }
        if (parentNodeId != null) {
            body.append("\"parentNodeId\":").append(parentNodeId).append(",");
        }
        body.append("\"questionText\":\"").append(question).append("\"}");
        ResponseEntity<String> resp = http.postForEntity("/api/sessions/" + sessionId + "/nodes",
                jsonWithToken(body.toString(), token), String.class);
        assertThat(resp.getStatusCode().value()).as("add node body=%s", resp.getBody()).isEqualTo(200);
        return ((Number) JsonPath.read(resp.getBody(), "$.data.nodeId")).longValue();
    }

    /** 直插节点（仅用于「节点挂 DRAFT 版本」这类正常流程造不出的场景：addNode 已拦截未发布卡）。
     *  visited_at 自钉 DB 钟 +1s（env-addendum §4.7 双时钟）：API 挂节点走 JVM 钟，直插走 PG 钟，
     *  两钟亚秒级偏移会让树序（visited_at,id）抖动，+1s 保证直插节点稳定排在既有节点之后 */
    private long insertNodeRaw(long sessionId, Long cardVersionId, String question) {
        return jdbc.queryForObject("insert into path_node(session_id, card_version_id, question_text, visited_at) "
                + "values(?,?,?, now() + interval '1 second') returning id", Long.class, sessionId, cardVersionId,
                question);
    }

    /** 直插入口（照 EntryApiIT 模式；绕开入口四步流，仅造可见性矩阵数据） */
    private long insertEntry(long cardId, String name, String type, String relationLabel, Long targetCardId,
                             String scope, String status, long authorId) {
        return jdbc.queryForObject("insert into entry(card_id,name,type,relation_label,target_card_id,scope,status,"
                + "author_id,sort) values(?,?,?,?,?,?,?,?,?) returning id",
                Long.class, cardId, name, type, relationLabel, targetCardId, scope, status, authorId, 0);
    }

    /** POST /api/shares → 返回 token（断言 201 + code=0 + url 路径片段） */
    private String createShare(String token, long sessionId, Object nodeIds, String title, String summary) {
        StringBuilder body = new StringBuilder("{\"objectType\":\"SESSION\",\"objectId\":").append(sessionId)
                .append(",\"nodeIds\":").append(nodeIds);
        if (title != null) {
            body.append(",\"title\":\"").append(title).append("\"");
        }
        if (summary != null) {
            body.append(",\"summary\":\"").append(summary).append("\"");
        }
        body.append("}");
        ResponseEntity<String> resp = http.postForEntity("/api/shares", jsonWithToken(body.toString(), token),
                String.class);
        assertThat(resp.getStatusCode().value()).as("share create body=%s", resp.getBody()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isZero();
        String shareToken = JsonPath.read(resp.getBody(), "$.data.token");
        assertThat((String) JsonPath.read(resp.getBody(), "$.data.url")).isEqualTo("/s/" + shareToken);
        return shareToken;
    }

    private ResponseEntity<String> anonView(String token) {
        return http.exchange("/s/" + token, HttpMethod.GET, noAuth(), String.class);
    }

    private ResponseEntity<String> revoke(String callerToken, String shareToken) {
        return http.exchange("/api/shares/" + shareToken, HttpMethod.DELETE, bearer(callerToken), String.class);
    }

    // ---------- scenarios ----------

    /** P7-29 Step1 主案：创建 → 匿名可读且内容=快照（A4 数据面）；快照无 findings/artifacts 键（裁决钉①） */
    @Test
    void createAndAnonymousView() {
        String creator = newUserToken("13800170001", "分享卡创作", "CREATOR");
        String editor = newUserToken("13800170002", "分享卡编辑", "EDITOR");
        long creatorId = userIdByPhone("13800170001");
        String reader = newUserToken("13800170003", "分享者", "EXPLORER");
        long readerId = userIdByPhone("13800170003");
        long[] card = publishCard(creator, editor, "岳麓书院", "书院摘要");

        // 可见性矩阵：PUBLIC 入口（任何人）、作者 PRIVATE（隐藏）、分享者本人 PRIVATE（可见）
        insertEntry(card[0], "书院深度讲解", "AGENT_SERVICE", "深入了解", null, "PUBLIC", "ACTIVE", creatorId);
        insertEntry(card[0], "作者私人链接", "LINK_CARD", "相关联", card[0], "PRIVATE", "ACTIVE", creatorId);
        insertEntry(card[0], "读者私人入口", "LINK_CARD", "相关联", card[0], "PRIVATE", "ACTIVE", readerId);

        long sid = createSession(reader, "academy", "湖湘书院探索");
        long n1 = addNode(reader, sid, card[1], null, "岳麓书院的缘起是什么");
        long n2 = addNode(reader, sid, null, n1, "再说说是怎么衰落的");

        // POST /api/shares 恒认证：匿名 → 401
        ResponseEntity<String> anonCreate = http.postForEntity("/api/shares",
                json("{\"objectType\":\"SESSION\",\"objectId\":" + sid + ",\"nodeIds\":[" + n1 + "]}"), String.class);
        assertThat(anonCreate.getStatusCode().value()).as("anon create body=%s", anonCreate.getBody()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(anonCreate.getBody(), "$.code")).isEqualTo(401);

        String token = createShare(reader, sid, "[" + n1 + "," + n2 + "]", "湖湘书院之旅", "书院两问");
        assertThat(token).hasSize(21).matches("[0-9A-Za-z]{21}");

        // 匿名 GET /s/{token} → 200，内容=快照
        ResponseEntity<String> view = anonView(token);
        assertThat(view.getStatusCode().value()).as("anon view body=%s", view.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(view.getBody(), "$.code")).isZero();
        assertThat((String) JsonPath.read(view.getBody(), "$.data.token")).isEqualTo(token);
        assertThat((String) JsonPath.read(view.getBody(), "$.data.title")).isEqualTo("湖湘书院之旅");
        assertThat((String) JsonPath.read(view.getBody(), "$.data.summary")).isEqualTo("书院两问");
        assertThat((String) JsonPath.read(view.getBody(), "$.data.createdAt")).isNotBlank();
        assertThat((String) JsonPath.read(view.getBody(), "$.data.snapshot.generatedAt")).isNotBlank();

        // 卡节点：title=卡题（非节点追问文本），入口=PUBLIC + 分享者本人 PRIVATE，作者 PRIVATE 剔除
        assertThat(((java.util.List<Object>) JsonPath.read(view.getBody(), "$.data.snapshot.nodes")).size()).isEqualTo(2);
        assertThat((String) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[0].title")).isEqualTo("岳麓书院");
        assertThat((Boolean) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[0].removed")).isFalse();
        assertThat(((java.util.List<Object>) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[0].entries")).size())
                .isEqualTo(2);
        assertThat((String) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[0].entries[0].name"))
                .isEqualTo("书院深度讲解");
        assertThat((String) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[0].entries[0].relationLabel"))
                .isEqualTo("深入了解");
        assertThat(view.getBody()).contains("读者私人入口").doesNotContain("作者私人链接");

        // 纯追问节点：title=追问文本、无挂接版本、removed=false
        assertThat((String) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[1].title")).isEqualTo("再说说是怎么衰落的");
        assertThat((Boolean) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[1].removed")).isFalse();
        assertThat(((java.util.List<Object>) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[1].entries")).size())
                .isZero();

        // 裁决钉①：快照=节点+标题+来源，不含讲解 findings / 整理 artifacts 键（JSON 级断言）
        assertThat(view.getBody()).doesNotContain("findings").doesNotContain("artifacts");
    }

    /** 撤销即隐藏（FR-H03/H06）：撤销 → 匿名 404；已撤销再撤幂等 200 */
    @Test
    void revokeHides() {
        String creator = newUserToken("13800170004", "撤销卡创作", "CREATOR");
        String editor = newUserToken("13800170005", "撤销卡编辑", "EDITOR");
        String reader = newUserToken("13800170006", "撤销分享者", "EXPLORER");
        long versionId = publishCard(creator, editor, "可撤销的书院", "撤销摘要")[1];

        long sid = createSession(reader, "academy", "撤销探索");
        long n1 = addNode(reader, sid, versionId, null, "撤销前问一句");
        String token = createShare(reader, sid, "[" + n1 + "]", null, null);
        assertThat(anonView(token).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> revoked = revoke(reader, token);
        assertThat(revoked.getStatusCode().value()).as("revoke body=%s", revoked.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(revoked.getBody(), "$.code")).isZero();

        ResponseEntity<String> after = anonView(token);
        assertThat(after.getStatusCode().value()).as("after revoke body=%s", after.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(after.getBody(), "$.code")).isEqualTo(404);

        // 已 revoked 再撤 → 幂等 200，匿名仍 404
        assertThat(revoke(reader, token).getStatusCode().value()).isEqualTo(200);
        assertThat(anonView(token).getStatusCode().value()).isEqualTo(404);
    }

    /** 未勾选节点不入快照（FR-H01）：3 节点选 2 → snapshot.nodes=2，被排除节点的追问文本不出现在响应 */
    @Test
    void unselectedNodeExcluded() {
        String creator = newUserToken("13800170007", "裁剪卡创作", "CREATOR");
        String editor = newUserToken("13800170008", "裁剪卡编辑", "EDITOR");
        String reader = newUserToken("13800170009", "裁剪分享者", "EXPLORER");
        long versionId = publishCard(creator, editor, "裁剪卡", "裁剪摘要")[1];

        long sid = createSession(reader, "academy", "裁剪探索");
        long n1 = addNode(reader, sid, versionId, null, "第一问");
        long n2 = addNode(reader, sid, versionId, n1, "不想分享的中间一问");
        long n3 = addNode(reader, sid, null, n2, "第三问");

        String token = createShare(reader, sid, "[" + n1 + "," + n3 + "]", null, null);
        ResponseEntity<String> view = anonView(token);
        assertThat(view.getStatusCode().value()).isEqualTo(200);
        assertThat(((java.util.List<Object>) JsonPath.read(view.getBody(), "$.data.snapshot.nodes")).size()).isEqualTo(2);
        assertThat((String) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[0].title")).isEqualTo("裁剪卡");
        assertThat((String) JsonPath.read(view.getBody(), "$.data.snapshot.nodes[1].title")).isEqualTo("第三问");
        // 隐藏分支的字面保障：未选节点的文本整体不出现在快照 JSON（P7-29 钉②的数据面）
        assertThat(view.getBody()).doesNotContain("不想分享的中间一问");
    }

    /** 节点挂 DRAFT 版本 → removed:true 占位（内容已不可用），不泄露草稿卡标题 */
    @Test
    void draftVersionPlaceholder() {
        String creator = newUserToken("13800170010", "占位卡创作", "CREATOR");
        String editor = newUserToken("13800170011", "占位卡编辑", "EDITOR");
        String reader = newUserToken("13800170012", "占位分享者", "EXPLORER");
        long versionId = publishCard(creator, editor, "已发布卡", "占位摘要")[1];

        long sid = createSession(reader, "academy", "占位探索");
        long n1 = addNode(reader, sid, versionId, null, "发布卡问一句");

        // 造 DRAFT 卡（建卡即落 version 1，不送审）→ 直插节点挂草稿版本（addNode 已拦未发布卡）
        ResponseEntity<String> draft = http.postForEntity("/api/wb/cards",
                jsonWithToken("{\"theme\":\"academy\",\"templateType\":\"TEXT\",\"title\":\"草稿秘密卡\",\"content\":"
                        + textContent("草稿摘要") + "}", creator), String.class);
        long draftCardId = ((Number) JsonPath.read(draft.getBody(), "$.data.cardId")).longValue();
        Long draftVersionId = jdbc.queryForObject(
                "select id from card_version where card_id=? order by id limit 1", Long.class, draftCardId);
        long n2 = insertNodeRaw(sid, draftVersionId, "草稿卡的追问");

        String token = createShare(reader, sid, "[" + n1 + "," + n2 + "]", null, null);
        ResponseEntity<String> view = anonView(token);
        assertThat(view.getStatusCode().value()).as("draft view body=%s", view.getBody()).isEqualTo(200);
        assertThat(((java.util.List<Object>) JsonPath.read(view.getBody(), "$.data.snapshot.nodes")).size()).isEqualTo(2);
        // 按追问文本定位两节点（不受树序双钟抖动影响）：发布卡节点正常、草稿节点 removed 占位
        assertThat((java.util.List<Boolean>) JsonPath.read(view.getBody(),
                "$.data.snapshot.nodes[?(@.question=='发布卡问一句')].removed")).containsExactly(false);
        assertThat((java.util.List<Boolean>) JsonPath.read(view.getBody(),
                "$.data.snapshot.nodes[?(@.question=='草稿卡的追问')].removed")).containsExactly(true);
        assertThat((java.util.List<String>) JsonPath.read(view.getBody(),
                "$.data.snapshot.nodes[?(@.question=='草稿卡的追问')].note")).containsExactly("内容已不可用");
        assertThat((java.util.List<String>) JsonPath.read(view.getBody(),
                "$.data.snapshot.nodes[?(@.question=='草稿卡的追问')].title")).containsExactly("草稿卡的追问");
        // 不泄露不可用版本/草稿卡的标题
        assertThat(view.getBody()).doesNotContain("草稿秘密卡");
    }

    /** P7-29 钉②：nodeIds 只接受本会话节点——他人会话节点/不存在的节点 → 400；空选择 → 400 */
    @Test
    void foreignNodeRejected() {
        String creator = newUserToken("13800170013", "外节卡创作", "CREATOR");
        String editor = newUserToken("13800170014", "外节卡编辑", "EDITOR");
        String userA = newUserToken("13800170015", "外节甲", "EXPLORER");
        String userB = newUserToken("13800170016", "外节乙", "EXPLORER");
        long versionId = publishCard(creator, editor, "外节卡", "外节摘要")[1];

        long sidA = createSession(userA, "academy", "甲的会话");
        long nodeA = addNode(userA, sidA, versionId, null, "甲的第一问");
        long sidB = createSession(userB, "sound", "乙的会话");
        long nodeB = addNode(userB, sidB, versionId, null, "乙的第一问");

        // 乙把甲的节点勾进自己的分享 → 400
        ResponseEntity<String> cross = http.postForEntity("/api/shares", jsonWithToken(
                "{\"objectType\":\"SESSION\",\"objectId\":" + sidB + ",\"nodeIds\":[" + nodeB + "," + nodeA + "]}",
                userB), String.class);
        assertThat(cross.getStatusCode().value()).as("cross body=%s", cross.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(cross.getBody(), "$.code")).isEqualTo(400);

        // 不存在的节点 → 400
        ResponseEntity<String> ghost = http.postForEntity("/api/shares", jsonWithToken(
                "{\"objectType\":\"SESSION\",\"objectId\":" + sidB + ",\"nodeIds\":[999999]}", userB), String.class);
        assertThat(ghost.getStatusCode().value()).as("ghost body=%s", ghost.getBody()).isEqualTo(400);

        // 空选择集 → 400「请至少选择一个节点」
        ResponseEntity<String> empty = http.postForEntity("/api/shares", jsonWithToken(
                "{\"objectType\":\"SESSION\",\"objectId\":" + sidB + ",\"nodeIds\":[]}", userB), String.class);
        assertThat(empty.getStatusCode().value()).as("empty body=%s", empty.getBody()).isEqualTo(400);
        assertThat((String) JsonPath.read(empty.getBody(), "$.message")).contains("请至少选择一个节点");

        // 同一节点由其会话属主分享 → 放行（400 判定按会话归属，不是节点形状）
        assertThat(createShare(userA, sidA, "[" + nodeA + "]", null, null)).hasSize(21);
    }

    /** 撤销属主校验：他人 DELETE → 403（不泄露可操作性）；属主撤销成功；匿名 DELETE → 401 */
    @Test
    void foreignUserCannotRevoke() {
        String creator = newUserToken("13800170017", "越权卡创作", "CREATOR");
        String editor = newUserToken("13800170018", "越权卡编辑", "EDITOR");
        String owner = newUserToken("13800170019", "撤销属主", "EXPLORER");
        String stranger = newUserToken("13800170020", "撤销路人", "EXPLORER");
        long versionId = publishCard(creator, editor, "越权卡", "越权摘要")[1];

        long sid = createSession(owner, "academy", "越权探索");
        long n1 = addNode(owner, sid, versionId, null, "越权问一句");
        String token = createShare(owner, sid, "[" + n1 + "]", null, null);

        ResponseEntity<String> other = revoke(stranger, token);
        assertThat(other.getStatusCode().value()).as("other revoke body=%s", other.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(other.getBody(), "$.code")).isEqualTo(403);

        ResponseEntity<String> anon = http.exchange("/api/shares/" + token, HttpMethod.DELETE, noAuth(), String.class);
        assertThat(anon.getStatusCode().value()).as("anon revoke body=%s", anon.getBody()).isEqualTo(401);

        // 属主可撤；分享仍活着（路人 403 未误伤数据）
        assertThat(revoke(owner, token).getStatusCode().value()).isEqualTo(200);
        assertThat(anonView(token).getStatusCode().value()).isEqualTo(404);
    }

    /** token 形状：21 位 Base62 不可枚举，两次生成不同 */
    @Test
    void tokenShape() {
        String creator = newUserToken("13800170021", "token卡创作", "CREATOR");
        String editor = newUserToken("13800170022", "token卡编辑", "EDITOR");
        String reader = newUserToken("13800170023", "token分享者", "EXPLORER");
        long versionId = publishCard(creator, editor, "token卡", "token摘要")[1];

        long sid = createSession(reader, "academy", "token探索");
        long n1 = addNode(reader, sid, versionId, null, "token问一句");
        String t1 = createShare(reader, sid, "[" + n1 + "]", null, null);
        String t2 = createShare(reader, sid, "[" + n1 + "]", null, null);

        assertThat(t1).hasSize(21).matches("[0-9A-Za-z]{21}");
        assertThat(t2).hasSize(21).matches("[0-9A-Za-z]{21}");
        assertThat(t1).isNotEqualTo(t2);
    }

    /** 不存在的 token：匿名浏览与属主撤销均 404（envelope「分享不存在」） */
    @Test
    void nonExistentToken404() {
        String user = newUserToken("13800170024", "404用户", "EXPLORER");

        ResponseEntity<String> view = anonView("zzzzzzzzzzzzzzzzzzzzz");
        assertThat(view.getStatusCode().value()).as("view 404 body=%s", view.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(view.getBody(), "$.code")).isEqualTo(404);
        assertThat((String) JsonPath.read(view.getBody(), "$.message")).contains("分享不存在");

        ResponseEntity<String> revoked = revoke(user, "zzzzzzzzzzzzzzzzzzzzz");
        assertThat(revoked.getStatusCode().value()).as("revoke 404 body=%s", revoked.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(revoked.getBody(), "$.code")).isEqualTo(404);
    }

    /** 不泄露存在性：已撤销分享与不存在 token 的匿名响应完全同形（404 同 message） */
    @Test
    void revokedEqualsMissing() {
        String creator = newUserToken("13800170025", "同形卡创作", "CREATOR");
        String editor = newUserToken("13800170026", "同形卡编辑", "EDITOR");
        String reader = newUserToken("13800170027", "同形分享者", "EXPLORER");
        long versionId = publishCard(creator, editor, "同形卡", "同形摘要")[1];

        long sid = createSession(reader, "academy", "同形探索");
        long n1 = addNode(reader, sid, versionId, null, "同形问一句");
        String token = createShare(reader, sid, "[" + n1 + "]", null, null);
        assertThat(revoke(reader, token).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> revokedView = anonView(token);
        ResponseEntity<String> missingView = anonView("yyyyyyyyyyyyyyyyyyyyy");
        assertThat(revokedView.getStatusCode().value()).isEqualTo(404);
        assertThat(missingView.getStatusCode().value()).isEqualTo(404);
        assertThat((String) JsonPath.read(revokedView.getBody(), "$.message"))
                .isEqualTo(JsonPath.read(missingView.getBody(), "$.message"));
        assertThat((Integer) JsonPath.read(revokedView.getBody(), "$.code"))
                .isEqualTo(JsonPath.read(missingView.getBody(), "$.code"));
    }
}
