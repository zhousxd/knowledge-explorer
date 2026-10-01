package com.ke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.jayway.jsonpath.JsonPath;
import com.ke.service.auth.DevSmsSender;
import com.ke.support.ItDb;
import com.ke.support.RedisFlush;

/**
 * 短信验证码登录（FR-U01）：发码冷却 / 验证码校验与尝试次数作废 / 自动注册。
 * DevSmsSender（test profile 生效）会把验证码写入 sms:sent:{phone}，测试据此取码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
@ExtendWith(RedisFlush.class)
class SmsLoginIT {

    @Autowired
    TestRestTemplate http;
    @Autowired
    StringRedisTemplate redis;

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /** 发码并从 Redis 标记取回验证码（仅 dev/test 行为） */
    private String sendAndGetCode(String phone) {
        ResponseEntity<String> send = http.postForEntity("/api/auth/sms/send",
                json("{\"phone\":\"" + phone + "\"}"), String.class);
        assertThat(send.getStatusCode().value()).as("send body=%s", send.getBody()).isEqualTo(200);
        return redis.opsForValue().get(DevSmsSender.SENT_KEY + phone);
    }

    @Test
    void devSmsSendAndLoginRoundtrip() {
        String phone = "13900000001";
        String code = sendAndGetCode(phone);
        assertThat(code).as("DevSmsSender 应写 sms:sent 标记").isNotBlank();

        ResponseEntity<String> login = http.postForEntity("/api/auth/sms/login",
                json("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"), String.class);
        assertThat(login.getStatusCode().value()).as("login body=%s", login.getBody()).isEqualTo(200);
        String accessToken = JsonPath.read(login.getBody(), "$.data.accessToken");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> me = http.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(me.getBody(), "$.data.nickname")).isEqualTo("探索者0001");
        assertThat((String) JsonPath.read(me.getBody(), "$.data.role")).isEqualTo("EXPLORER");
    }

    @Test
    void wrongCodeRejectedAndInvalidatedAfter5Attempts() {
        String phone = "13900000002";
        String code = sendAndGetCode(phone);
        // 随机码理论上可能为 000000，错码取一个确定不同的值
        String wrongCode = "000000".equals(code) ? "999999" : "000000";

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> wrong = http.postForEntity("/api/auth/sms/login",
                    json("{\"phone\":\"" + phone + "\",\"code\":\"" + wrongCode + "\"}"), String.class);
            assertThat(wrong.getStatusCode().value()).as("第%d次错码 body=%s", i + 1, wrong.getBody()).isEqualTo(401);
            assertThat((Integer) JsonPath.read(wrong.getBody(), "$.code")).isEqualTo(401);
        }
        // 连错 5 次后该验证码作废：正确码也拒绝
        ResponseEntity<String> correct = http.postForEntity("/api/auth/sms/login",
                json("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"), String.class);
        assertThat(correct.getStatusCode().value()).as("correct body=%s", correct.getBody()).isEqualTo(401);
    }

    @Test
    void cooldownEnforced() {
        String phone = "13900000003";
        assertThat(http.postForEntity("/api/auth/sms/send",
                json("{\"phone\":\"" + phone + "\"}"), String.class).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> second = http.postForEntity("/api/auth/sms/send",
                json("{\"phone\":\"" + phone + "\"}"), String.class);
        assertThat(second.getStatusCode().value()).as("second body=%s", second.getBody()).isEqualTo(429);
        assertThat((String) JsonPath.read(second.getBody(), "$.message")).isEqualTo("发送过于频繁");
        assertThat((String) JsonPath.read(second.getBody(), "$.traceId")).isNotNull();
    }

    @Test
    void invalidPhoneRejected() {
        ResponseEntity<String> send = http.postForEntity("/api/auth/sms/send",
                json("{\"phone\":\"abc\"}"), String.class);
        assertThat(send.getStatusCode().value()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(send.getBody(), "$.code")).isEqualTo(400);

        ResponseEntity<String> login = http.postForEntity("/api/auth/sms/login",
                json("{\"phone\":\"abc\",\"code\":\"123456\"}"), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(400);
    }
}
