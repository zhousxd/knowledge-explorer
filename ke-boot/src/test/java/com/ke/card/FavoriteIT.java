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
 * 收藏（FR-C10）：POST/DELETE /api/cards/{id}/favorite 与 GET /api/me/favorites。
 * - 仅 PUBLISHED 卡可收藏（草稿/不存在 → 404 envelope，不泄露存在性）；
 * - 幂等：重复收藏返回原收藏不出错（UNIQUE(user_id, card_id) 兜底）；
 * - 认证即可收藏（EXPLORER 角色即可），匿名 → 401 envelope；
 * - 详情响应带 favorited（当前用户已收藏否；匿名 false——公开端点可选身份）。
 *
 * 角色账号：API 注册后经 JdbcTemplate 提权再重新登录（JWT 载荷携带提权后的角色）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class FavoriteIT {

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

    private ResponseEntity<String> postFavorite(String token, long cardId) {
        return http.exchange("/api/cards/" + cardId + "/favorite", HttpMethod.POST,
                token == null ? noAuth() : bearer(token), String.class);
    }

    // ---------- scenarios ----------

    @Test
    void favoriteRoundtrip() {
        String creator = newUserToken("13800015001", "收藏创作者", "CREATOR");
        String editor = newUserToken("13800015002", "收藏编辑", "EDITOR");
        String reader = newUserToken("13800015003", "收藏读者", "EXPLORER");
        long cardId = publishCard(creator, editor, "可收藏的岳麓书院", "收藏往返摘要");

        // 详情初态：未收藏
        ResponseEntity<String> before = http.exchange("/api/cards/" + cardId, HttpMethod.GET, bearer(reader), String.class);
        assertThat(before.getStatusCode().value()).as("before body=%s", before.getBody()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(before.getBody(), "$.data.favorited")).isFalse();

        // 收藏 → 200 {favorited:true}
        ResponseEntity<String> fav = postFavorite(reader, cardId);
        assertThat(fav.getStatusCode().value()).as("fav body=%s", fav.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(fav.getBody(), "$.code")).isZero();
        assertThat((Boolean) JsonPath.read(fav.getBody(), "$.data.favorited")).isTrue();

        // 详情带出 favorited=true（当前用户）；匿名仍 false
        ResponseEntity<String> after = http.exchange("/api/cards/" + cardId, HttpMethod.GET, bearer(reader), String.class);
        assertThat((Boolean) JsonPath.read(after.getBody(), "$.data.favorited")).isTrue();
        ResponseEntity<String> anon = http.exchange("/api/cards/" + cardId, HttpMethod.GET, noAuth(), String.class);
        assertThat(anon.getStatusCode().value()).as("anon body=%s", anon.getBody()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(anon.getBody(), "$.data.favorited")).isFalse();

        // 收藏列表含该卡（created_at DESC，favoritedAt 恒在）
        ResponseEntity<String> list = http.exchange("/api/me/favorites?page=1&size=20", HttpMethod.GET,
                bearer(reader), String.class);
        assertThat(list.getStatusCode().value()).as("list body=%s", list.getBody()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(list.getBody(), "$.data.total")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(list.getBody(), "$.data.page")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(list.getBody(), "$.data.size")).isEqualTo(20);
        assertThat(((Number) JsonPath.read(list.getBody(), "$.data.items[0].cardId")).longValue()).isEqualTo(cardId);
        assertThat((String) JsonPath.read(list.getBody(), "$.data.items[0].title")).isEqualTo("可收藏的岳麓书院");
        assertThat((String) JsonPath.read(list.getBody(), "$.data.items[0].theme")).isEqualTo("湖湘文化");
        assertThat((String) JsonPath.read(list.getBody(), "$.data.items[0].templateType")).isEqualTo("TEXT");
        assertThat((String) JsonPath.read(list.getBody(), "$.data.items[0].summaryText")).isEqualTo("收藏往返摘要");
        assertThat((String) JsonPath.read(list.getBody(), "$.data.items[0].favoritedAt")).isNotBlank();

        // 取消收藏 → {favorited:false}；详情回落 false；列表清空
        ResponseEntity<String> unfav = http.exchange("/api/cards/" + cardId + "/favorite", HttpMethod.DELETE,
                bearer(reader), String.class);
        assertThat(unfav.getStatusCode().value()).as("unfav body=%s", unfav.getBody()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(unfav.getBody(), "$.data.favorited")).isFalse();
        ResponseEntity<String> afterUnfav = http.exchange("/api/cards/" + cardId, HttpMethod.GET, bearer(reader), String.class);
        assertThat((Boolean) JsonPath.read(afterUnfav.getBody(), "$.data.favorited")).isFalse();
        ResponseEntity<String> empty = http.exchange("/api/me/favorites", HttpMethod.GET, bearer(reader), String.class);
        assertThat((Integer) JsonPath.read(empty.getBody(), "$.data.total")).isZero();
        assertThat(JsonPath.<java.util.List<Object>>read(empty.getBody(), "$.data.items")).isEmpty();
    }

    @Test
    void idempotentFavorite() {
        String creator = newUserToken("13800015004", "幂等创作者", "CREATOR");
        String editor = newUserToken("13800015005", "幂等编辑", "EDITOR");
        String reader = newUserToken("13800015006", "幂等读者", "EXPLORER");
        long cardId = publishCard(creator, editor, "幂等收藏的书院", "幂等摘要");

        // 连收藏两次：都 200 且不出错
        ResponseEntity<String> first = postFavorite(reader, cardId);
        assertThat(first.getStatusCode().value()).as("first body=%s", first.getBody()).isEqualTo(200);
        ResponseEntity<String> second = postFavorite(reader, cardId);
        assertThat(second.getStatusCode().value()).as("second body=%s", second.getBody()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(second.getBody(), "$.data.favorited")).isTrue();

        // 库里只有一行（UNIQUE(user_id, card_id) 兜底），列表 total=1
        Integer rows = jdbc.queryForObject("select count(*) from favorite where card_id=?", Integer.class, cardId);
        assertThat(rows).isEqualTo(1);
        ResponseEntity<String> list = http.exchange("/api/me/favorites", HttpMethod.GET, bearer(reader), String.class);
        assertThat((Integer) JsonPath.read(list.getBody(), "$.data.total")).isEqualTo(1);
    }

    @Test
    void favoriteDraftRejected() {
        String creator = newUserToken("13800015007", "草稿收藏创作者", "CREATOR");
        String reader = newUserToken("13800015008", "草稿收藏读者", "EXPLORER");
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken("{\"theme\":\"湖湘文化\",\"templateType\":\"TEXT\",\"title\":\"不可收藏草稿\","
                        + "\"content\":" + textContent("草稿摘要") + "}", creator), String.class);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();

        // 草稿卡收藏 → 404 envelope（与公开详情同则，不泄露存在性）
        ResponseEntity<String> fav = postFavorite(reader, cardId);
        assertThat(fav.getStatusCode().value()).as("fav body=%s", fav.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(fav.getBody(), "$.code")).isEqualTo(404);
        assertThat((String) JsonPath.read(fav.getBody(), "$.message")).contains("卡片不存在");
        assertThat((String) JsonPath.read(fav.getBody(), "$.traceId")).isNotBlank();
    }

    @Test
    void explorerCanFavorite() {
        String creator = newUserToken("13800015009", "探索创作", "CREATOR");
        String editor = newUserToken("13800015010", "探索编辑", "EDITOR");
        String explorer = newUserToken("13800015011", "普通探索者", "EXPLORER");
        long cardId = publishCard(creator, editor, "探索者可收藏", "探索者摘要");

        // 认证即可收藏：普通 EXPLORER 无需任何写路径角色
        ResponseEntity<String> fav = postFavorite(explorer, cardId);
        assertThat(fav.getStatusCode().value()).as("fav body=%s", fav.getBody()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(fav.getBody(), "$.data.favorited")).isTrue();
        ResponseEntity<String> list = http.exchange("/api/me/favorites", HttpMethod.GET, bearer(explorer), String.class);
        assertThat((Integer) JsonPath.read(list.getBody(), "$.data.total")).isEqualTo(1);
    }

    @Test
    void anonymousCannotFavorite() {
        String creator = newUserToken("13800015012", "匿名收藏创作", "CREATOR");
        String editor = newUserToken("13800015013", "匿名收藏编辑", "EDITOR");
        long cardId = publishCard(creator, editor, "匿名不可收藏", "匿名摘要");

        // 无 token POST → 401 envelope（过滤器链层拒绝）
        ResponseEntity<String> fav = postFavorite(null, cardId);
        assertThat(fav.getStatusCode().value()).as("fav body=%s", fav.getBody()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(fav.getBody(), "$.code")).isEqualTo(401);
        assertThat((String) JsonPath.read(fav.getBody(), "$.message")).contains("未认证");

        // 无 token 取消收藏同样 401
        ResponseEntity<String> unfav = http.exchange("/api/cards/" + cardId + "/favorite", HttpMethod.DELETE,
                noAuth(), String.class);
        assertThat(unfav.getStatusCode().value()).as("unfav body=%s", unfav.getBody()).isEqualTo(401);
        // 匿名收藏列表同样 401
        ResponseEntity<String> list = http.exchange("/api/me/favorites", HttpMethod.GET, noAuth(), String.class);
        assertThat(list.getStatusCode().value()).as("list body=%s", list.getBody()).isEqualTo(401);
    }
}
