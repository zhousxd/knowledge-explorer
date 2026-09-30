package com.ke.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    void structuralBadLineSkippedWithout500() {
        String editor = newUserToken("13900001008", "编辑己", "EDITOR");
        // 第 3 行引号未闭合：结构坏行只作废该行并回报行号，其余行照常导入（不得整请求 500）
        byte[] bytes = ("kind,title,source_meta,locator,license,license_expire,content_extract\n"
                + "book,《结构完好书》,,\"{\"\"chapter\"\":\"\"一\"\"}\",已授权,,完好行一\n"
                + "book,《结构坏行书》,,\"未闭合的定位字段,还继续\n"
                + "book,《结构第二书》,,\"{\"\"chapter\"\":\"\"二\"\"}\",已授权,,完好行二\n")
                .getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> res = importCsv(bytes, editor);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.imported")).isEqualTo(2);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.skipped")).isEqualTo(1);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.errors.length()")).isEqualTo(1);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.errors[0].line")).isEqualTo(3);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.errors[0].reason")).contains("引号未闭合");

        Integer good = jdbc.queryForObject(
                "select count(*) from knowledge_asset where title in (?,?)", Integer.class,
                "《结构完好书》", "《结构第二书》");
        assertThat(good).isEqualTo(2);
        Integer bad = jdbc.queryForObject(
                "select count(*) from knowledge_asset where title=?", Integer.class, "《结构坏行书》");
        assertThat(bad).isZero();
    }

    @Test
    void wrongHeaderRejectedWithoutImport() {
        String editor = newUserToken("13900001009", "编辑庚", "EDITOR");
        // 表头缺 locator（列名集合必须包含 kind/title/locator）：imported=0，错误指向表头行
        byte[] badHeader = ("kind,title,source_meta,license\n"
                + "book,《表头缺失书》,,已授权\n")
                .getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> res = importCsv(badHeader, editor);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.imported")).isZero();
        assertThat((int) JsonPath.read(res.getBody(), "$.data.skipped")).isEqualTo(1);
        assertThat((int) JsonPath.read(res.getBody(), "$.data.errors[0].line")).isEqualTo(1);
        assertThat((String) JsonPath.read(res.getBody(), "$.data.errors[0].reason"))
                .contains("表头缺少列").contains("locator");

        // 列名集合判断与顺序/大小写无关：表头乱序 + 大写仍可通过（数据行仍按固定列位解析）
        byte[] shuffledHeader = ("TITLE,KIND,LOCATOR,source_meta,license,license_expire,content_extract\n"
                + "book,《乱序表头书》,,\"{\"\"chapter\"\":\"\"一\"\"}\",,,\n")
                .getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> ok = importCsv(shuffledHeader, editor);
        assertThat(ok.getStatusCode().value()).as("body=%s", ok.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(ok.getBody(), "$.data.imported")).isEqualTo(1);
        Integer rows = jdbc.queryForObject(
                "select count(*) from knowledge_asset where title=?", Integer.class, "《乱序表头书》");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void missingFilePartRejectedAs400() {
        String editor = newUserToken("13900001010", "编辑辛", "EDITOR");
        // multipart 不带 file part → MissingServletRequestPartException → 400 envelope
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("notFile", "oops");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(editor);
        ResponseEntity<String> res = http.postForEntity("/api/wb/assets/import",
                new HttpEntity<>(form, headers), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(400);
        assertThat((String) JsonPath.read(res.getBody(), "$.message")).contains("缺少上传文件");
        assertThat((String) JsonPath.read(res.getBody(), "$.traceId")).isNotBlank();
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
                "book,《分页甲书》,,\"{\"\"chapter\"\":\"\"一\"\"}\",,,甲",
                "book,《分页乙书》,,\"{\"\"chapter\"\":\"\"二\"\"}\",,,乙",
                "audio,《分页丙音频》,,\"{\"\"t\"\":\"\"00:00:10-00:00:20\"\"}\",,,丙"), editor);
        // 本方法自己的数据集（@ItDb 只在类开始前清一次库，断言一律限定在自己的 title 集合内）
        List<Long> mine = jdbc.queryForList(
                "select id from knowledge_asset where title in (?,?,?) order by id", Long.class,
                "《分页甲书》", "《分页乙书》", "《分页丙音频》");
        assertThat(mine).hasSize(3);
        long cited = mine.get(0);
        http.postForEntity("/api/wb/assets/" + cited + "/citations",
                jsonWithToken("{\"objectType\":\"card_version\",\"objectId\":7,\"quote\":\"甲的引用\"}", editor), String.class);

        // kind 过滤 + q 前缀（“分页”仅命中本方法数据）：2 本 book
        ResponseEntity<String> books = http.exchange("/api/wb/assets?kind={kind}&q={q}", HttpMethod.GET,
                bearer(editor), String.class, "book", "分页");
        assertThat((int) JsonPath.read(books.getBody(), "$.data.total")).isEqualTo(2);

        // q 对 title ILIKE
        ResponseEntity<String> byQ = http.exchange("/api/wb/assets?q={q}", HttpMethod.GET,
                bearer(editor), String.class, "乙书");
        assertThat((int) JsonPath.read(byQ.getBody(), "$.data.total")).isEqualTo(1);
        assertThat((String) JsonPath.read(byQ.getBody(), "$.data.items[0].title")).isEqualTo("《分页乙书》");

        // 分页 page/size（q 圈定本方法数据集，按 id 升序）
        ResponseEntity<String> page0 = http.exchange("/api/wb/assets?q={q}&page={p}&size={s}", HttpMethod.GET,
                bearer(editor), String.class, "分页", 0, 2);
        assertThat((int) JsonPath.read(page0.getBody(), "$.data.items.length()")).isEqualTo(2);
        assertThat((int) JsonPath.read(page0.getBody(), "$.data.total")).isEqualTo(3);
        ResponseEntity<String> page1 = http.exchange("/api/wb/assets?q={q}&page={p}&size={s}", HttpMethod.GET,
                bearer(editor), String.class, "分页", 1, 2);
        assertThat((int) JsonPath.read(page1.getBody(), "$.data.items.length()")).isEqualTo(1);

        // citationCount 批量回填：全量拉取后按本方法 id 集合逐个断言（被引=1，其余=0）
        ResponseEntity<String> all = http.exchange("/api/wb/assets?size={s}", HttpMethod.GET,
                bearer(editor), String.class, 100);
        List<Integer> ids = JsonPath.read(all.getBody(), "$.data.items[*].id");
        List<Number> counts = JsonPath.read(all.getBody(), "$.data.items[*].citationCount");
        assertThat(ids).hasSize(counts.size());
        Map<Long, Number> countById = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            countById.put(ids.get(i).longValue(), counts.get(i));
        }
        for (Long id : mine) {
            long expected = id == cited ? 1L : 0L;
            assertThat(countById).containsKey(id);
            assertThat(countById.get(id).longValue()).as("asset %s", id).isEqualTo(expected);
        }
    }
}
