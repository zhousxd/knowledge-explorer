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

    /** 无引用的 TEXT content（sources 契约下空 sources 不允许 citations） */
    private static String textContent(String summary) {
        return "{\"summary\":\"" + summary + "\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\"}],\"related\":[]}";
    }

    /** 带 citations 的 TEXT content（citationsJson 形如 "[1,2]"，1-based 指向 sources） */
    private static String textContentWithCitations(String summary, String citationsJson) {
        return "{\"summary\":\"" + summary + "\",\"sections\":[{\"h\":\"缘起\",\"body\":\"正文内容。\",\"citations\":"
                + citationsJson + "}],\"related\":[]}";
    }

    /** content 为内嵌 JSON 对象（sources 契约），不再是 contentJson 字符串 */
    private static String createBody(String theme, String title, String contentJson) {
        return createBody(theme, title, contentJson, null);
    }

    private static String createBody(String theme, String title, String contentJson, String sourcesJson) {
        return "{\"theme\":\"" + theme + "\",\"templateType\":\"TEXT\",\"title\":\"" + title
                + "\",\"content\":" + contentJson
                + (sourcesJson == null ? "" : ",\"sources\":" + sourcesJson) + "}";
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
                    jsonWithToken("{\"content\":" + textContent("第" + v + "版摘要") + "}", creator), String.class);
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
        Integer versions = jdbc.queryForObject(
                "select count(*) from card_version v join card c on c.id=v.card_id where c.title=?",
                Integer.class, "非法内容卡");
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

    @Test
    void sourcesContractEnforcedAndCitationsLinkedOnPublish() {
        String creator = newUserToken("13800001012", "创作者壬", "CREATOR");
        String editor = newUserToken("13800001013", "编辑丁", "EDITOR");
        // 真实知识单元（直插库，供 assetId 校验与 citation 挂接）
        jdbc.update("insert into knowledge_asset(kind,title,locator) values('book','《出处测试书》','{\"pages\":\"12-14\"}')");
        long assetId = jdbc.queryForObject("select id from knowledge_asset where title='《出处测试书》'", Long.class);

        String content = textContentWithCitations("来源契约摘要", "[2]");
        String sources = "[{\"assetId\":" + assetId + ",\"title\":\"《出处测试书》\",\"locator\":\"第12页\",\"license\":\"已授权\"},"
                + "{\"assetId\":null,\"title\":\"口述访谈\",\"locator\":\"录音 03:00\",\"license\":null}]";
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("湖湘文化", "来源契约卡", content, sources), creator), String.class);
        assertThat(created.getStatusCode().value()).as("body=%s", created.getBody()).isEqualTo(201);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        ResponseEntity<String> published = post(creator, "/api/wb/cards/" + cardId + "/submit");
        assertThat(published.getStatusCode().value()).as("body=%s", published.getBody()).isEqualTo(200);
        post(editor, "/api/wb/cards/" + cardId + "/publish");

        // citation 恰 1 行（仅第 1 个 source 带 assetId）：asset、locator={"ref":…}
        Long versionId = jdbc.queryForObject("select current_version_id from card where id=?", Long.class, cardId);
        Integer citationRows = jdbc.queryForObject(
                "select count(*) from citation where object_type='card_version' and object_id=?", Integer.class, versionId);
        assertThat(citationRows).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select asset_id from citation where object_type='card_version' and object_id=?", Long.class, versionId))
                .isEqualTo(assetId);
        String locator = jdbc.queryForObject(
                "select locator::text from citation where object_type='card_version' and object_id=?", String.class, versionId);
        assertThat(locator).contains("\"ref\"").contains("第12页");

        // GET /api/cards/{id}：sources 长度 2（canonical 形态，assetId/占位来源均在）
        ResponseEntity<String> detail = http.exchange("/api/cards/" + cardId, HttpMethod.GET, bearer(creator), String.class);
        assertThat(detail.getStatusCode().value()).as("body=%s", detail.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(detail.getBody(), "$.data.sources.length()")).isEqualTo(2);
        assertThat((int) JsonPath.read(detail.getBody(), "$.data.sources[0].assetId")).isEqualTo((int) assetId);
        assertThat((String) JsonPath.read(detail.getBody(), "$.data.sources[1].title")).isEqualTo("口述访谈");

        // citations=[2] 合法（指向第 2 个来源，1-based）→ 追加第 2 版成功
        ResponseEntity<String> ok = http.exchange("/api/wb/cards/" + cardId + "/content", HttpMethod.PUT,
                jsonWithToken("{\"content\":" + textContentWithCitations("第二版摘要", "[2]")
                        + ",\"sources\":" + sources + "}", creator), String.class);
        assertThat(ok.getStatusCode().value()).as("body=%s", ok.getBody()).isEqualTo(200);

        // citations=[3] 越界（仅 2 个来源）→ 400 带消息
        ResponseEntity<String> outOfRange = http.exchange("/api/wb/cards/" + cardId + "/content", HttpMethod.PUT,
                jsonWithToken("{\"content\":" + textContentWithCitations("越界摘要", "[3]")
                        + ",\"sources\":" + sources + "}", creator), String.class);
        assertThat(outOfRange.getStatusCode().value()).as("body=%s", outOfRange.getBody()).isEqualTo(400);
        assertThat((String) JsonPath.read(outOfRange.getBody(), "$.message")).contains("超出来源范围");

        // sources 为空而 content 带 citations → 同样拒绝
        ResponseEntity<String> noSources = http.exchange("/api/wb/cards/" + cardId + "/content", HttpMethod.PUT,
                jsonWithToken("{\"content\":" + textContentWithCitations("无来源摘要", "[1]") + "}", creator), String.class);
        assertThat(noSources.getStatusCode().value()).as("body=%s", noSources.getBody()).isEqualTo(400);

        // assetId=9999（不存在）→ 400
        String badSources = "[{\"assetId\":9999,\"title\":\"幽灵书\",\"locator\":\"第1页\",\"license\":null}]";
        ResponseEntity<String> badAsset = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("湖湘文化", "幽灵来源卡", textContent("幽灵摘要"), badSources), creator), String.class);
        assertThat(badAsset.getStatusCode().value()).as("body=%s", badAsset.getBody()).isEqualTo(400);
        assertThat((String) JsonPath.read(badAsset.getBody(), "$.message")).contains("知识单元不存在");
    }

    @Test
    void versionsListed() {
        String creator = newUserToken("13800001014", "创作者癸", "CREATOR");
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("书院地标", "版本历史卡", textContent("第一版摘要")), creator), String.class);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        for (int v = 2; v <= 3; v++) {
            http.exchange("/api/wb/cards/" + cardId + "/content", HttpMethod.PUT,
                    jsonWithToken("{\"content\":" + textContent("第" + v + "版摘要") + "}", creator), String.class);
        }

        ResponseEntity<String> res = http.exchange("/api/wb/cards/" + cardId + "/versions",
                HttpMethod.GET, bearer(creator), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.length()")).isEqualTo(3);
        // versionNo 倒序
        List<Integer> versionNos = JsonPath.read(res.getBody(), "$.data[*].versionNo");
        assertThat(versionNos).containsExactly(3, 2, 1);
        List<String> nicknames = JsonPath.read(res.getBody(), "$.data[*].createdByNickname");
        assertThat(nicknames).containsOnly("创作者癸");
        List<String> createdAts = JsonPath.read(res.getBody(), "$.data[*].createdAt");
        assertThat(createdAts).hasSize(3).doesNotContainNull();
    }

    @Test
    void creatorCannotEditOthersCard() {
        String owner = newUserToken("13800001015", "卡主创作者", "CREATOR");
        String stranger = newUserToken("13800001016", "路人创作者", "CREATOR");
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("湖湘文化", "他人的卡", textContent("归属测试摘要")), owner), String.class);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();

        // 非维护者的 CREATOR：存内容 → 403
        ResponseEntity<String> editDenied = http.exchange("/api/wb/cards/" + cardId + "/content", HttpMethod.PUT,
                jsonWithToken("{\"content\":" + textContent("越权改写") + "}", stranger), String.class);
        assertThat(editDenied.getStatusCode().value()).as("body=%s", editDenied.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(editDenied.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(editDenied.getBody(), "$.traceId")).isNotBlank();

        ResponseEntity<String> submitDenied = post(stranger, "/api/wb/cards/" + cardId + "/submit");
        assertThat(submitDenied.getStatusCode().value()).as("body=%s", submitDenied.getBody()).isEqualTo(403);
        ResponseEntity<String> disableDenied = post(stranger, "/api/wb/cards/" + cardId + "/disable");
        assertThat(disableDenied.getStatusCode().value()).as("body=%s", disableDenied.getBody()).isEqualTo(403);

        // 状态与版本均未被改动
        String status = jdbc.queryForObject("select status from card where id=?", String.class, cardId);
        assertThat(status).isEqualTo("DRAFT");
        Integer versions = jdbc.queryForObject("select count(*) from card_version where card_id=?", Integer.class, cardId);
        assertThat(versions).isEqualTo(1);

        // 维护者本人不受影响
        ResponseEntity<String> ownEdit = http.exchange("/api/wb/cards/" + cardId + "/content", HttpMethod.PUT,
                jsonWithToken("{\"content\":" + textContent("本人改写") + "}", owner), String.class);
        assertThat(ownEdit.getStatusCode().value()).as("body=%s", ownEdit.getBody()).isEqualTo(200);
    }

    @Test
    void editorCannotSelfPublishDirectly() {
        String editor = newUserToken("13800001017", "自发编辑", "EDITOR");
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("湖湘文化", "自发卡", textContent("自发自审摘要")), editor), String.class);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        post(editor, "/api/wb/cards/" + cardId + "/submit");

        // 维护者本人直接 publish → 403（自审禁绝）
        ResponseEntity<String> denied = post(editor, "/api/wb/cards/" + cardId + "/publish");
        assertThat(denied.getStatusCode().value()).as("body=%s", denied.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(denied.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(denied.getBody(), "$.message")).contains("不能发布自己提交的内容");

        // 卡保持 PENDING，PENDING 审核任务未被关闭
        assertThat(jdbc.queryForObject("select status from card where id=?", String.class, cardId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "select status from review_task where object_type='CARD' and object_id=?", String.class, cardId))
                .isEqualTo("PENDING");
    }

    @Test
    void directPublishClosesPendingTask() {
        String creator = newUserToken("13800001018", "排队创作者", "CREATOR");
        String editor = newUserToken("13800001019", "直发编辑", "EDITOR");
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards",
                jsonWithToken(createBody("湖湘文化", "直发闭环卡", textContent("直发闭环摘要")), creator), String.class);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        post(creator, "/api/wb/cards/" + cardId + "/submit");
        long taskId = jdbc.queryForObject(
                "select id from review_task where object_type='CARD' and object_id=?", Long.class, cardId);

        // 非维护者的 EDITOR 直接 publish：卡发布 + PENDING 任务同事务关闭为 APPROVED
        ResponseEntity<String> published = post(editor, "/api/wb/cards/" + cardId + "/publish");
        assertThat(published.getStatusCode().value()).as("body=%s", published.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(published.getBody(), "$.data.status")).isEqualTo("PUBLISHED");

        Long editorId = jdbc.queryForObject("select id from ke_user where phone=?", Long.class, "13800001019");
        var task = jdbc.queryForMap("select status, reviewer_id, notes from review_task where id=?", taskId);
        assertThat(task.get("status")).isEqualTo("APPROVED");
        assertThat(((Number) task.get("reviewer_id")).longValue()).isEqualTo(editorId);
        assertThat(task.get("notes")).isEqualTo("直接发布");
        assertThat(jdbc.queryForObject("select status from card where id=?", String.class, cardId)).isEqualTo("PUBLISHED");

        // 审发行一致：CARD_PUBLISH 审计恰一行
        Integer audits = jdbc.queryForObject(
                "select count(*) from audit_log where action='CARD_PUBLISH' and object_type='CARD' and object_id=?",
                Integer.class, cardId);
        assertThat(audits).isEqualTo(1);
    }
}
