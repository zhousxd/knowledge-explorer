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

    /** 无引用的 TEXT content（sources 契约下空 sources 不允许 citations） */
    private static String textContent(String summary) {
        return "{\"summary\":\"" + summary + "\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\"}],\"related\":[]}";
    }

    /** 建卡（指定模板与 content）+ 送审，返回 cardId */
    private long createCard(String authorToken, String theme, String title, String templateType, String contentJson) {
        String body = "{\"theme\":\"" + theme + "\",\"templateType\":\"" + templateType + "\",\"title\":\"" + title
                + "\",\"content\":" + contentJson + "}";
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards", jsonWithToken(body, authorToken), String.class);
        assertThat(created.getStatusCode().value()).as("create body=%s", created.getBody()).isEqualTo(201);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        ResponseEntity<String> submitted = http.exchange("/api/wb/cards/" + cardId + "/submit", HttpMethod.POST,
                jsonWithToken("{}", authorToken), String.class);
        assertThat(submitted.getStatusCode().value()).as("submit body=%s", submitted.getBody()).isEqualTo(200);
        return cardId;
    }

    /** 建卡（TEXT）+ 送审，返回 cardId */
    private long createAndSubmitCard(String authorToken, String theme, String title, String summary) {
        return createCard(authorToken, theme, title, "TEXT", textContent(summary));
    }

    /** 在审核队列里按 objectId 找到对应 review_task id */
    private long pendingReviewId(String editorToken, long objectId) {
        ResponseEntity<String> res = http.exchange("/api/wb/reviews?status=PENDING", HttpMethod.GET,
                bearer(editorToken), String.class);
        assertThat(res.getStatusCode().value()).as("queue body=%s", res.getBody()).isEqualTo(200);
        List<Integer> ids = JsonPath.read(res.getBody(), "$.data.items[?(@.objectId == " + objectId + ")].id");
        assertThat(ids).as("queue should contain review for object %s: %s", objectId, res.getBody()).isNotEmpty();
        return ids.get(0).longValue();
    }

    /** 从队列响应里按 objectId 取 contentPreview */
    private String previewOf(String queueBody, long objectId) {
        List<String> previews = JsonPath.read(queueBody,
                "$.data.items[?(@.objectId == " + objectId + ")].contentPreview");
        assertThat(previews).as("queue item for object %s: %s", objectId, queueBody).isNotEmpty();
        return previews.get(0);
    }

    /** 当前 PENDING 队列总数（同类其他用例会残留 PENDING 任务，计数断言一律以基线为参照） */
    private int pendingTotal(String editorToken) {
        ResponseEntity<String> res = http.exchange("/api/wb/reviews?status=PENDING&page=1&size=100", HttpMethod.GET,
                bearer(editorToken), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (Integer) JsonPath.read(res.getBody(), "$.data.total");
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
        List<String> types = JsonPath.read(queue.getBody(), "$.data.items[?(@.objectId == " + cardId + ")].objectType");
        assertThat(types).containsExactly("CARD");
        List<String> actions = JsonPath.read(queue.getBody(), "$.data.items[?(@.objectId == " + cardId + ")].action");
        assertThat(actions).containsExactly("SUBMIT");
        List<String> statuses = JsonPath.read(queue.getBody(), "$.data.items[?(@.objectId == " + cardId + ")].status");
        assertThat(statuses).containsExactly("PENDING");
        List<String> summaries = JsonPath.read(queue.getBody(), "$.data.items[?(@.objectId == " + cardId + ")].summary");
        assertThat(summaries.get(0)).contains("岳麓书院").contains("TEXT").contains("创作者甲");
        List<Boolean> contentValid = JsonPath.read(queue.getBody(),
                "$.data.items[?(@.objectId == " + cardId + ")].precheck.contentValid");
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

    @Test
    void rejectSelfForbidden() {
        // 自审禁绝对 reject 同样生效：编辑不能驳回自己提交的内容
        String editor = newUserToken("13800002011", "编辑己", "EDITOR");
        long cardId = createAndSubmitCard(editor, "湖湘文化", "自驳卡", "自驳摘要");

        long reviewId = pendingReviewId(editor, cardId);
        ResponseEntity<String> res = post(editor, "/api/wb/reviews/" + reviewId + "/reject",
                "{\"notes\":\"自己驳回自己\"}");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(res.getBody(), "$.message")).contains("不能审核自己提交的内容");

        // 卡与任务保持 PENDING
        assertThat(jdbc.queryForObject("select status from card where id=?", String.class, cardId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from review_task where id=?", String.class, reviewId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select notes from review_task where id=?", String.class, reviewId)).isNull();
    }

    @Test
    void doubleApproveSecondRejectedWithSingleAudit() {
        // 并发窗口回归：第二次 approve 必须被状态谓词（WHERE status='PENDING'）拦下（400），
        // 而非覆盖式成功——否则会出现双审计行与 reviewer_id 互相覆盖。
        String creator = newUserToken("13800002009", "创作者丁", "CREATOR");
        String editor = newUserToken("13800002010", "编辑戊", "EDITOR");
        long cardId = createAndSubmitCard(creator, "湖湘文化", "并发双审卡", "并发双审摘要");

        long reviewId = pendingReviewId(editor, cardId);
        ResponseEntity<String> first = post(editor, "/api/wb/reviews/" + reviewId + "/approve", "{}");
        assertThat(first.getStatusCode().value()).as("first body=%s", first.getBody()).isEqualTo(200);

        ResponseEntity<String> second = post(editor, "/api/wb/reviews/" + reviewId + "/approve", "{}");
        assertThat(second.getStatusCode().value()).as("second body=%s", second.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(second.getBody(), "$.code")).isEqualTo(400);
        assertThat((String) JsonPath.read(second.getBody(), "$.traceId")).isNotBlank();

        // 已处理任务再 reject 同样 400；卡片仍 PUBLISHED；CARD_PUBLISH 审计恰好一行（无双审计）
        ResponseEntity<String> late = post(editor, "/api/wb/reviews/" + reviewId + "/reject", "{\"notes\":\"迟到的驳回\"}");
        assertThat(late.getStatusCode().value()).as("late reject body=%s", late.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(late.getBody(), "$.code")).isEqualTo(400);
        assertThat(jdbc.queryForObject("select status from card where id=?", String.class, cardId)).isEqualTo("PUBLISHED");
        Integer audits = jdbc.queryForObject(
                "select count(*) from audit_log where action='CARD_PUBLISH' and object_type='CARD' and object_id=?",
                Integer.class, cardId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void queuePaginatesAndPreviewsContent() {
        String creator = newUserToken("13800002021", "创作者庚", "CREATOR");
        String editor = newUserToken("13800002022", "编辑辛", "EDITOR");
        int baseline = pendingTotal(editor);

        // 9 条新 PENDING：前 5 张覆盖四模板 contentPreview 与超长截断，后 4 张补量
        long textCard = createAndSubmitCard(creator, "湖湘文化", "预检卡", "千年学府简介");
        long compareCard = createCard(creator, "湖湘文化", "对比卡", "COMPARE",
                "{\"objects\":[\"岳麓书院\",\"石鼓书院\"],\"dimensions\":[\"始建年代\",\"地位影响\"],"
                        + "\"cells\":[[\"976\",\"古代四大书院\"],[\"805\",\"六大书院之列\"]]}");
        long timelineCard = createCard(creator, "湖湘文化", "年表卡", "TIMELINE",
                "{\"events\":[{\"year\":\"976\",\"title\":\"岳麓书院创建\"},{\"year\":\"1015\",\"title\":\"真宗赐书匾\"}]}");
        long taskCard = createCard(creator, "湖湘文化", "任务卡", "TASK",
                "{\"goal\":\"完成书院考察\",\"steps\":[{\"place\":\"岳麓书院\",\"observe\":\"记录建筑布局\",\"minutes\":60}],"
                        + "\"recordSchema\":[\"考察点\"]}");
        String summary120 = "长".repeat(120);
        long longCard = createCard(creator, "湖湘文化", "长摘要卡", "TEXT",
                "{\"summary\":\"" + summary120 + "\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\"}],\"related\":[]}");
        for (int i = 1; i <= 4; i++) {
            createAndSubmitCard(creator, "湖湘文化", "补量卡" + i, "补量摘要" + i);
        }
        int expectedTotal = baseline + 9;

        // 第 1 页 size=5：items=5、total=9+基线、page/size 回显
        ResponseEntity<String> page1 = http.exchange("/api/wb/reviews?status=PENDING&page=1&size=5", HttpMethod.GET,
                bearer(editor), String.class);
        assertThat(page1.getStatusCode().value()).as("page1 body=%s", page1.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(page1.getBody(), "$.data.page")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(page1.getBody(), "$.data.size")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(page1.getBody(), "$.data.total")).isEqualTo(expectedTotal);
        List<Integer> page1Ids = JsonPath.read(page1.getBody(), "$.data.items[*].id");
        assertThat(page1Ids).hasSize(5);

        // contentPreview 断言取全量页（同类其他用例可能残留 PENDING 任务，页内位置不可钉死）：
        // 审批人不点开就能看到内容大意（TEXT=summary，其余模板取对应摘要）
        ResponseEntity<String> full = http.exchange("/api/wb/reviews?status=PENDING&page=1&size=100", HttpMethod.GET,
                bearer(editor), String.class);
        assertThat((Integer) JsonPath.read(full.getBody(), "$.data.total")).isEqualTo(expectedTotal);
        assertThat(previewOf(full.getBody(), textCard)).isEqualTo("千年学府简介");
        assertThat(previewOf(full.getBody(), compareCard)).isEqualTo("岳麓书院 vs 石鼓书院 · 始建年代、地位影响");
        assertThat(previewOf(full.getBody(), timelineCard)).isEqualTo("976 岳麓书院创建 等 2 条");
        assertThat(previewOf(full.getBody(), taskCard)).isEqualTo("完成书院考察");
        // 超长摘要截 100 字并加省略号
        String longPreview = previewOf(full.getBody(), longCard);
        assertThat(longPreview).hasSize(101).endsWith("…").startsWith("长".repeat(100));

        // 第 2 页：剩余 expectedTotal - 5 条（≤5）
        ResponseEntity<String> page2 = http.exchange("/api/wb/reviews?status=PENDING&page=2&size=5", HttpMethod.GET,
                bearer(editor), String.class);
        assertThat((Integer) JsonPath.read(page2.getBody(), "$.data.total")).isEqualTo(expectedTotal);
        List<Integer> page2Ids = JsonPath.read(page2.getBody(), "$.data.items[*].id");
        assertThat(page2Ids).hasSize(Math.min(5, expectedTotal - 5));

        // 省略 page/size → 默认 page=1、size=20
        ResponseEntity<String> defaults = http.exchange("/api/wb/reviews?status=PENDING", HttpMethod.GET,
                bearer(editor), String.class);
        assertThat((Integer) JsonPath.read(defaults.getBody(), "$.data.page")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(defaults.getBody(), "$.data.size")).isEqualTo(20);

        // 通过其中一条 → 从 PENDING 队列消失（total 9+基线 → 8+基线）
        long reviewId = pendingReviewId(editor, textCard);
        ResponseEntity<String> approved = post(editor, "/api/wb/reviews/" + reviewId + "/approve", "{}");
        assertThat(approved.getStatusCode().value()).as("approve body=%s", approved.getBody()).isEqualTo(200);
        ResponseEntity<String> after = http.exchange("/api/wb/reviews?status=PENDING&page=1&size=100", HttpMethod.GET,
                bearer(editor), String.class);
        assertThat((Integer) JsonPath.read(after.getBody(), "$.data.total")).isEqualTo(expectedTotal - 1);
        List<Integer> afterIds = JsonPath.read(after.getBody(), "$.data.items[*].id");
        assertThat(afterIds).isNotEmpty().doesNotContain((int) reviewId);
        List<Integer> goneObject = JsonPath.read(after.getBody(),
                "$.data.items[?(@.objectId == " + textCard + ")].id");
        assertThat(goneObject).isEmpty();
    }
}
