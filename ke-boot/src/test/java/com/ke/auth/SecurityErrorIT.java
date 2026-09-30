package com.ke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.jayway.jsonpath.JsonPath;
import com.ke.support.ItDbReset;

/**
 * 安全边界与错误处理 envelope 约定：
 * - 未认证 → 401 且响应体是 envelope（code/traceId），不是空体；
 * - 恶意/损坏 JSON → 400 envelope（HttpMessageNotReadableException 不再落 500）；
 * - traceId 每请求唯一（TraceIdFilter 先于安全链写入并在 finally 清理，
 *   否则 Tomcat 工作线程复用时相邻请求共享同一个 traceId）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ke.test.security-error-it = true")
@ActiveProfiles("test")
@ExtendWith(ItDbReset.class)
class SecurityErrorIT {

    @Autowired
    TestRestTemplate http;

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void meWithoutTokenReturns401Envelope() {
        ResponseEntity<String> me = http.getForEntity("/api/me", String.class);
        assertThat(me.getStatusCode().value()).as("body=%s", me.getBody()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(me.getBody(), "$.code")).isEqualTo(401);
        assertThat((String) JsonPath.read(me.getBody(), "$.traceId")).isNotBlank();
    }

    @Test
    void malformedJsonLoginReturns400Envelope() {
        ResponseEntity<String> res = http.postForEntity("/api/auth/login",
                json("{not-valid-json"), String.class);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(res.getBody(), "$.code")).isEqualTo(400);
        assertThat((String) JsonPath.read(res.getBody(), "$.message")).isEqualTo("请求体格式错误");
        assertThat((String) JsonPath.read(res.getBody(), "$.traceId")).isNotBlank();
    }

    @Test
    void traceIdDiffersBetweenConsecutiveUnauthenticatedRequests() {
        ResponseEntity<String> first = http.getForEntity("/api/me", String.class);
        ResponseEntity<String> second = http.getForEntity("/api/me", String.class);
        String t1 = JsonPath.read(first.getBody(), "$.traceId");
        String t2 = JsonPath.read(second.getBody(), "$.traceId");
        assertThat(t1).isNotBlank();
        assertThat(t2).isNotBlank();
        assertThat(t1).as("traceId 必须每请求唯一：t1=%s t2=%s", t1, t2).isNotEqualTo(t2);
    }
}
