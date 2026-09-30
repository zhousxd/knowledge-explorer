package com.ke.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
 * 卡片生命周期与不可变版本（FR-C07/C08）端到端：
 * CREATOR 建卡（DRAFT + version 1）→ submit（PENDING）→ EDITOR publish（PUBLISHED + 回填
 * summary_text + current_version_id）→ 公开端点可见；PUT content 只追加新版本不改旧行；
 * 状态流转与角色边界（EXPLORER 不能发布）、非法 content 400、DRAFT 对公开端点不可见、
 * keyset (sort,id) 游标翻页 nextCursor 语义。
 *
 * 角色账号：API 注册后经 JdbcTemplate 提权再重新登录（JWT 载荷携带提权后的角色）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class CardLifecycleIT {

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

    /** 注册（API）→ 非法 EXPLORER 角色经 JdbcTemplate 提权 → 登录拿 accessToken */
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

    private static String createBody(String theme, String title, String contentJson) {
        return "{\"theme\":\"" + theme + "\",\"templateType\":\"TEXT\",\"title\":\"" + title
                + "\",\"contentJson\":" + quote(contentJson) + "}";
    }

    private ResponseEntity<String> post(String token, String path) {
        return http.exchange(path, HttpMethod.POST, jsonWithToken("{}", token), String.class);
    }

    /** 建卡（TEXT）+ 送审 + 发布，返回 cardId */
    private long publishCard(String creatorToken, String editorToken, String theme, String title, String summary) {
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody(theme, title, textContent(summary)), creatorToken), String.class);
        assertThat(created.getStatusCode().value()).as("create body=%s", created.getBody()).isEqualTo(201);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        ResponseEntity<String> submitted = post(creatorToken, "/api/wb/cards/" + cardId + "/submit");
        assertThat(submitted.getStatusCode().value()).as("submit body=%s", submitted.getBody()).isEqualTo(200);
        ResponseEntity<String> published = post(editorToken, "/api/wb/cards/" + cardId + "/publish");
        assertThat(published.getStatusCode().value()).as("publish body=%s", published.getBody()).isEqualTo(200);
        return cardId;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> listIds(String body) {
        return JsonPath.read(body, "$.data.items[*].id");
    }

    // ---------- scenarios ----------

    @Test
    void creatorSubmitEditorPublishFlow() {
        String creator = newUserToken("13800001001", "创作者", "CREATOR");
        String editor = newUserToken("13800001002", "编辑", "EDITOR");

        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("湖湘文化", "岳麓书院", textContent("千年学府简要介绍")), creator), String.class);
        assertThat(created.getStatusCode().value()).as("body=%s", created.getBody()).isEqualTo(201);
        int cardId = JsonPath.read(created.getBody(), "$.data.cardId");

        // DRAFT 阶段公开端点不可见
        ResponseEntity<String> draftList = http.exchange("/api/cards", HttpMethod.GET, bearer(creator), String.class);
        assertThat(draftList.getStatusCode().value()).isEqualTo(200);
        assertThat(listIds(draftList.getBody())).doesNotContain(cardId);

        // submit：DRAFT → PENDING
        ResponseEntity<String> submitted = post(creator, "/api/wb/cards/" + cardId + "/submit");
        assertThat(submitted.getStatusCode().value()).as("body=%s", submitted.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(submitted.getBody(), "$.data.status")).isEqualTo("PENDING");

        // EDITOR publish：PENDING → PUBLISHED，回填 summary_text 与 current_version_id
        ResponseEntity<String> published = post(editor, "/api/wb/cards/" + cardId + "/publish");
        assertThat(published.getStatusCode().value()).as("body=%s", published.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(published.getBody(), "$.data.status")).isEqualTo("PUBLISHED");

        String summary = jdbc.queryForObject("select summary_text from card where id=?", String.class, cardId);
        assertThat(summary).isEqualTo("千年学府简要介绍");
        Long currentVersionId = jdbc.queryForObject("select current_version_id from card where id=?", Long.class, cardId);
        assertThat(currentVersionId).isNotNull();

        // 列表含该卡且带摘要
        ResponseEntity<String> list = http.exchange("/api/cards", HttpMethod.GET, bearer(editor), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(listIds(list.getBody())).contains(cardId);
        List<String> summaries = JsonPath.read(list.getBody(), "$.data.items[?(@.id == " + cardId + ")].summaryText");
        assertThat(summaries).containsExactly("千年学府简要介绍");

        // 详情返回 content 对象（非字符串）与版本号
        ResponseEntity<String> detail = http.exchange("/api/cards/" + cardId, HttpMethod.GET, bearer(editor), String.class);
        assertThat(detail.getStatusCode().value()).as("body=%s", detail.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(detail.getBody(), "$.data.versionNo")).isEqualTo(1);
        assertThat((String) JsonPath.read(detail.getBody(), "$.data.content.summary")).isEqualTo("千年学府简要介绍");
        assertThat((String) JsonPath.read(detail.getBody(), "$.data.title")).isEqualTo("岳麓书院");
        assertThat((String) JsonPath.read(detail.getBody(), "$.data.theme")).isEqualTo("湖湘文化");
    }

    @Test
    void saveContentCreatesNewImmutableVersion() {
        String creator = newUserToken("13800001003", "创作者乙", "CREATOR");
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("书院地标", "卡片版本测试", textContent("第一版摘要")), creator), String.class);
        int cardId = JsonPath.read(created.getBody(), "$.data.cardId");

        String v1Json = jdbc.queryForObject(
                "select content_json::text from card_version where card_id=? and version_no=1", String.class, cardId);
        assertThat(v1Json).contains("第一版摘要");

        for (int v = 2; v <= 3; v++) {
            ResponseEntity<String> saved = http.exchange("/api/wb/cards/" + cardId + "/content", HttpMethod.PUT,
                    jsonWithToken("{\"contentJson\":" + quote(textContent("第" + v + "版摘要")) + "}", creator), String.class);
            assertThat(saved.getStatusCode().value()).as("body=%s", saved.getBody()).isEqualTo(200);
            assertThat((int) JsonPath.read(saved.getBody(), "$.data.versionNo")).isEqualTo(v);
        }

        Integer rows = jdbc.queryForObject("select count(*) from card_version where card_id=?", Integer.class, cardId);
        assertThat(rows).isEqualTo(3);

        // 旧行不可变：version 1 的 content_json 与首版一致
        String v1JsonAfter = jdbc.queryForObject(
                "select content_json::text from card_version where card_id=? and version_no=1", String.class, cardId);
        assertThat(v1JsonAfter).isEqualTo(v1Json);
        // 新版本内容生效
        String v3Summary = jdbc.queryForObject(
                "select content_json->>'summary' from card_version where card_id=? and version_no=3", String.class, cardId);
        assertThat(v3Summary).isEqualTo("第3版摘要");
    }

    @Test
    void explorerCannotPublish() {
        String creator = newUserToken("13800001004", "创作者丙", "CREATOR");
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("湖湘文化", "越权测试卡", textContent("越权测试摘要")), creator), String.class);
        int cardId = JsonPath.read(created.getBody(), "$.data.cardId");
        post(creator, "/api/wb/cards/" + cardId + "/submit");

        String explorer = newUserToken("13800001005", "访客", "EXPLORER");
        ResponseEntity<String> denied = post(explorer, "/api/wb/cards/" + cardId + "/publish");
        assertThat(denied.getStatusCode().value()).as("body=%s", denied.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(denied.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(denied.getBody(), "$.traceId")).isNotBlank();

        // 状态未被改动，且 EXPLORER 可正常访问公开端点（探索仅需认证，无角色要求）
        String status = jdbc.queryForObject("select status from card where id=?", String.class, cardId);
        assertThat(status).isEqualTo("PENDING");
        ResponseEntity<String> list = http.exchange("/api/cards", HttpMethod.GET, bearer(explorer), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void submitFromPublishedRejected() {
        String creator = newUserToken("13800001006", "创作者丁", "CREATOR");
        String editor = newUserToken("13800001007", "编辑乙", "EDITOR");
        long cardId = publishCard(creator, editor, "湖湘文化", "重复送审卡", "重复送审摘要");

        ResponseEntity<String> resubmit = post(creator, "/api/wb/cards/" + cardId + "/submit");
        assertThat(resubmit.getStatusCode().value()).as("body=%s", resubmit.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(resubmit.getBody(), "$.code")).isEqualTo(400);
        assertThat((String) JsonPath.read(resubmit.getBody(), "$.traceId")).isNotBlank();

        String status = jdbc.queryForObject("select status from card where id=?", String.class, cardId);
        assertThat(status).isEqualTo("PUBLISHED");
    }

    @Test
    void invalidContentRejected() {
        String creator = newUserToken("13800001008", "创作者戊", "CREATOR");
        // summary 121 字，超过 TEXT 模板 @Size(max=120) 上限
        String bad = "{\"summary\":\"" + "字".repeat(121)
                + "\",\"sections\":[{\"h\":\"h\",\"body\":\"b\"}],\"related\":[]}";
        ResponseEntity<String> res = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("湖湘文化", "非法内容卡", bad), creator), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(400);
        assertThat((String) JsonPath.read(res.getBody(), "$.message")).contains("summary");
        assertThat((String) JsonPath.read(res.getBody(), "$.traceId")).isNotBlank();
        // 未落任何库表
        Integer cards = jdbc.queryForObject("select count(*) from card where title=?", Integer.class, "非法内容卡");
        assertThat(cards).isZero();
        Integer versions = jdbc.queryForObject("select count(*) from card_version", Integer.class);
        assertThat(versions).isZero();
    }

    @Test
    void draftCardInvisibleOnPublicEndpoints() {
        String creator = newUserToken("13800001009", "创作者己", "CREATOR");
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("湖湘文化", "隐藏草稿卡", textContent("草稿摘要")), creator), String.class);
        int cardId = JsonPath.read(created.getBody(), "$.data.cardId");

        ResponseEntity<String> detail = http.exchange("/api/cards/" + cardId, HttpMethod.GET, bearer(creator), String.class);
        assertThat(detail.getStatusCode().value()).as("body=%s", detail.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(detail.getBody(), "$.code")).isEqualTo(404);

        ResponseEntity<String> list = http.exchange("/api/cards", HttpMethod.GET, bearer(creator), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(listIds(list.getBody())).doesNotContain(cardId);
    }

    @Test
    void searchByTitleAndCursor() {
        String creator = newUserToken("13800001010", "创作者庚", "CREATOR");
        String editor = newUserToken("13800001011", "编辑丙", "EDITOR");
        long c1 = publishCard(creator, editor, "湖湘书院", "岳麓书院", "千年学府");
        long c2 = publishCard(creator, editor, "湖湘书院", "爱晚亭", "爱晚亭秋景");
        long c3 = publishCard(creator, editor, "湖湘书院", "橘子洲头", "橘子洲头风光");
        long otherTheme = publishCard(creator, editor, "其他专题", "另一个专题的岳麓书院故事", "干扰摘要");

        // 不同 sort：c3 > c2 > c1，验证 (sort,id) 升序
        jdbc.update("update card set sort=30 where id=?", c3);
        jdbc.update("update card set sort=20 where id=?", c2);
        jdbc.update("update card set sort=10 where id=?", c1);
        // 补 9 张已发布（绕过 API，仅造翻页数据），专题内共 12 张 > 每页 10
        for (int i = 0; i < 9; i++) {
            jdbc.update("insert into card(theme,template_type,title,status,summary_text,sort) values(?,?,?,?,?,?)",
                    "湖湘书院", "TEXT", "批量卡" + i, "PUBLISHED", "批量摘要" + i, 100 + i);
        }

        // q 命中 title：专题内仅爱晚亭
        ResponseEntity<String> byTitle = http.exchange("/api/cards?theme={theme}&q={q}", HttpMethod.GET,
                bearer(creator), String.class, "湖湘书院", "爱晚亭");
        assertThat(byTitle.getStatusCode().value()).isEqualTo(200);
        assertThat(listIds(byTitle.getBody())).containsExactly((int) c2);

        // 首页 10 条按 (sort,id) 升序，theme 过滤排除其他专题（12 张主题内卡：首页 10 + 翻页 2）
        ResponseEntity<String> page1 = http.exchange("/api/cards?theme={theme}", HttpMethod.GET,
                bearer(creator), String.class, "湖湘书院");
        List<Integer> page1Ids = listIds(page1.getBody());
        assertThat(page1Ids).hasSize(10).doesNotContain((int) otherTheme);
        assertThat(page1Ids.get(0)).isEqualTo((int) c1);
        assertThat(page1Ids.get(1)).isEqualTo((int) c2);
        assertThat(page1Ids.get(2)).isEqualTo((int) c3);
        String nextCursor = JsonPath.read(page1.getBody(), "$.data.nextCursor");
        assertThat(nextCursor).isNotBlank();

        // 翻页：剩余 2 条且 nextCursor 为空（没有更多）
        ResponseEntity<String> page2 = http.exchange("/api/cards?theme={theme}&cursor={cursor}", HttpMethod.GET,
                bearer(creator), String.class, "湖湘书院", nextCursor);
        List<Integer> page2Ids = listIds(page2.getBody());
        assertThat(page2Ids).hasSize(2);
        assertThat(page2Ids).doesNotContainAnyElementsOf(page1Ids);
        Object page2Cursor = JsonPath.read(page2.getBody(), "$.data.nextCursor");
        assertThat(page2Cursor).isNull();
    }
}
