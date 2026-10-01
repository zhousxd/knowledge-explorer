package com.ke.service.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * prod 的短信网关实现：把验证码以表单 POST 到 ke.sms.gateway-url（api-key 随表单携带）。
 * 本期为占位实现，不在集成测试覆盖范围；网关地址未配置时快速失败。
 */
@Component
@Profile("prod")
public class HttpSmsSender implements SmsSender {

    private final RestTemplate http = new RestTemplate();
    private final String gatewayUrl;
    private final String apiKey;

    public HttpSmsSender(@Value("${ke.sms.gateway-url:}") String gatewayUrl,
                         @Value("${ke.sms.api-key:}") String apiKey) {
        this.gatewayUrl = gatewayUrl;
        this.apiKey = apiKey;
    }

    @Override
    public void send(String phone, String code) {
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            throw new IllegalStateException("ke.sms.gateway-url 未配置，无法发送短信");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("phone", phone);
        form.add("code", code);
        form.add("apikey", apiKey);
        http.postForEntity(gatewayUrl, new HttpEntity<>(form, headers), String.class);
    }
}
