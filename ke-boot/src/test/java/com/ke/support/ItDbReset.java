package com.ke.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * ke_test 为共享测试库：每个测试类开始前清空 public schema，
 * 让 Flyway 随后从干净 schema 开始迁移（测试串行执行，无并发互踩）。
 * 连接参数可用系统属性覆盖：ke.test.db.url / ke.test.db.username / ke.test.db.password。
 */
public class ItDbReset implements BeforeAllCallback {

    static final String URL = System.getProperty("ke.test.db.url", "jdbc:postgresql://localhost:5432/ke_test");
    static final String USERNAME = System.getProperty("ke.test.db.username", "ke");
    static final String PASSWORD = System.getProperty("ke.test.db.password", "ke");

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA IF NOT EXISTS public");
        }
    }
}
