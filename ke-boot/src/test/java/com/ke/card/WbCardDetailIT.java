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
 * 工作台单卡读取（编辑器回填用）：GET /api/wb/cards/{id}。
 * 归属与四模板编辑器前置契约：CREATOR 仅可读自己维护的卡（他人卡 403，不泄露存在性）；
 * EDITOR 可读任何卡；DRAFT 卡返回完整 content（内嵌 JSON 对象）与 sources。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class WbCardDetailIT {

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

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    /** 注册（API）→ 经 JdbcTemplate 提权 → 登录拿 accessToken（JWT 载荷携带提权后的角色） */
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

    private long createTextCard(String creatorToken, String theme, String title) {
        String content = "{\"summary\":\"" + title + "摘要\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\"}],\"related\":[]}";
        String body = "{\"theme\":\"" + theme + "\",\"templateType\":\"TEXT\",\"title\":\"" + title
                + "\",\"content\":" + content
                + ",\"sources\":[{\"assetId\":null,\"title\":\"《测试出处》\",\"locator\":\"第1页\",\"license\":null}]}";
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards", jsonWithToken(body, creatorToken), String.class);
        assertThat(created.getStatusCode().value()).as("body=%s", created.getBody()).isEqualTo(201);
        return ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
    }

    private HttpEntity<String> jsonWithToken(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    // ---------- scenarios ----------

    @Test
    void creatorReadingOthersCardForbidden() {
        String owner = newUserToken("13800003001", "单卡卡主", "CREATOR");
        String stranger = newUserToken("13800003002", "单卡路人", "CREATOR");
        long cardId = createTextCard(owner, "湖湘文化", "单卡归属卡");

        // 非维护者的 CREATOR → 403 envelope（归属校验复用 assertWritable 语义）
        ResponseEntity<String> denied = http.exchange("/api/wb/cards/{id}", HttpMethod.GET,
                bearer(stranger), String.class, cardId);
        assertThat(denied.getStatusCode().value()).as("body=%s", denied.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(denied.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(denied.getBody(), "$.traceId")).isNotBlank();
    }

    @Test
    void editorCanReadAnyCard() {
        String creator = newUserToken("13800003003", "单卡创作者", "CREATOR");
        String editor = newUserToken("13800003004", "单卡编辑", "EDITOR");
        long cardId = createTextCard(creator, "湖湘文化", "单卡编辑可读卡");

        ResponseEntity<String> res = http.exchange("/api/wb/cards/{id}", HttpMethod.GET,
                bearer(editor), String.class, cardId);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.id")).isEqualTo((int) cardId);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.title")).isEqualTo("单卡编辑可读卡");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.theme")).isEqualTo("湖湘文化");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.templateType")).isEqualTo("TEXT");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.status")).isEqualTo("DRAFT");
    }

    @Test
    void draftCardReturnsFullContentAndSources() {
        String creator = newUserToken("13800003005", "单卡草稿主", "CREATOR");
        long cardId = createTextCard(creator, "湖湘文化", "单卡草稿卡");

        ResponseEntity<String> res = http.exchange("/api/wb/cards/{id}", HttpMethod.GET,
                bearer(creator), String.class, cardId);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);

        // content 为内嵌对象（wire format 非 contentJson 字符串），summary 与 sections 完整
        assertThat((String) JsonPath.read(res.getBody(), "$.data.content.summary")).isEqualTo("单卡草稿卡摘要");
        assertThat((int) JsonPath.read(res.getBody(), "$.data.content.sections.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.content.sections[0].h")).isEqualTo("缘起");
        // sources 数组原样返回（citations 1-based 索引指向它）
        assertThat((int) JsonPath.read(res.getBody(), "$.data.sources.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.sources[0].title")).isEqualTo("《测试出处》");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.sources[0].locator")).isEqualTo("第1页");
    }

    @Test
    void missingCardNotFound() {
        String editor = newUserToken("13800003006", "单卡缺失编辑", "EDITOR");
        ResponseEntity<String> res = http.exchange("/api/wb/cards/{id}", HttpMethod.GET,
                bearer(editor), String.class, 999999);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(404);
    }
}
