package com.ke.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ke.infra.support.ResetSchemaExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2__card_summary_and_immutability.sql：
 * 1) card.summary_text 检索摘要列与 pg_trgm GIN 索引（FR-C02 中文模糊检索准备）；
 * 2) card_version 不可变（FR-C07/C08：版本只允许 INSERT，UPDATE/DELETE 被触发器拒绝）。
 *
 * NOT_SUPPORTED 使每条语句独立事务：否则 UPDATE 触发异常后同事务的 DELETE 只会报
 * "current transaction is aborted"，无法证明拒绝来自触发器本身。
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = CardVersionImmutabilityIT.SliceConfig.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ExtendWith(ResetSchemaExtension.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CardVersionImmutabilityIT {

    /** ke-infra 是普通库模块，无 @SpringBootConfiguration，切片测试需显式指定。 */
    @Configuration(proxyBeanMethods = false)
    static class SliceConfig {
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void summaryColumnAndTrgmIndexesExist() {
        Integer column = jdbc.queryForObject(
            "select count(*) from information_schema.columns " +
            "where table_name='card' and column_name='summary_text'", Integer.class);
        assertThat(column).isEqualTo(1);
        Integer trgmExtension = jdbc.queryForObject(
            "select count(*) from pg_extension where extname='pg_trgm'", Integer.class);
        assertThat(trgmExtension).isEqualTo(1);
        Integer indexes = jdbc.queryForObject(
            "select count(*) from pg_indexes " +
            "where tablename='card' and indexname in ('idx_card_title_trgm','idx_card_summary_trgm')",
            Integer.class);
        assertThat(indexes).isEqualTo(2);
    }

    @Test
    void updateAndDeleteAreRejectedByTrigger() {
        jdbc.update("insert into ke_user(phone,nickname) values('13900000001','编辑')");
        jdbc.update("insert into card(theme,template_type,title) values('书院地标','TEXT','岳麓书院')");
        jdbc.update("insert into card_version(card_id,version_no,content_json) values(1,1,'{\"summary\":\"s\"}'::jsonb)");
        assertThatThrownBy(() -> jdbc.update("update card_version set content_json='{\"x\":1}'::jsonb where id=1"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("card_version is immutable");
        assertThatThrownBy(() -> jdbc.update("delete from card_version where id=1"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("card_version is immutable");
    }
}
