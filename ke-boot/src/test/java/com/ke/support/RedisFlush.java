package com.ke.support;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Redis 相关 IT 前置：与 application-test.yml 同源的配置（localhost:6379、
 * 密码 ${KE_REDIS_PASSWORD}、database 15）对测试库执行 flushdb，
 * 避免冷却键/验证码键等跨类残留。仅挂在用到 Redis 的 IT 上，与 @ItDb 组合使用。
 * 连接参数可用系统属性覆盖：ke.test.redis.host / port / password / database。
 */
public class RedisFlush implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        String host = System.getProperty("ke.test.redis.host", "localhost");
        int port = Integer.parseInt(System.getProperty("ke.test.redis.port", "6379"));
        int database = Integer.parseInt(System.getProperty("ke.test.redis.database", "15"));
        String password = System.getProperty("ke.test.redis.password",
                System.getenv().getOrDefault("KE_REDIS_PASSWORD", ""));

        RedisURI.Builder builder = RedisURI.builder().withHost(host).withPort(port).withDatabase(database);
        if (!password.isBlank()) builder.withPassword(password.toCharArray());

        RedisClient client = RedisClient.create(builder.build());
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            connection.sync().flushdb();
        } finally {
            client.shutdown();
        }
    }
}
