package com.ke.service.auth;

import com.ke.service.common.ApiResponse;
import com.ke.service.common.RateLimitException;
import com.ke.service.common.UnauthorizedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 短信验证码登录（FR-U01）：发码带 60s 冷却（Redis SET NX），验证码存 Redis hash
 * （code + 尝试次数，TTL 5 分钟，连错 5 次作废）；校验通过后与密码登录共用
 * JwtService 签发，新手机号自动注册为 EXPLORER。
 */
@RestController
@RequestMapping("/api/auth/sms")
public class SmsController {

    static final String COOL_KEY = "sms:cool:";
    static final String CODE_KEY = "sms:code:";
    private static final Duration COOL_TTL = Duration.ofSeconds(60);
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;

    private final StringRedisTemplate redis;
    private final SmsSender sender;
    private final AuthService auth;

    public SmsController(StringRedisTemplate redis, SmsSender sender, AuthService auth) {
        this.redis = redis;
        this.sender = sender;
        this.auth = auth;
    }

    public record SendReq(@NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone) {}

    public record LoginReq(@NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone,
                           @NotBlank String code) {}

    @PostMapping("/send")
    public ApiResponse<Void> send(@Valid @RequestBody SendReq req) {
        Boolean acquired = redis.opsForValue().setIfAbsent(COOL_KEY + req.phone(), "1", COOL_TTL);
        if (!Boolean.TRUE.equals(acquired)) throw new RateLimitException("发送过于频繁");
        String code = "%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
        redis.opsForHash().putAll(CODE_KEY + req.phone(), Map.of("code", code, "attempts", "0"));
        redis.expire(CODE_KEY + req.phone(), CODE_TTL);
        sender.send(req.phone(), code);
        return ApiResponse.ok(null);
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody LoginReq req) {
        String key = CODE_KEY + req.phone();
        Object expected = redis.opsForHash().get(key, "code");
        if (expected == null) throw new UnauthorizedException("验证码已过期或未发送");
        if (!expected.toString().equals(req.code())) {
            Long attempts = redis.opsForHash().increment(key, "attempts", 1);
            if (attempts != null && attempts >= MAX_ATTEMPTS) redis.delete(key);
            throw new UnauthorizedException("验证码错误");
        }
        redis.delete(key);
        return ApiResponse.ok(auth.smsLogin(req.phone()));
    }
}
