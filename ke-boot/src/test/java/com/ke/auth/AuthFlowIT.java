package com.ke.auth;

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
import org.springframework.test.context.ActiveProfiles;

import com.jayway.jsonpath.JsonPath;
import com.ke.support.ItDb;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
class AuthFlowIT {

    @Autowired
    TestRestTemplate http;

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void registerLoginMeRoundtrip() {
        ResponseEntity<String> reg = http.postForEntity("/api/auth/register",
                json("{\"phone\":\"13800000001\",\"password\":\"passw0rd!\",\"nickname\":\"探索者\"}"), String.class);
        assertThat(reg.getStatusCode().value()).as("register body=%s", reg.getBody()).isEqualTo(201);

        ResponseEntity<String> login = http.postForEntity("/api/auth/login",
                json("{\"phone\":\"13800000001\",\"password\":\"passw0rd!\"}"), String.class);
        assertThat(login.getStatusCode().value()).as("login body=%s", login.getBody()).isEqualTo(200);
        assertThat(login.getBody()).contains("accessToken");
        String accessToken = JsonPath.read(login.getBody(), "$.data.accessToken");

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);
        ResponseEntity<String> me = http.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(me.getBody(), "$.data.nickname")).isEqualTo("探索者");
    }

    @Test
    void wrongPasswordReturns401Envelope() {
        ResponseEntity<String> reg = http.postForEntity("/api/auth/register",
                json("{\"phone\":\"13800000002\",\"password\":\"passw0rd!\",\"nickname\":\"乙\"}"), String.class);
        assertThat(reg.getStatusCode().value()).as("register body=%s", reg.getBody()).isEqualTo(201);

        ResponseEntity<String> login = http.postForEntity("/api/auth/login",
                json("{\"phone\":\"13800000002\",\"password\":\"wrong\"}"), String.class);
        assertThat(login.getStatusCode().value()).as("login body=%s", login.getBody()).isEqualTo(401);
        assertThat((Integer) JsonPath.read(login.getBody(), "$.code")).isNotNull();
        assertThat((String) JsonPath.read(login.getBody(), "$.traceId")).isNotNull();
    }
}
