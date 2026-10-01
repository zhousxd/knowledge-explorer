package com.ke.card;

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
 * 探索端匿名读卡（01 文档「浏览无登录要求」的后端放行面）：
 * GET /api/cards（列表）与 GET /api/cards/{id}（详情，仅 PUBLISHED）匿名放行；
 * GET /api/cards/{id}/entries（按 viewer 过滤公私入口）匿名仍 401；
 * 非 PUBLISHED 卡对匿名 404 envelope（不泄露草稿存在性）。
 *
 * 角色账号：API 注册后经 JdbcTemplate 提权再重新登录（JWT 载荷携带提权后的角色）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class AnonymousCardIT {

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

    private HttpEntity<Void> noAuth() {
        return new HttpEntity<>(new HttpHeaders());
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

    /** 无引用的 TEXT content */
    private static String textContent(String summary) {
        return "{\"summary\":\"" + summary + "\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\"}],\"related\":[]}";
    }

    /** 建卡（TEXT）+ 送审 + 发布，返回 cardId */
    private long publishCard(String creatorToken, String editorToken, String title, String summary) {
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken("{\"theme\":\"湖湘文化\",\"templateType\":\"TEXT\",\"title\":\"" + title
                        + "\",\"content\":" + textContent(summary) + "}", creatorToken), String.class);
        assertThat(created.getStatusCode().value()).as("create body=%s", created.getBody()).isEqualTo(201);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        ResponseEntity<String> submitted = http.exchange("/api/wb/cards/" + cardId + "/submit", HttpMethod.POST,
                jsonWithToken("{}", creatorToken), String.class);
        assertThat(submitted.getStatusCode().value()).as("submit body=%s", submitted.getBody()).isEqualTo(200);
        ResponseEntity<String> published = http.exchange("/api/wb/cards/" + cardId + "/publish", HttpMethod.POST,
                jsonWithToken("{}", editorToken), String.class);
        assertThat(published.getStatusCode().value()).as("publish body=%s", published.getBody()).isEqualTo(200);
        return cardId;
    }

    // ---------- scenarios ----------

    @Test
    void anonymousCanBrowsePublishedCardsButNotEntries() {
        String creator = newUserToken("13800002001", "匿名创作者", "CREATOR");
        String editor = newUserToken("13800002002", "匿名编辑", "EDITOR");
        long cardId = publishCard(creator, editor, "匿名浏览的岳麓书院", "匿名可见摘要");

        // 无 token 列表 → 200 envelope，含已发布卡
        ResponseEntity<String> list = http.exchange("/api/cards", HttpMethod.GET, noAuth(), String.class);
        assertThat(list.getStatusCode().value()).as("list body=%s", list.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(list.getBody(), "$.code")).isZero();
        assertThat((String) JsonPath.read(list.getBody(), "$.traceId")).isNotBlank();
        var ids = JsonPath.<java.util.List<Integer>>read(list.getBody(), "$.data.items[*].id");
        assertThat(ids).contains((int) cardId);
        // 匿名列表行同样带 summaryText（发布回填）
        var summaries = JsonPath.<java.util.List<String>>read(
                list.getBody(), "$.data.items[?(@.id == " + cardId + ")].summaryText");
        assertThat(summaries).containsExactly("匿名可见摘要");

        // 无 token 详情 → 200 envelope，content 内嵌对象
        ResponseEntity<String> detail = http.exchange("/api/cards/" + cardId, HttpMethod.GET, noAuth(), String.class);
        assertThat(detail.getStatusCode().value()).as("detail body=%s", detail.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(detail.getBody(), "$.code")).isZero();
        assertThat((String) JsonPath.read(detail.getBody(), "$.data.title")).isEqualTo("匿名浏览的岳麓书院");
        assertThat((String) JsonPath.read(detail.getBody(), "$.data.content.summary")).isEqualTo("匿名可见摘要");

        // 无 token 入口列表 → 401 envelope（viewer 过滤的接口不放行）
        ResponseEntity<String> entries = http.exchange("/api/cards/" + cardId + "/entries",
                HttpMethod.GET, noAuth(), String.class);
        assertThat(entries.getStatusCode().value()).as("entries body=%s", entries.getBody()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(entries.getBody(), "$.code")).isEqualTo(401);
        assertThat((String) JsonPath.read(entries.getBody(), "$.message")).contains("未认证");
    }

    @Test
    void anonymousDetailOfDraftIs404Envelope() {
        String creator = newUserToken("13800002003", "草稿创作者", "CREATOR");
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken("{\"theme\":\"湖湘文化\",\"templateType\":\"TEXT\",\"title\":\"匿名不可见草稿\","
                        + "\"content\":" + textContent("草稿摘要") + "}", creator), String.class);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();

        // 非 PUBLISHED 对匿名 404 envelope（与登录态同则，不泄露草稿存在性）
        ResponseEntity<String> detail = http.exchange("/api/cards/" + cardId, HttpMethod.GET, noAuth(), String.class);
        assertThat(detail.getStatusCode().value()).as("detail body=%s", detail.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(detail.getBody(), "$.code")).isEqualTo(404);
        assertThat((String) JsonPath.read(detail.getBody(), "$.message")).contains("卡片不存在");
        assertThat((String) JsonPath.read(detail.getBody(), "$.traceId")).isNotBlank();
    }
}
