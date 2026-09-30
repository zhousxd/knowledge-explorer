package com.ke.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.jayway.jsonpath.JsonPath;
import com.ke.support.ItDb;

/**
 * 知识资产批量导入与统一引用（FR-O01/O02）端到端：
 * CSV（UTF-8，表头行 + 数据行）逐行校验入库，非法行跳过并回报（行号+原因）；
 * 授权到期日早于今日的资产在 GET 列表标注 expired=true；citation = 资产+定位器+原文摘录，
 * objectType 白名单（card_version/agent_run）；EXPLORER/CREATOR 不能导入（import 仅 EDITOR/OPERATOR）。
 *
 * 角色账号：API 注册后经 JdbcTemplate 提权再重新登录（JWT 载荷携带提权后的角色）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class AssetImportIT {

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

    /** multipart/form-data 上传 CSV 字节（ByteArrayResource 带 filename） */
    private HttpEntity<MultiValueMap<String, Object>> csvUpload(byte[] bytes, String token) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "assets.csv";
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", resource);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return new HttpEntity<>(form, headers);
    }

    /** 项目种子 CSV（仓库根 seed/，surefire 工作目录为 ke-boot 模块根） */
    private static byte[] seedCsv() throws IOException {
        Path path = Paths.get("..", "seed", "assets-book.csv");
        if (!Files.exists(path)) {
            path = Paths.get("seed", "assets-book.csv");
        }
        return Files.readAllBytes(path);
    }

    private static byte[] csv(String... rows) {
        StringBuilder sb = new StringBuilder("kind,title,source_meta,locator,license,license_expire,content_extract\n");
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> importCsv(byte[] bytes, String token) {
        return http.postForEntity("/api/wb/assets/import", csvUpload(bytes, token), String.class);
    }

    private long soleAssetId(String title) {
        return jdbc.queryForObject("select id from knowledge_asset where title=?", Long.class, title);
    }

    // ---------- scenarios ----------

    @Test
    void importValidCsvPersists() throws IOException {
        String editor = newUserToken("13900001001", "编辑甲", "EDITOR");

        ResponseEntity<String> res = importCsv(seedCsv(), editor);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.imported")).isEqualTo(5);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.skipped")).isZero();
        assertThat((int) JsonPath.read(res.getBody(), "$.data.errors.length()")).isZero();

        Integer rows = jdbc.queryForObject(
                "select count(*) from knowledge_asset where title in (?,?,?,?,?)", Integer.class,
                "《岳麓书院史略》", "《爱晚亭诗文集》", "《岳麓书院学规探析》", "《千年学府讲坛·书院篇》", "《岳麓书院纪录片》");
        assertThat(rows).isEqualTo(5);
        // kind 一律小写，且都在允许枚举内
        var kinds = jdbc.queryForList("select distinct kind from knowledge_asset", String.class);
        assertThat(kinds).containsExactlyInAnyOrder("book", "article", "audio", "video");

        // locator / source_meta 为合法 jsonb（列值可直接取 ->> 运算）
        String chapter = jdbc.queryForObject(
                "select locator->>'chapter' from knowledge_asset where title=?", String.class, "《岳麓书院史略》");
        assertThat(chapter).isEqualTo("第一章");
        String pages = jdbc.queryForObject(
                "select locator->>'pages' from knowledge_asset where title=?", String.class, "《岳麓书院史略》");
        assertThat(pages).isEqualTo("12-14");
        String author = jdbc.queryForObject(
                "select source_meta->>'author' from knowledge_asset where title=?", String.class, "《岳麓书院史略》");
        assertThat(author).isEqualTo("杨慎初");
        LocalDate expire = jdbc.queryForObject(
                "select license_expire from knowledge_asset where title=?", LocalDate.class, "《岳麓书院史略》");
        assertThat(expire).isEqualTo(LocalDate.of(2024, 6, 30));
        // 时间片段定位
        String t = jdbc.queryForObject(
                "select locator->>'t' from knowledge_asset where title like '《千年学府讲坛%'", String.class);
        assertThat(t).isEqualTo("00:12:30-00:15:00");
    }

    @Test
    void importSkipsBadRowsAndReports() {
        String editor = newUserToken("13900001002", "编辑乙", "EDITOR");
        byte[] bad = csv(
                "book,《有效的书》,\"{\"\"author\"\":\"\"某人\"\"}\",\"{\"\"chapter\"\":\"\"第一章\"\"}\",已授权,,有效行内容",
                "magazine,《kind 非法》,\"{\"\"a\"\":\"\"b\"\"}\",\"{\"\"chapter\"\":\"\"第一章\"\"}\",已授权,,kind 不在枚举内",
                "book,《locator 非 JSON》,,不是JSON对象,已授权,,locator 坏行",
                "book,,\"{\"\"a\"\":\"\"b\"\"}\",\"{\"\"chapter\"\":\"\"第一章\"\"}\",已授权,,缺 title");

        ResponseEntity<String> res = importCsv(bad, editor);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.imported")).isEqualTo(1);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.skipped")).isEqualTo(3);
        List<?> errors = JsonPath.read(res.getBody(), "$.data.errors");
        assertThat(errors).hasSize(3);
        // 行号 = 文件物理行号（表头为第 1 行，坏行在第 3/4/5 行）
        assertThat((int) JsonPath.read(res.getBody(), "$.data.errors[0].line")).isEqualTo(3);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.errors[0].reason")).contains("kind");
        assertThat((int) JsonPath.read(res.getBody(), "$.data.errors[1].line")).isEqualTo(4);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.errors[1].reason")).contains("locator");
        assertThat((int) JsonPath.read(res.getBody(), "$.data.errors[2].line")).isEqualTo(5);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.errors[2].reason")).contains("title");

        Integer rows = jdbc.queryForObject(
                "select count(*) from knowledge_asset where title=?", Integer.class, "《有效的书》");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void expiredFlagSet() {
        String editor = newUserToken("13900001003", "编辑丙", "EDITOR");
        importCsv(csv(
                "book,《过期授权刊》,,\"{\"\"pages\"\":\"\"1-2\"\"}\",已授权,2024-01-01,授权已过期的资产",
                "book,《长期授权刊》,,\"{\"\"pages\"\":\"\"3-4\"\"}\",已授权,2099-01-01,授权仍在有效期内"), editor);

        // license_expire 早于今日 → expired=true；未来日期 → false
        ResponseEntity<String> expired = http.exchange("/api/wb/assets?q={q}", HttpMethod.GET,
                bearer(editor), String.class, "过期授权刊");
        assertThat(expired.getStatusCode().value()).isEqualTo(200);
        assertThat((boolean) JsonPath.read(expired.getBody(), "$.data.items[0].expired")).isTrue();

        ResponseEntity<String> future = http.exchange("/api/wb/assets?q={q}", HttpMethod.GET,
                bearer(editor), String.class, "长期授权刊");
        assertThat(future.getStatusCode().value()).isEqualTo(200);
        assertThat((boolean) JsonPath.read(future.getBody(), "$.data.items[0].expired")).isFalse();
        assertThat((int) JsonPath.read(future.getBody(), "$.data.items[0].citationCount")).isZero();
    }

    @Test
    void citationRoundTrip() {
        String editor = newUserToken("13900001004", "编辑丁", "EDITOR");
        importCsv(csv("video,《引用测试片》,\"{\"\"producer\"\":\"\"某台\"\"}\",\"{\"\"t\"\":\"\"00:01:00-00:02:00\"\"}\",已授权,,引用测试内容"), editor);
        long assetId = soleAssetId("《引用测试片》");

        // card_version 引用 → 201
        ResponseEntity<String> created = http.postForEntity("/api/wb/assets/" + assetId + "/citations",
                jsonWithToken("{\"objectType\":\"card_version\",\"objectId\":999,\"quote\":\"千年学府，弦歌不绝\",\"locator\":\"{\\\"chapter\\\":\\\"第一章\\\"}\"}", editor),
                String.class);
        assertThat(created.getStatusCode().value()).as("body=%s", created.getBody()).isEqualTo(201);
        int citationId = JsonPath.read(created.getBody(), "$.data.citationId");

        // agent_run 也在白名单内（Phase 5 预留，先放行）
        ResponseEntity<String> agentCite = http.postForEntity("/api/wb/assets/" + assetId + "/citations",
                jsonWithToken("{\"objectType\":\"agent_run\",\"objectId\":42,\"quote\":\"讲解摘录\"}", editor), String.class);
        assertThat(agentCite.getStatusCode().value()).as("body=%s", agentCite.getBody()).isEqualTo(201);

        // 回读：含 object_type/object_id/quote
        ResponseEntity<String> list = http.exchange("/api/wb/assets/" + assetId + "/citations",
                HttpMethod.GET, bearer(editor), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat((int) JsonPath.read(list.getBody(), "$.data.length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(list.getBody(), "$.data[0].objectType")).isEqualTo("card_version");
        assertThat((int) JsonPath.read(list.getBody(), "$.data[0].objectId")).isEqualTo(999);
        assertThat((String) JsonPath.read(list.getBody(), "$.data[0].quote")).isEqualTo("千年学府，弦歌不绝");
        assertThat((String) JsonPath.read(list.getBody(), "$.data[0].locator.chapter")).isEqualTo("第一章");

        // 非法 objectType → 400 envelope
        ResponseEntity<String> badType = http.postForEntity("/api/wb/assets/" + assetId + "/citations",
                jsonWithToken("{\"objectType\":\"bogus\",\"objectId\":1,\"quote\":\"x\"}", editor), String.class);
        assertThat(badType.getStatusCode().value()).as("body=%s", badType.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(badType.getBody(), "$.code")).isEqualTo(400);
        assertThat((String) JsonPath.read(badType.getBody(), "$.traceId")).isNotBlank();

        // 库中仅两条合法引用
        Integer rows = jdbc.queryForObject("select count(*) from citation where asset_id=?", Integer.class, assetId);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void explorerCannotImport() {
        String explorer = newUserToken("13900001005", "访客甲", "EXPLORER");
        String creator = newUserToken("13900001006", "创作者甲", "CREATOR");

        ResponseEntity<String> denied = importCsv(csv("book,《越权的书》,,\"{\"\"chapter\"\":\"\"一\"\"}\",,,x"), explorer);
        assertThat(denied.getStatusCode().value()).as("body=%s", denied.getBody()).isEqualTo(403);
        assertThat((Integer) JsonPath.read(denied.getBody(), "$.code")).isEqualTo(403);
        assertThat((String) JsonPath.read(denied.getBody(), "$.traceId")).isNotBlank();

        // import 仅 EDITOR/OPERATOR：CREATOR 也拒绝（角色简报：02 内容运营由编辑承担）
        ResponseEntity<String> creatorDenied = importCsv(csv("book,《越权的书》,,\"{\"\"chapter\"\":\"\"一\"\"}\",,,x"), creator);
        assertThat(creatorDenied.getStatusCode().value()).as("body=%s", creatorDenied.getBody()).isEqualTo(403);

        // CREATOR 可查看资产列表（类级角色含 CREATOR）
        ResponseEntity<String> list = http.exchange("/api/wb/assets", HttpMethod.GET, bearer(creator), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);

        Integer rows = jdbc.queryForObject(
                "select count(*) from knowledge_asset where title=?", Integer.class, "《越权的书》");
        assertThat(rows).isZero();
    }

    @Test
    void listQueryPaginationAndCitationCount() {
        String editor = newUserToken("13900001007", "编辑戊", "EDITOR");
        importCsv(csv(
                "book,《甲书》,,\"{\"\"chapter\"\":\"\"一\"\"}\",,,甲",
                "book,《乙书》,,\"{\"\"chapter\"\":\"\"二\"\"}\",,,乙",
                "audio,《丙音频》,,\"{\"\"t\"\":\"\"00:00:10-00:00:20\"\"}\",,,丙"), editor);
        long cited = soleAssetId("《甲书》");
        http.postForEntity("/api/wb/assets/" + cited + "/citations",
                jsonWithToken("{\"objectType\":\"card_version\",\"objectId\":7,\"quote\":\"甲的引用\"}", editor), String.class);

        // kind 过滤
        ResponseEntity<String> books = http.exchange("/api/wb/assets?kind={kind}", HttpMethod.GET,
                bearer(editor), String.class, "book");
        assertThat((int) JsonPath.read(books.getBody(), "$.data.total")).isEqualTo(2);

        // q 对 title ILIKE
        ResponseEntity<String> byQ = http.exchange("/api/wb/assets?q={q}", HttpMethod.GET,
                bearer(editor), String.class, "乙书");
        assertThat((int) JsonPath.read(byQ.getBody(), "$.data.total")).isEqualTo(1);
        assertThat((String) JsonPath.read(byQ.getBody(), "$.data.items[0].title")).isEqualTo("《乙书》");

        // 分页 page/size（按 id 升序）
        ResponseEntity<String> page0 = http.exchange("/api/wb/assets?page={p}&size={s}", HttpMethod.GET,
                bearer(editor), String.class, 0, 2);
        assertThat((int) JsonPath.read(page0.getBody(), "$.data.items.length()")).isEqualTo(2);
        assertThat((int) JsonPath.read(page0.getBody(), "$.data.total")).isEqualTo(3);
        ResponseEntity<String> page1 = http.exchange("/api/wb/assets?page={p}&size={s}", HttpMethod.GET,
                bearer(editor), String.class, 1, 2);
        assertThat((int) JsonPath.read(page1.getBody(), "$.data.items.length()")).isEqualTo(1);

        // citationCount 批量回填：被引用的资产=1，其余=0
        ResponseEntity<String> all = http.exchange("/api/wb/assets?size={s}", HttpMethod.GET,
                bearer(editor), String.class, 100);
        List<Number> citedCounts = JsonPath.read(all.getBody(), "$.data.items[?(@.id == " + cited + ")].citationCount");
        assertThat(citedCounts).hasSize(1);
        assertThat(citedCounts.get(0).intValue()).isEqualTo(1);
        List<Number> zeroCounts = JsonPath.read(all.getBody(), "$.data.items[?(@.id != " + cited + ")].citationCount");
        assertThat(zeroCounts).hasSize(2).allSatisfy(n -> assertThat(n.intValue()).isZero());
    }
}
