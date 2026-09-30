package com.ke.infra;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import com.ke.infra.support.ResetSchemaExtension;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MigrationIT.SliceConfig.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ExtendWith(ResetSchemaExtension.class)
class MigrationIT {

    /** ke-infra 是普通库模块，无 @SpringBootConfiguration，切片测试需显式指定。 */
    @Configuration(proxyBeanMethods = false)
    static class SliceConfig {
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void coreTablesExistAfterMigration() {
        List<String> tables = jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema='public' order by 1", String.class);
        assertThat(tables).contains(
            "ke_user", "card", "card_version", "entry", "knowledge_asset", "citation",
            "exploration_session", "path_node", "agent_run", "artifact",
            "share", "share_snapshot", "review_task");
    }

    @Test
    void agentRunStatusHasDefaultQueued() {
        String def = jdbc.queryForObject(
            "select column_default from information_schema.columns " +
            "where table_name='agent_run' and column_name='status'", String.class);
        assertThat(def).contains("QUEUED");
    }
}
