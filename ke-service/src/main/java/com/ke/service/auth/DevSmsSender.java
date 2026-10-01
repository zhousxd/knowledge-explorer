package com.ke.service.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 非 prod 的短信发送实现：日志输出验证码，并写 Redis 标记 sms:sent:{phone}=code
 * 供集成测试读取（prod 的 HttpSmsSender 不写该标记）。
 */
@Component
@Profile("!prod")
public class DevSmsSender implements SmsSender {

    public static final String SENT_KEY = "sms:sent:";
    private static final Logger log = LoggerFactory.getLogger(DevSmsSender.class);

    private final StringRedisTemplate redis;

    public DevSmsSender(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void send(String phone, String code) {
        log.info("短信[{}]: 验证码 {}", phone, code);
        redis.opsForValue().set(SENT_KEY + phone, code, SmsController.CODE_TTL);
    }
}
