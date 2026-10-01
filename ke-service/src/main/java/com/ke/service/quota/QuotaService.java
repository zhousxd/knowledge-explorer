package com.ke.service.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 智能服务每日配额（FR-S04）：Redis INCR 计数，key {@code user:quota:{userId}:{yyyyMMdd}}，
 * 上限 {@code ke.quota.daily-limit}（默认 30 次/日）。
 *
 * <p>超限判定决策：incr 后判断，&gt;limit 即超限返回 false——计数含被拒次（第 31 次、32 次…均拒绝），
 * 简单可测，无 GET/INCR 竞态窗口。首次（incr==1）设 48h TTL：跨日自然过期重建，无需清理任务。
 *
 * <p>故障语义：配额是护栏不是数据正确性依赖，Redis 不可用时降级放行（fail-open，打 warn），
 * 不让缓存层故障打断全部智能服务。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    /** 覆盖跨日重置：当日键 48h 后自然过期 */
    static final Duration TTL = Duration.ofHours(48);
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redis;

    @Value("${ke.quota.daily-limit:30}")
    private int dailyLimit;

    public QuotaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 配额键（public 供 IT 预置与运维排查） */
    public static String keyOf(long userId, LocalDate date) {
        return "user:quota:" + userId + ":" + date.format(KEY_DATE);
    }

    /** 消费一次配额：true=放行，false=当日已用完（调用方抛 RateLimitException → 429） */
    public boolean tryConsume(long userId) {
        try {
            String key = keyOf(userId, LocalDate.now());
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            if (count == 1) {
                redis.expire(key, TTL);
            }
            return count <= dailyLimit;
        } catch (Exception e) {
            log.warn("配额 Redis 不可用，降级放行 user {}: {}", userId, e.getMessage());
            return true;
        }
    }

    /** 当日已用次数（无键/Redis 不可用按 0） */
    public int used(long userId) {
        try {
            String value = redis.opsForValue().get(keyOf(userId, LocalDate.now()));
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception e) {
            log.warn("配额查询 Redis 不可用，按 0 计: {}", e.getMessage());
            return 0;
        }
    }

    public int remaining(long userId) {
        return Math.max(0, dailyLimit - used(userId));
    }

    public int dailyLimit() {
        return dailyLimit;
    }
}
