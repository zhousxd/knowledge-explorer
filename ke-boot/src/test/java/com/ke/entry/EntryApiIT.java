package com.ke.entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.ke.infra.entity.EntryEntity;
import com.ke.service.common.BadRequestException;
import com.ke.service.entry.EntryService;
import com.ke.support.ItDb;

/**
 * 入口只读 API 与类型白名单（FR-E02/C09/N07）：
 * - GET /api/cards/{id}/entries：仅 PUBLISHED 卡可见（404 不泄露）；仅 ACTIVE 入口；
 *   PUBLIC 全可见 + PRIVATE 仅创建者本人（FR-N04）；(sort,id) 升序，前 5 进 defaultEntries、其余 folded；
 *   每项带 mine 标记；不外泄 config_json（试运行在 Phase 6 提供写端点）。
 * - 写端点本任务不存在：白名单守卫 EntryService.checkInsertable 以服务层直调断言（单元级）。
 * 造数据：JdbcTemplate 直插 entry 行；卡片直插 card + card_version 最小行再置 PUBLISHED。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class EntryApiIT {

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

    /** 注册（EXPLORER 默认角色即可，读端点仅要求认证）+ 登录拿 accessToken */
    private String newUserToken(String phone, String nickname) {
        ResponseEntity<String> reg = http.postForEntity("/api/auth/register",
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\",\"nickname\":\"" + nickname + "\"}"),
                String.class);
        assertThat(reg.getStatusCode().value()).as("register body=%s", reg.getBody()).isEqualTo(201);
        ResponseEntity<String> login = http.postForEntity("/api/auth/login",
                json("{\"phone\":\"" + phone + "\",\"password\":\"passw0rd!\"}"), String.class);
        assertThat(login.getStatusCode().value()).as("login body=%s", login.getBody()).isEqualTo(200);
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    private long userId(String phone) {
        return jdbc.queryForObject("select id from ke_user where phone=?", Long.class, phone);
    }

    /** 直插一张已发布卡：card PUBLISHED + card_version 最小 content 行 + current_version_id 回填 */
    private long publishedCard(String title) {
        jdbc.update("insert into card(theme,template_type,title,status,summary_text,sort) values(?,?,?,?,?,?)",
                "湖湘文化", "TEXT", title, "PUBLISHED", title + "摘要", 0);
        long cardId = jdbc.queryForObject("select id from card where title=?", Long.class, title);
        jdbc.update("insert into card_version(card_id,version_no,content_json) values(?,1,?)", cardId,
                "{\"summary\":\"" + title + "摘要\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文。\"}],\"related\":[]}");
        jdbc.update("update card set current_version_id=(select id from card_version where card_id=? and version_no=1) "
                + "where id=?", cardId, cardId);
        return cardId;
    }

    /** 直插入口行，返回生成 id（title/name 由调用方保证类内唯一） */
    private long insertEntry(long cardId, long authorId, String name, String type, String relationLabel,
                             Long targetCardId, String scope, String status, int sort) {
        return jdbc.queryForObject("insert into entry(card_id,name,type,relation_label,target_card_id,scope,status,"
                + "author_id,sort) values(?,?,?,?,?,?,?,?,?) returning id",
                Long.class, cardId, name, type, relationLabel, targetCardId, scope, status, authorId, sort);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> ids(String body, String path) {
        return JsonPath.read(body, path);
    }

    // ---------- 场景 ----------

    @Test
    void entriesGroupedFivePlusFolded() {
        String token = newUserToken("13800006001", "探索者甲");
        long authorId = userId("13800006001");
        long card = publishedCard("入口分组卡");
        long target = publishedCard("入口目标卡");

        List<Long> entryIds = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            entryIds.add(insertEntry(card, authorId, "入口" + i, "LINK_CARD", "深入了解", target, "PUBLIC", "ACTIVE", i));
        }
        // DISABLED 不出现在任何分组（仅 ACTIVE 可见）
        insertEntry(card, authorId, "停用入口", "LINK_CARD", "相关联", target, "PUBLIC", "DISABLED", 8);

        ResponseEntity<String> res = http.exchange("/api/cards/" + card + "/entries", HttpMethod.GET,
                bearer(token), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.cardId")).isEqualTo((int) card);

        List<Integer> defaultIds = ids(res.getBody(), "$.data.defaultEntries[*].id");
        List<Integer> foldedIds = ids(res.getBody(), "$.data.folded[*].id");
        assertThat(defaultIds).hasSize(5);
        assertThat(foldedIds).hasSize(2);
        // (sort,id) 升序：sort 0-4 进 defaultEntries，sort 5-6 进 folded，DISABLED 与停用入口不出现
        assertThat(defaultIds).containsExactly(
                entryIds.subList(0, 5).stream().map(Long::intValue).toArray(Integer[]::new));
        assertThat(foldedIds).containsExactly(
                entryIds.subList(5, 7).stream().map(Long::intValue).toArray(Integer[]::new));

        // 条目字段形状：不外泄 config_json；mine 标记作者
        assertThat((String) JsonPath.read(res.getBody(), "$.data.defaultEntries[0].name")).isEqualTo("入口0");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.defaultEntries[0].type")).isEqualTo("LINK_CARD");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.defaultEntries[0].relationLabel")).isEqualTo("深入了解");
        assertThat((int) JsonPath.read(res.getBody(), "$.data.defaultEntries[0].targetCardId")).isEqualTo((int) target);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.defaultEntries[0].scope")).isEqualTo("PUBLIC");
        assertThat((String) JsonPath.read(res.getBody(), "$.data.defaultEntries[0].status")).isEqualTo("ACTIVE");
        assertThat((Boolean) JsonPath.read(res.getBody(), "$.data.defaultEntries[0].mine")).isTrue();
        assertThat(res.getBody()).doesNotContain("configJson").doesNotContain("config_json");
    }

    @Test
    void privateEntriesVisibleOnlyToOwner() {
        String tokenA = newUserToken("13800006003", "探索者乙");
        String tokenB = newUserToken("13800006004", "探索者丙");
        long authorA = userId("13800006003");
        long card = publishedCard("私人入口卡");

        long publicId = insertEntry(card, authorA, "公开入口", "LINK_CARD", "相关联", null, "PUBLIC", "ACTIVE", 1);
        long privateId = insertEntry(card, authorA, "私人入口", "AGENT_SERVICE", null, null, "PRIVATE", "ACTIVE", 0);

        // 用户A（创建者）：PUBLIC + PRIVATE 都可见，PRIVATE 因 sort=0 排前；两个都是自己创建 → mine=true
        ResponseEntity<String> resA = http.exchange("/api/cards/" + card + "/entries", HttpMethod.GET,
                bearer(tokenA), String.class);
        assertThat(resA.getStatusCode().value()).as("body=%s", resA.getBody()).isEqualTo(200);
        assertThat(ids(resA.getBody(), "$.data.defaultEntries[*].id"))
                .containsExactly((int) privateId, (int) publicId);
        assertThat((Boolean) JsonPath.read(resA.getBody(), "$.data.defaultEntries[0].mine")).isTrue();
        assertThat((Boolean) JsonPath.read(resA.getBody(), "$.data.defaultEntries[1].mine")).isTrue();

        // 用户B：只见 PUBLIC，且非自己创建 → mine=false
        ResponseEntity<String> resB = http.exchange("/api/cards/" + card + "/entries", HttpMethod.GET,
                bearer(tokenB), String.class);
        assertThat(resB.getStatusCode().value()).as("body=%s", resB.getBody()).isEqualTo(200);
        assertThat(ids(resB.getBody(), "$.data.defaultEntries[*].id")).containsExactly((int) publicId);
        assertThat((Boolean) JsonPath.read(resB.getBody(), "$.data.defaultEntries[0].mine")).isFalse();
    }

    @Test
    void draftCardEntries404() {
        String token = newUserToken("13800006005", "探索者丁");
        jdbc.update("insert into card(theme,template_type,title,status,summary_text,sort) values(?,?,?,?,?,?)",
                "湖湘文化", "TEXT", "草稿入口卡", "DRAFT", "草稿摘要", 0);
        long cardId = jdbc.queryForObject("select id from card where title=?", Long.class, "草稿入口卡");
        insertEntry(cardId, userId("13800006005"), "草稿卡上的入口", "LINK_CARD", "去实践", null, "PUBLIC", "ACTIVE", 0);

        ResponseEntity<String> res = http.exchange("/api/cards/" + cardId + "/entries", HttpMethod.GET,
                bearer(token), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(404);
        assertThat((String) JsonPath.read(res.getBody(), "$.traceId")).isNotBlank();
    }

    /** 白名单守卫（单元级，不走 HTTP）：EntryType 三类之外的 type 直接被 Service 拒绝 */
    @Test
    void guardRejectsInvalidType() {
        EntryEntity bad = new EntryEntity();
        bad.setName("非法入口");
        bad.setType("MAP");
        bad.setScope("PUBLIC");
        bad.setStatus("ACTIVE");

        assertThatThrownBy(() -> EntryService.checkInsertable(bad))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("类型");
    }

    @Test
    void guardRequiresRelationForLinkCard() {
        EntryEntity missing = new EntryEntity();
        missing.setName("链接入口");
        missing.setType("LINK_CARD");
        missing.setScope("PUBLIC");
        missing.setStatus("ACTIVE");
        missing.setRelationLabel(null);

        assertThatThrownBy(() -> EntryService.checkInsertable(missing))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("关系标签");

        missing.setRelationLabel("深入了解");
        assertThatCode(() -> EntryService.checkInsertable(missing)).doesNotThrowAnyException();

        // 白名单外的关系词同样拒绝（C09：四词之外非法）
        EntryEntity wrong = new EntryEntity();
        wrong.setName("链接入口乙");
        wrong.setType("LINK_CARD");
        wrong.setScope("PRIVATE");
        wrong.setStatus("ACTIVE");
        wrong.setRelationLabel("相似");
        assertThatThrownBy(() -> EntryService.checkInsertable(wrong))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("关系标签");
    }

    @Test
    void unauthenticated401() {
        long card = publishedCard("匿名入口卡");
        ResponseEntity<String> res = http.getForEntity("/api/cards/" + card + "/entries", String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(401);
        assertThat((String) JsonPath.read(res.getBody(), "$.traceId")).isNotBlank();
    }
}
