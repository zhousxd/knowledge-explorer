package com.ke.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.jayway.jsonpath.JsonPath;
import com.ke.support.ItDb;

/**
 * 卡片配图（二期图片功能·本地存储版）端到端：
 * - 上传：EDITOR 魔数白名单 PNG 落盘 ke.upload.dir（{yyyy}/{MM}/{uuid}.ext）+ card_image 入库；
 *   文本伪装 image/png → 400（客户端 Content-Type 不可信）；EXPLORER → 403；
 * - 读取：GET /api/images/{id} 匿名 200（与卡片详情同语义）+ immutable 长缓存；不存在 → 404；
 * - 内容把关：TEXT 卡 content.image 指向真实上传 → 存档；指向不存在 id / url 错位 → 400 不入版本库。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class ImageUploadIT {

    /** 在 @DynamicPropertySource 求值时自建（避免 @TempDir 注入顺序与容器创建竞态） */
    static Path uploadDir;

    @DynamicPropertySource
    static void uploadDir(DynamicPropertyRegistry registry) {
        try {
            uploadDir = Files.createTempDirectory("ke-upload-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("ke.upload.dir", () -> uploadDir.toString());
    }

    @AfterAll
    static void cleanUploadDir() throws IOException {
        if (uploadDir != null && Files.exists(uploadDir)) {
            try (var paths = Files.walk(uploadDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    /** 1×1 合法 PNG（仅作魔数与落盘载体，不渲染断言） */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

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

    /** 注册（API）→ 经 JdbcTemplate 提权 → 重新登录拿 accessToken */
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

    private HttpEntity<MultiValueMap<String, Object>> imageUpload(byte[] bytes, String filename, String token) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", resource);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return new HttpEntity<>(form, headers);
    }

    private ResponseEntity<String> uploadNamed(byte[] bytes, String filename, String token) {
        return http.postForEntity("/api/wb/images", imageUpload(bytes, filename, token), String.class);
    }

    private ResponseEntity<String> upload(byte[] bytes, String token) {
        return uploadNamed(bytes, "pic.png", token);
    }

    /** 同类用例共享库会累积行：断言一律限定在本次上传的文件名内 */
    private Integer rowsOf(String originalName) {
        return jdbc.queryForObject("select count(*) from card_image where original_name=?", Integer.class, originalName);
    }

    // ---------- scenarios ----------

    @Test
    void uploadPersistsFileAndRowThenServesAnonymously() {
        String editor = newUserToken("13900002001", "配图编辑", "EDITOR");

        ResponseEntity<String> res = upload(PNG, editor);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat((int) JsonPath.read(res.getBody(), "$.code")).isZero();
        long id = ((Number) JsonPath.read(res.getBody(), "$.data.id")).longValue();
        assertThat(JsonPath.<String>read(res.getBody(), "$.data.url")).isEqualTo("/api/images/" + id);
        assertThat(JsonPath.<String>read(res.getBody(), "$.data.contentType")).isEqualTo("image/png");

        // DB 元数据：落盘相对路径无用户可控字符，size/type 留档
        var row = jdbc.queryForMap("select storage_key, content_type, size_bytes, original_name from card_image where id=?", id);
        assertThat((String) row.get("content_type")).isEqualTo("image/png");
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(PNG.length);
        assertThat((String) row.get("storage_key")).matches("\\d{4}/\\d{2}/[0-9a-f]{32}\\.png");

        // 文件真实落盘且字节一致
        Path onDisk = uploadDir.resolve((String) row.get("storage_key"));
        assertThat(onDisk).exists();
        assertThat(onDisk).hasBinaryContent(PNG);

        // 匿名读取（无 token）：原始字节 + 类型 + immutable 长缓存
        ResponseEntity<byte[]> img = http.getForEntity("/api/images/" + id, byte[].class);
        assertThat(img.getStatusCode().value()).isEqualTo(200);
        assertThat(img.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("image/png"));
        assertThat(img.getHeaders().getCacheControl()).contains("immutable");
        assertThat(img.getBody()).isEqualTo(PNG);

        // 不存在 → 404
        assertThat(http.getForEntity("/api/images/999999", String.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void disguisedTextFileRejectedWithoutSideEffects() {
        String editor = newUserToken("13900002002", "伪装拦editor", "EDITOR");

        ResponseEntity<String> res = uploadNamed("<script>alert(1)</script>".getBytes(), "disguise.png", editor);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((int) JsonPath.read(res.getBody(), "$.code")).isEqualTo(400);
        assertThat(rowsOf("disguise.png")).isZero();
        assertThat(uploadDir.resolve("disguise.png")).doesNotExist();
    }

    @Test
    void explorerCannotUpload() {
        String explorer = newUserToken("13900002003", "只读游客", "EXPLORER");

        ResponseEntity<String> res = uploadNamed(PNG, "explorer.png", explorer);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(403);
        assertThat(rowsOf("explorer.png")).isZero();
    }

    @Test
    void cardContentImageReferenceValidated() {
        String editor = newUserToken("13900002004", "引用把关editor", "EDITOR");
        ResponseEntity<String> uploaded = upload(PNG, editor);
        long imageId = ((Number) JsonPath.read(uploaded.getBody(), "$.data.id")).longValue();

        // 引用真实配图 → 建卡成功，详情回读带出 image
        String contentWithImage = """
            {"summary":"岳麓书院讲堂",
             "sections":[{"h":"讲堂","body":"书院核心建筑","citations":[]}],
             "image":{"id":%d,"url":"/api/images/%d","alt":"讲堂"}}
            """.formatted(imageId, imageId);
        ResponseEntity<String> created = http.postForEntity("/api/wb/cards", jsonWithToken("""
            {"theme":"academy","templateType":"TEXT","title":"配图卡",
             "content":%s,"sources":[]}
            """.formatted(contentWithImage), editor), String.class);
        assertThat(created.getStatusCode().value()).as("body=%s", created.getBody()).isEqualTo(201);
        long cardId = ((Number) JsonPath.read(created.getBody(), "$.data.cardId")).longValue();
        assertThat(http.exchange("/api/wb/cards/" + cardId, HttpMethod.GET,
                jsonWithToken(null, editor), String.class).getBody()).contains("/api/images/" + imageId);

        // 指向不存在 id → 400，不产生新版本
        int versionsBefore = jdbc.queryForObject(
                "select count(*) from card_version where card_id=?", Integer.class, cardId);
        ResponseEntity<String> rejected = http.exchange("/api/wb/cards/" + cardId + "/content", HttpMethod.PUT,
                jsonWithToken("""
                    {"content":{"summary":"岳麓书院讲堂",
                     "sections":[{"h":"讲堂","body":"改","citations":[]}],
                     "image":{"id":424242,"url":"/api/images/424242","alt":"不存在"}},"sources":[]}
                    """, editor), String.class);
        assertThat(rejected.getStatusCode().value()).as("body=%s", rejected.getBody()).isEqualTo(400);
        assertThat((int) jdbc.queryForObject("select count(*) from card_version where card_id=?", Integer.class, cardId))
                .isEqualTo(versionsBefore);

        // url 与 id 不一致（自造规范形状 URL）同样拦下
        ResponseEntity<String> mismatch = http.exchange("/api/wb/cards/" + cardId + "/content", HttpMethod.PUT,
                jsonWithToken("""
                    {"content":{"summary":"岳麓书院讲堂",
                     "sections":[{"h":"讲堂","body":"改","citations":[]}],
                     "image":{"id":%d,"url":"/api/images/999999","alt":"错位"}},"sources":[]}
                    """.formatted(imageId), editor), String.class);
        assertThat(mismatch.getStatusCode().value()).as("body=%s", mismatch.getBody()).isEqualTo(400);
    }
}
