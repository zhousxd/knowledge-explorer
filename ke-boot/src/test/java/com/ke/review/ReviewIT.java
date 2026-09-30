package com.ke.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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
 * 审核工作流与审计留痕（FR-O03 / 02 §9）端到端：
 * CREATOR submit 卡片 → EDITOR 在审核队列看到（item 带 summary=标题·模板·提交人昵称 与
 * precheck={contentValid, hasSources}）→ approve → 卡 PUBLISHED 且 audit_log 由 AOP 落
 * CARD_PUBLISH（object_id=卡、actor_id=审核人）；reject 带 notes → 卡回 DRAFT、review_task
 * REJECTED 带 notes；notes 缺失 → 400 envelope；EXPLORER 不能看队列（403）；
 * 审核人不能 approve 自己提交的内容（403，卡与任务保持 PENDING）。
 *
 * 角色账号：API 注册后经 JdbcTemplate 提权再重新登录（JWT 载荷携带提权后的角色）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class ReviewIT {

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
        return "{\"summary\":\"" + summary + "\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\",\"citations\":[0]}],\"related\":[]}";
    }

    /** contentJson 是 JSON 里的字符串字段，需转义引号 */
    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 建卡（TEXT）+ 送审，返回 cardId */
    private long createAndSubmitCard(String authorToken, String theme, String title, String summary) {
        String body = "{\"theme\":\"" + theme + "\",\"templateType\":\"TEXT\",\"title\":\"" + title
                + "\",\"contentJson\":" + quote(textContent(summary)) + "}";
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards", jsonWithToken(body, authorToken), String.class);
        assertThat(created.getStatusCode().value()).as("create body=%s", created.getBody()).isEqualTo(201);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        ResponseEntity<String> submitted = http.exchange("/api/wb/cards/" + cardId + "/submit", HttpMethod.POST,
                jsonWithToken("{}", authorToken), String.class);
        assertThat(submitted.getStatusCode().value()).as("submit body=%s", submitted.getBody()).isEqualTo(200);
        return cardId;
    }

    /** 在审核队列里按 objectId 找到对应 review_task id */
    private long pendingReviewId(String editorToken, long objectId) {
        ResponseEntity<String> res = http.exchange("/api/wb/reviews?status=PENDING", HttpMethod.GET,
                bearer(editorToken), String.class);
        assertThat(res.getStatusCode().value()).as("queue body=%s", res.getBody()).isEqualTo(200);
        List<Integer> ids = JsonPath.read(res.getBody(), "$.data[?(@.objectId == " + objectId + ")].id");
        assertThat(ids).as("queue should contain review for object %s: %s", objectId, res.getBody()).isNotEmpty();
        return ids.get(0).longValue();
    }

    private ResponseEntity<String> post(String token, String path, String body) {
        return http.exchange(path, HttpMethod.POST, jsonWithToken(body, token), String.class);
    }

    // ---------- scenarios ----------

    @Test
    void creatorSubmitsEditorApproves() {
        String creator = newUserToken("13800002001", "创作者甲", "CREATOR");
        String editor = newUserToken("13800002002", "编辑甲", "EDITOR");
        long cardId = createAndSubmitCard(creator, "湖湘文化", "岳麓书院", "千年学府简介");

        // EDITOR 在 PENDING 队列看到该卡：objectType/action/status + summary + precheck
        ResponseEntity<String> queue = http.exchange("/api/wb/reviews?status=PENDING", HttpMethod.GET,
                bearer(editor), String.class);
        assertThat(queue.getStatusCode().value()).as("queue body=%s", queue.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(queue.getBody(), "$.code")).isZero();
        List<String> types = JsonPath.read(queue.getBody(), "$.data[?(@.objectId == " + cardId + ")].objectType");
        assertThat(types).containsExactly("CARD");
        List<String> actions = JsonPath.read(queue.getBody(), "$.data[?(@.objectId == " + cardId + ")].action");
        assertThat(actions).containsExactly("SUBMIT");
        List<String> statuses = JsonPath.read(queue.getBody(), "$.data[?(@.objectId == " + cardId + ")].status");
        assertThat(statuses).containsExactly("PENDING");
        List<String> summaries = JsonPath.read(queue.getBody(), "$.data[?(@.objectId == " + cardId + ")].summary");
        assertThat(summaries.get(0)).contains("岳麓书院").contains("TEXT").contains("创作者甲");
        List<Boolean> contentValid = JsonPath.read(queue.getBody(),
                "$.data[?(@.objectId == " + cardId + ")].precheck.contentValid");
        assertThat(contentValid).containsExactly(true);

        long reviewId = pendingReviewId(editor, cardId);
        ResponseEntity<String> approved = post(editor, "/api/wb/reviews/" + reviewId + "/approve", "{}");
        assertThat(approved.getStatusCode().value()).as("approve body=%s", approved.getBody()).isEqualTo(200);

        // 卡片 PUBLISHED；review_task APPROVED 带 reviewer
        assertThat(jdbc.queryForObject("select status from card where id=?", String.class, cardId)).isEqualTo("PUBLISHED");
        Map<String, Object> task = jdbc.queryForMap("select status, reviewer_id from review_task where id=?", reviewId);
        assertThat(task.get("status")).isEqualTo("APPROVED");
        Long editorId = jdbc.queryForObject("select id from ke_user where phone=?", Long.class, "13800002002");
        assertThat(((Number) task.get("reviewer_id")).longValue()).isEqualTo(editorId);

        // audit_log：AOP 自动落 CARD_PUBLISH，actor=审核人，detail_json 带方法参数
        // （送审本身还落一行 CARD_SUBMIT，此处按 action 精确断言发布记录）
        Map<String, Object> audit = jdbc.queryForMap(
                "select actor_id from audit_log where action='CARD_PUBLISH' and object_type='CARD' and object_id=?",
                cardId);
        assertThat(((Number) audit.get("actor_id")).longValue()).isEqualTo(editorId);
        String detail = jdbc.queryForObject(
                "select detail_json::text from audit_log where action='CARD_PUBLISH' and object_type='CARD' and object_id=?",
                String.class, cardId);
        assertThat(detail).contains("cardId");
    }

    @Test
    void rejectReturnsToDraft() {
        String creator = newUserToken("13800002003", "创作者乙", "CREATOR");
        String editor = newUserToken("13800002004", "编辑乙", "EDITOR");
        long cardId = createAndSubmitCard(creator, "湖湘文化", "被驳回的卡", "驳回测试摘要");

        long reviewId = pendingReviewId(editor, cardId);
        ResponseEntity<String> rejected = post(editor, "/api/wb/reviews/" + reviewId + "/reject",
                "{\"notes\":\"来源不足，请补充出处\"}");
        assertThat(rejected.getStatusCode().value()).as("reject body=%s", rejected.getBody()).isEqualTo(200);

        assertThat(jdbc.queryForObject("select status from card where id=?", String.class, cardId)).isEqualTo("DRAFT");
        Map<String, Object> task = jdbc.queryForMap("select status, reviewer_id, notes from review_task where id=?", reviewId);
        assertThat(task.get("status")).isEqualTo("REJECTED");
        Long editorId = jdbc.queryForObject("select id from ke_user where phone=?", Long.class, "13800002004");
        assertThat(((Number) task.get("reviewer_id")).longValue()).isEqualTo(editorId);
        assertThat(task.get("notes")).isEqualTo("来源不足，请补充出处");
    }

    @Test
    void rejectWithoutNotesRejected() {
        String creator = newUserToken("13800002005", "创作者丙", "CREATOR");
        String editor = newUserToken("13800002006", "编辑丙", "EDITOR");
        long cardId = createAndSubmitCard(creator, "湖湘文化", "缺意见的卡", "缺意见摘要");

        long reviewId = pendingReviewId(editor, cardId);
        for (String body : new String[]{"{\"notes\":\"   \"}", "{}"}) {
            ResponseEntity<String> res = post(editor, "/api/wb/reviews/" + reviewId + "/reject", body);
            assertThat(res.getStatusCode().value()).as("reject body=%s → %s", body, res.getBody()).isEqualTo(400);
            assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(400);
            assertThat((String) JsonPath.read(res.getBody(), "$.traceId")).isNotBlank();
        }

        // 卡与任务均保持 PENDING，未产生任何流转
        assertThat(jdbc.queryForObject("select status from card where id=?", String.class, cardId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from review_task where id=?", String.class, reviewId))
                .isEqualTo("PENDING");
    }

    @Test
    void explorerCannotListReviews() {
        String explorer = newUserToken("13800002007", "访客甲", "EXPLORER");
        ResponseEntity<String> res = http.exchange("/api/wb/reviews?status=PENDING", HttpMethod.GET,
                bearer(explorer), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(res.getBody(), "$.traceId")).isNotBlank();
    }

    @Test
    void selfApprovalForbidden() {
        // EDITOR 可建卡（CREATOR/EDITOR/OPERATOR 均可），但不能 approve 自己提交的内容
        String editor = newUserToken("13800002008", "编辑丁", "EDITOR");
        long cardId = createAndSubmitCard(editor, "湖湘文化", "自审卡", "自审摘要");

        long reviewId = pendingReviewId(editor, cardId);
        ResponseEntity<String> res = post(editor, "/api/wb/reviews/" + reviewId + "/approve", "{}");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(res.getBody(), "$.traceId")).isNotBlank();

        // 卡与任务保持 PENDING
        assertThat(jdbc.queryForObject("select status from card where id=?", String.class, cardId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from review_task where id=?", String.class, reviewId))
                .isEqualTo("PENDING");
    }
}
