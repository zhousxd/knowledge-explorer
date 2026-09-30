# W1 工程骨架与模型定稿 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建一期 MVP 的可运行工程底座：Java 多模块骨架 + 全量核心表 + 认证 API + LLM 网关与异步执行骨架 + Vue3 双端 monorepo（Token/共享组件/图标落地）+ CI，对应 `docs/05-开发计划.md` W1 全部任务。

**Architecture:** 与 `docs/02-技术架构.md` 一致——Maven 四模块模块化单体（ke-domain 纯领域 / ke-infra 持久化 / ke-service 业务与 Web / ke-boot 启动），Flyway 管理 PG 15 全量核心表；LLM 调用收口 `LlmGateway` 接口（Spring AI 实现 + Mock 实现），`agent_run` 表既是任务队列又是执行记录，虚拟线程执行器消费；前端 `frontend/` pnpm workspace（packages/shared + apps/explorer + apps/workbench），UI Token 与共享语义组件来自 `docs/04-主题与UI规范.md` v1.1。

**Tech Stack:** Java 21 · Spring Boot 3.5 · MyBatis-Plus · Flyway · PostgreSQL 15(pgvector 镜像) · jjwt · Spring AI 1.x(OpenAI 兼容) · Testcontainers · MockWebServer · Vue 3 · Vite · Vant · Element Plus · Vitest · pnpm · GitHub Actions

## Global Constraints

- JDK **21**；Spring Boot **3.5.x**；Maven **≥3.6.3**（wrapper 固定 3.9.9）；Node **≥20**；pnpm **≥9**
- 所有依赖版本**只允许在父 POM `<properties>` / frontend 根 `package.json` 锁定**，模块不得自报版本号
- 数据库：snake_case 命名，业务表前缀无（表名即 `card`/`entry`…），用户表 `ke_user`；时间列一律 `TIMESTAMPTZ`；`content_json`/`config_json` 用 `JSONB`
- **版本不可变**：`card_version` 只允许 INSERT（W2 起触发器兜底，本期代码层只 INSERT）
- JWT：Access **2h** / Refresh **7d**（02 §9）；密钥经 `KE_JWT_SECRET` 环境变量注入，本地默认值仅供 dev
- 错误响应统一 envelope：`{code, message, traceId}`；成功响应 `{code:0, message:"ok", traceId, data}`（02 §7）
- 前端**禁止裸色值**：`frontend/` 下 `.vue/.css` 不得出现 `#hex`/`rgb()`（stylelint 守护，`tokens.css` 与 `theme-*.css` 豁免）；**控件图标一律 SVG**（`@ke/shared` 图标集），禁止 emoji
- UI Token/主题映射/图标 sprite 的唯一取值来源：`docs/04-主题与UI规范.md` §11 与 `prototype/styleguide.html`（`:root` 块与 `<defs>` 块原文复制，禁止手抄改值）
- 提交信息用 Conventional Commits（`feat:`/`test:`/`chore:`/`build:`），每个 Task 至少一次提交
- 测试命令在仓库根（Git Bash）执行；`./mvnw` 与 `pnpm --dir frontend` 为标准入口

---

### Task 1: Maven 多模块骨架与 Boot 启动（虚拟线程）

**Files:**
- Create: `pom.xml`（父 POM）
- Create: `ke-domain/pom.xml`、`ke-infra/pom.xml`、`ke-service/pom.xml`、`ke-boot/pom.xml`
- Create: `ke-boot/src/main/java/com/ke/KeApplication.java`
- Create: `ke-boot/src/main/resources/application.yml`、`application-dev.yml`
- Test: `ke-boot/src/test/java/com/ke/BootApplicationTest.java`

**Interfaces:**
- Produces: 包根 `com.ke`；模块坐标 `com.ke:ke-parent/ke-domain/ke-infra/ke-service/ke-boot`；属性名 `spring-boot.version`、`spring-ai.version`、`mybatis-plus.version`、`jjwt.version`、`springdoc.version`、`testcontainers.version`（后续任务引用）

- [ ] **Step 1: 写失败的上下文加载测试**

```java
package com.ke;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BootApplicationTest {
    @Test
    void contextLoads() {}
}
```

- [ ] **Step 2: 创建父 POM 与四个模块 POM**

父 `pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.ke</groupId>
  <artifactId>ke-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>ke-domain</module>
    <module>ke-infra</module>
    <module>ke-service</module>
    <module>ke-boot</module>
  </modules>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <spring-boot.version>3.5.5</spring-boot.version>
    <spring-ai.version>1.0.1</spring-ai.version>
    <mybatis-plus.version>3.5.12</mybatis-plus.version>
    <jjwt.version>0.12.6</jjwt.version>
    <springdoc.version>2.8.9</springdoc.version>
    <testcontainers.version>1.20.6</testcontainers.version>
    <awaitility.version>4.2.2</awaitility.version>
    <mockwebserver.version>4.12.0</mockwebserver.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        <version>${mybatis-plus.version}</version>
      </dependency>
      <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>${jjwt.version}</version>
      </dependency>
      <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>${springdoc.version}</version>
      </dependency>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.awaitility</groupId>
        <artifactId>awaitility</artifactId>
        <version>${awaitility.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>mockwebserver</artifactId>
        <version>${mockwebserver.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency><groupId>com.ke</groupId><artifactId>ke-domain</artifactId><version>${project.version}</version></dependency>
      <dependency><groupId>com.ke</groupId><artifactId>ke-infra</artifactId><version>${project.version}</version></dependency>
      <dependency><groupId>com.ke</groupId><artifactId>ke-service</artifactId><version>${project.version}</version></dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
          <version>${spring-boot.version}</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

`ke-domain/pom.xml`（纯领域，零 Spring）：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.ke</groupId>
    <artifactId>ke-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>ke-domain</artifactId>
</project>
```

`ke-infra/pom.xml`：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.ke</groupId>
    <artifactId>ke-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>ke-infra</artifactId>
  <dependencies>
    <dependency><groupId>com.ke</groupId><artifactId>ke-domain</artifactId></dependency>
    <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
  </dependencies>
</project>
```

`ke-service/pom.xml`（业务 + Web 层）：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.ke</groupId>
    <artifactId>ke-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>ke-service</artifactId>
  <dependencies>
    <dependency><groupId>com.ke</groupId><artifactId>ke-infra</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-model-openai</artifactId></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-jackson</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

`ke-boot/pom.xml`（启动模块，web/worker 共用）：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.ke</groupId>
    <artifactId>ke-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>ke-boot</artifactId>
  <dependencies>
    <dependency><groupId>com.ke</groupId><artifactId>ke-service</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <executions>
          <execution><goals><goal>repackage</goal></goals></execution>
        </executions>
        <configuration>
          <layers><enabled>true</enabled></layers>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 启动类与配置**

`ke-boot/src/main/java/com/ke/KeApplication.java`：

```java
package com.ke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KeApplication {
    public static void main(String[] args) {
        SpringApplication.run(KeApplication.class, args);
    }
}
```

`application.yml`：

```yaml
spring:
  application:
    name: knowledge-explorer
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  threads:
    virtual:
      enabled: true
  jackson:
    default-property-inclusion: non_null

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

`application-dev.yml`（本地数据库由 Task 2 的 compose 提供；Testcontainers 测试用 `test` profile 覆盖）：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ke
    username: ke
    password: ke
  flyway:
    enabled: true
    locations: classpath:db/migration

ke:
  jwt:
    secret: ${KE_JWT_SECRET:dev-only-secret-key-32bytes!!dev-only!!}
    access-ttl: 2h
    refresh-ttl: 7d
```

- [ ] **Step 4: 安装 Maven Wrapper 并验证测试通过**

```bash
mvn -N wrapper:wrapper -Dmaven=3.9.9
./mvnw -B -ntp test
```

Expected: `BootApplicationTest` PASS（`contextLoads`）。此时无 DB 依赖（datasource 由 dev profile 声明但未连接——若上下文因 datasource 失败，给测试加 `@SpringBootTest(properties = {"spring.datasource.url=jdbc:tc:postgresql:15:///ke"})` 前置临时方案，Task 2 完成后恢复）。

- [ ] **Step 5: Commit**

```bash
git add pom.xml ke-domain ke-infra ke-service ke-boot .mvn mvnw mvnw.cmd
git commit -m "build: maven 多模块骨架与 boot 启动（Java21 虚拟线程）"
```

---

### Task 2: Docker Compose 开发环境 + Flyway V1 全量核心表

**Files:**
- Create: `docker-compose.dev.yml`
- Create: `ke-infra/src/main/resources/db/migration/V1__core_schema.sql`
- Test: `ke-infra/src/test/java/com/ke/infra/MigrationIT.java`

**Interfaces:**
- Produces: 表 `ke_user`、`card`、`card_version`、`entry`、`knowledge_asset`、`citation`、`exploration_session`、`path_node`、`agent_run`、`artifact`、`share`、`share_snapshot`、`review_task`（列定义见 SQL，后续任务按此写实体）；dev 数据源 `localhost:5432/ke`（ke/ke）

- [ ] **Step 1: 写失败的迁移测试（Testcontainers 基线）**

```java
package com.ke.infra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@JdbcTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class MigrationIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg15");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
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
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -B -ntp -pl ke-infra test`
Expected: FAIL——迁移目录为空，Flyway 无事可做，`coreTablesExistAfterMigration` 断言的表不存在（`@JdbcTest` 默认不装配 Flyway，已用 `@ImportAutoConfiguration(FlywayAutoConfiguration.class)` 引入）

- [ ] **Step 3: 写 compose 与 V1 迁移脚本**

`docker-compose.dev.yml`：

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg15
    environment:
      POSTGRES_DB: ke
      POSTGRES_USER: ke
      POSTGRES_PASSWORD: ke
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
volumes:
  pgdata:
```

`V1__core_schema.sql`（列定义 = 02 §4.2 数据模型定稿）：

```sql
-- 用户与角色（FR-U01/U03：EXPLORER/CREATOR/EDITOR/OPERATOR）
CREATE TABLE ke_user (
    id            BIGSERIAL PRIMARY KEY,
    phone         VARCHAR(20) UNIQUE,
    password_hash VARCHAR(100),
    nickname      VARCHAR(50)  NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'EXPLORER',
    status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 卡片与版本（FR-C07/C08：版本不可变，只允许 INSERT）
CREATE TABLE card (
    id                 BIGSERIAL PRIMARY KEY,
    theme              VARCHAR(50)  NOT NULL,
    template_type      VARCHAR(20)  NOT NULL,
    title              VARCHAR(120) NOT NULL,
    status             VARCHAR(12)  NOT NULL DEFAULT 'DRAFT',
    current_version_id BIGINT,
    maintainer_id      BIGINT REFERENCES ke_user (id),
    sort               INT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_card_theme_status ON card (theme, status);

CREATE TABLE card_version (
    id           BIGSERIAL PRIMARY KEY,
    card_id      BIGINT NOT NULL REFERENCES card (id),
    version_no   INT NOT NULL,
    content_json JSONB NOT NULL,
    sources      JSONB,
    created_by   BIGINT REFERENCES ke_user (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (card_id, version_no)
);

-- 入口（FR-N07 白名单类型：link_card / agent_service / compare）
CREATE TABLE entry (
    id             BIGSERIAL PRIMARY KEY,
    card_id        BIGINT REFERENCES card (id),
    name           VARCHAR(60) NOT NULL,
    type           VARCHAR(20) NOT NULL,
    relation_label VARCHAR(20),
    target_card_id BIGINT REFERENCES card (id),
    service_type   VARCHAR(30),
    config_json    JSONB,
    scope          VARCHAR(10) NOT NULL DEFAULT 'PRIVATE',
    status         VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    version        INT NOT NULL DEFAULT 1,
    author_id      BIGINT REFERENCES ke_user (id),
    sort           INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entry_card ON entry (card_id, status);

-- 知识单元与引用（FR-O01/O02：locator 保留卷/章/页/时间戳）
CREATE TABLE knowledge_asset (
    id             BIGSERIAL PRIMARY KEY,
    kind           VARCHAR(12)  NOT NULL,
    title          VARCHAR(200) NOT NULL,
    source_meta    JSONB,
    locator        JSONB,
    license        VARCHAR(50),
    license_expire DATE,
    content_extract TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE citation (
    id          BIGSERIAL PRIMARY KEY,
    asset_id    BIGINT NOT NULL REFERENCES knowledge_asset (id),
    locator     JSONB,
    quote       TEXT,
    object_type VARCHAR(20) NOT NULL,
    object_id   BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_citation_object ON citation (object_type, object_id);

-- 会话与路径树（FR-E03/E05：分支不建表，parent_node_id 自然成树）
CREATE TABLE exploration_session (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES ke_user (id),
    theme           VARCHAR(50),
    goal            TEXT,
    explain_level   VARCHAR(10) NOT NULL DEFAULT 'SIMPLE',
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    origin_share_id BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_session_user ON exploration_session (user_id, status);

CREATE TABLE path_node (
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT NOT NULL REFERENCES exploration_session (id),
    parent_node_id   BIGINT REFERENCES path_node (id),
    card_version_id  BIGINT REFERENCES card_version (id),
    entry_id         BIGINT REFERENCES entry (id),
    question_text    TEXT,
    is_new_knowledge BOOLEAN,
    visited_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_node_session ON path_node (session_id);
CREATE INDEX idx_node_parent ON path_node (parent_node_id);

-- 智能体任务（FR-S04：既是队列又是执行记录）
CREATE TABLE agent_run (
    id           BIGSERIAL PRIMARY KEY,
    session_id   BIGINT REFERENCES exploration_session (id),
    node_id      BIGINT REFERENCES path_node (id),
    service_type VARCHAR(30) NOT NULL,
    input_json   JSONB,
    status       VARCHAR(10) NOT NULL DEFAULT 'QUEUED',
    artifact_ids JSONB,
    model        VARCHAR(60),
    tokens_in    INT,
    tokens_out   INT,
    cost         NUMERIC(10, 4),
    latency_ms   INT,
    error        TEXT,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_run_status ON agent_run (status);
CREATE INDEX idx_run_session ON agent_run (session_id);

-- 成果与分享（FR-S03 / FR-H03：快照不可变 JSON）
CREATE TABLE artifact (
    id            BIGSERIAL PRIMARY KEY,
    session_id    BIGINT NOT NULL REFERENCES exploration_session (id),
    type          VARCHAR(20) NOT NULL,
    content_json  JSONB NOT NULL,
    cited_run_ids JSONB,
    status        VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE share (
    id          BIGSERIAL PRIMARY KEY,
    token       VARCHAR(21) NOT NULL UNIQUE,
    object_type VARCHAR(20) NOT NULL,
    object_id   BIGINT NOT NULL,
    user_id     BIGINT NOT NULL REFERENCES ke_user (id),
    visibility  VARCHAR(10) NOT NULL DEFAULT 'PUBLIC',
    revoked     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE share_snapshot (
    id            BIGSERIAL PRIMARY KEY,
    share_id      BIGINT NOT NULL REFERENCES share (id),
    snapshot_json JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 审核队列（FR-O03）
CREATE TABLE review_task (
    id          BIGSERIAL PRIMARY KEY,
    object_type VARCHAR(12) NOT NULL,
    object_id   BIGINT NOT NULL,
    action      VARCHAR(20),
    status      VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    reviewer_id BIGINT REFERENCES ke_user (id),
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_review_status ON review_task (status);
```

- [ ] **Step 4: 运行测试至通过**

Run: `./mvnw -B -ntp -pl ke-infra test`
Expected: `MigrationIT` 2 个测试 PASS

- [ ] **Step 5: 本地 compose 冒烟**

```bash
docker compose -f docker-compose.dev.yml up -d postgres
./mvnw -B -ntp -pl ke-boot spring-boot:run &   # 观察 flyway 日志后 Ctrl+C
```

Expected: 日志出现 `Successfully applied 1 migration`；`docker compose ... exec postgres psql -U ke -c '\dt'` 列出 13 张表。

- [ ] **Step 6: Commit**

```bash
git add docker-compose.dev.yml ke-infra
git commit -m "feat: flyway v1 全量核心表与开发环境 compose"
```

---

### Task 3: ke-domain 领域枚举（纯 Java，零依赖）

**Files:**
- Create: `ke-domain/src/main/java/com/ke/domain/enums/UserRole.java`
- Create: `ke-domain/src/main/java/com/ke/domain/enums/CardStatus.java`
- Create: `ke-domain/src/main/java/com/ke/domain/enums/EntryType.java`
- Create: `ke-domain/src/main/java/com/ke/domain/enums/AgentRunStatus.java`
- Create: `ke-domain/src/main/java/com/ke/domain/enums/ClaimType.java`
- Test: `ke-domain/src/test/java/com/ke/domain/enums/AgentRunStatusTest.java`

**Interfaces:**
- Produces: `UserRole.EXPLORER/CREATOR/EDITOR/OPERATOR`；`CardStatus.DRAFT/PENDING/PUBLISHED/DISABLED`（含 `canTransitionTo(CardStatus)`）；`EntryType.LINK_CARD/AGENT_SERVICE/COMPARE`；`AgentRunStatus.QUEUED/RUNNING/DONE/FAILED/TIMEOUT`（含 `isTerminal()`）；`ClaimType.FACT/SYNTHESIS/GEN`（含 `symbol()` 返回 `●/◐/○`）——后续所有模块枚举一律引用此处，禁止字符串字面量

- [ ] **Step 1: 写失败测试**

```java
package com.ke.domain.enums;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentRunStatusTest {
    @Test
    void queuedCanGoRunningButNotDone() {
        assertThat(AgentRunStatus.QUEUED.canTransitionTo(AgentRunStatus.RUNNING)).isTrue();
        assertThat(AgentRunStatus.QUEUED.canTransitionTo(AgentRunStatus.DONE)).isFalse();
    }

    @Test
    void terminalStatesAreDoneFailedTimeout() {
        assertThat(AgentRunStatus.DONE.isTerminal()).isTrue();
        assertThat(AgentRunStatus.RUNNING.isTerminal()).isFalse();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -B -ntp -pl ke-domain test` → Expected: FAIL（类不存在）

- [ ] **Step 3: 实现五个枚举**

```java
package com.ke.domain.enums;

public enum AgentRunStatus {
    QUEUED, RUNNING, DONE, FAILED, TIMEOUT;

    private static final java.util.Map<AgentRunStatus, java.util.Set<AgentRunStatus>> ALLOWED =
        java.util.Map.of(
            QUEUED, java.util.Set.of(RUNNING),
            RUNNING, java.util.Set.of(DONE, FAILED, TIMEOUT));

    public boolean canTransitionTo(AgentRunStatus next) {
        return ALLOWED.getOrDefault(this, java.util.Set.of()).contains(next);
    }

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == TIMEOUT;
    }
}
```

```java
package com.ke.domain.enums;

public enum CardStatus {
    DRAFT, PENDING, PUBLISHED, DISABLED;

    private static final java.util.Map<CardStatus, CardStatus> ALLOWED_FROM =
        java.util.Map.of(PENDING, DRAFT, PUBLISHED, PENDING, DISABLED, PUBLISHED);

    /** 仅允许编辑/运营执行的状态流转（W2 审核模块使用，方向：目标 ← 来源） */
    public boolean canComeFrom(CardStatus from) {
        return ALLOWED_FROM.get(this) == from;
    }
}
```

```java
package com.ke.domain.enums;

public enum UserRole { EXPLORER, CREATOR, EDITOR, OPERATOR }
```

```java
package com.ke.domain.enums;

/** 一期入口白名单（FR-N07）：自然语言抽取只允许这三类 */
public enum EntryType { LINK_CARD, AGENT_SERVICE, COMPARE }
```

```java
package com.ke.domain.enums;

/** 三档可信（FR-S05）：符号与颜色双编码，symbol 供前端渲染（04 §2.3） */
public enum ClaimType {
    FACT("●"), SYNTHESIS("◐"), GEN("○");

    private final String symbol;
    ClaimType(String symbol) { this.symbol = symbol; }
    public String symbol() { return symbol; }
}
```

补 `CardStatusTest`（`canComeFrom`：PUBLISHED←PENDING true、PUBLISHED←DRAFT false）与 `ClaimTypeTest`（symbol 断言）。

- [ ] **Step 4: 运行至通过**

Run: `./mvnw -B -ntp -pl ke-domain test` → Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ke-domain
git commit -m "feat: ke-domain 领域枚举与状态机守卫"
```

---

### Task 4: 认证 API（注册/登录/刷新/me）+ JWT + 错误 envelope

**Files:**
- Create: `ke-infra/src/main/java/com/ke/infra/entity/KeUserEntity.java`
- Create: `ke-infra/src/main/java/com/ke/infra/mapper/KeUserMapper.java`
- Create: `ke-service/src/main/java/com/ke/service/common/ApiResponse.java`
- Create: `ke-service/src/main/java/com/ke/service/common/GlobalExceptionHandler.java`
- Create: `ke-service/src/main/java/com/ke/service/auth/JwtService.java`
- Create: `ke-service/src/main/java/com/ke/service/auth/AuthService.java`
- Create: `ke-service/src/main/java/com/ke/service/auth/AuthController.java`
- Create: `ke-service/src/main/java/com/ke/service/auth/JwtAuthFilter.java`
- Create: `ke-service/src/main/java/com/ke/service/auth/SecurityConfig.java`
- Create: `ke-boot/src/test/resources/application-test.yml`
- Test: `ke-boot/src/test/java/com/ke/auth/AuthFlowIT.java`

**Interfaces:**
- Consumes: Task 1 模块结构、Task 2 `ke_user` 表、Task 3 `UserRole`
- Produces: `ApiResponse.ok(data)` / `ApiResponse.error(code,message)`；`JwtService.issueAccess(Long,String)`、`parse(String): Jwts Claims`；端点 `POST /api/auth/register` `{phone,password,nickname}` → 201、`POST /api/auth/login` → `{accessToken,refreshToken}`、`POST /api/auth/refresh` `{refreshToken}` → 新对、`GET /api/me` → `{id,nickname,role}`；HTTP 状态 = 语义状态，业务码在 envelope

- [ ] **Step 1: 写失败的端到端认证流测试**

```java
package com.ke.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuthFlowIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg15");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired WebTestClient http;

    @Test
    void registerLoginMeRoundtrip() {
        var reg = """{"phone":"13800000001","password":"passw0rd!","nickname":"探索者"}""";
        http.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON).bodyValue(reg)
            .exchange().expectStatus().isCreated();

        var loginResult = http.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"phone":"13800000001","password":"passw0rd!"}""")
            .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        assertThat(loginResult.getResponseBody()).contains("accessToken");
        String accessToken = com.jayway.jsonpath.JsonPath.read(loginResult.getResponseBody(), "$.data.accessToken");

        http.get().uri("/api/me").headers(h -> h.setBearerAuth(accessToken))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.data.nickname").isEqualTo("探索者");
    }

    @Test
    void wrongPasswordReturns401Envelope() {
        http.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"phone":"13800000002","password":"passw0rd!","nickname":"乙"}""")
            .exchange().expectStatus().isCreated();
        http.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"phone":"13800000002","password":"wrong"}""")
            .exchange().expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.code").exists().jsonPath("$.traceId").exists();
    }
}
```

> `com.jayway.jsonpath:json-path` 由 `spring-boot-starter-test` 传递提供，无需额外依赖；`assertThat` 需 `import static org.assertj.core.api.Assertions.assertThat;`。

`ke-boot/src/test/resources/application-test.yml`：

```yaml
spring:
  datasource:
    url: ${spring.datasource.url}   # 由 DynamicPropertySource 注入
  flyway:
    enabled: true
ke:
  jwt:
    secret: test-secret-key-32-bytes!!test-only!!
    access-ttl: 2h
    refresh-ttl: 7d
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -B -ntp -pl ke-boot test -Dtest=AuthFlowIT` → Expected: FAIL（404，无控制器）

- [ ] **Step 3: 实现实体、Mapper、服务与安全链**

`KeUserEntity.java`：

```java
package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("ke_user")
public class KeUserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String passwordHash;
    private String nickname;
    private String role;      // UserRole.name()
    private String status;
    private OffsetDateTime createdAt;
    // getter/setter 省略不得省略——生成全部 getter/setter（IDE 生成或手写）
}
```

`KeUserMapper.java`：

```java
package com.ke.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ke.infra.entity.KeUserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KeUserMapper extends BaseMapper<KeUserEntity> {}
```

`ApiResponse.java`：

```java
package com.ke.service.common;

public record ApiResponse<T>(int code, String message, String traceId, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(0, "ok", TraceId.current(), data); }
    public static ApiResponse<Void> error(int code, String message) { return new ApiResponse<>(code, message, TraceId.current(), null); }
}
```

`TraceId.java`（同包）：

```java
package com.ke.service.common;

import java.util.UUID;

public final class TraceId {
    private static final ThreadLocal<String> CTX = new ThreadLocal<>();
    public static String current() {
        String v = CTX.get();
        if (v == null) { v = UUID.randomUUID().toString().substring(0, 8); CTX.set(v); }
        return v;
    }
    public static void clear() { CTX.remove(); }
}
```

`JwtService.java`：

```java
package com.ke.service.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(@Value("${ke.jwt.secret}") String secret,
                      @Value("${ke.jwt.access-ttl}") Duration accessTtl,
                      @Value("${ke.jwt.refresh-ttl}") Duration refreshTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public String issueAccess(Long userId, String role) { return issue(userId, role, "access", accessTtl); }
    public String issueRefresh(Long userId) { return issue(userId, null, "refresh", refreshTtl); }

    private String issue(Long userId, String role, String typ, Duration ttl) {
        var builder = Jwts.builder()
            .subject(String.valueOf(userId)).claim("typ", typ)
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plus(ttl)))
            .signWith(key);
        if (role != null) builder.claim("role", role);
        return builder.compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
```

`AuthService.java`：

```java
package com.ke.service.auth;

import com.ke.infra.entity.KeUserEntity;
import com.ke.infra.mapper.KeUserMapper;
import com.ke.service.common.BadRequestException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuthService {
    private final KeUserMapper users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(KeUserMapper users, JwtService jwt) { this.users = users; this.jwt = jwt; }

    public KeUserEntity register(String phone, String password, String nickname) {
        if (users.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KeUserEntity>()
                .eq(KeUserEntity::getPhone, phone)) != null) {
            throw new BadRequestException("该手机号已注册");
        }
        KeUserEntity u = new KeUserEntity();
        u.setPhone(phone);
        u.setPasswordHash(encoder.encode(password));
        u.setNickname(nickname);
        u.setRole("EXPLORER");
        u.setStatus("ACTIVE");
        users.insert(u);
        return u;
    }

    public Map<String, String> login(String phone, String password) {
        KeUserEntity u = users.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KeUserEntity>()
            .eq(KeUserEntity::getPhone, phone));
        if (u == null || !encoder.matches(password, u.getPasswordHash())) {
            throw new UnauthorizedException("手机号或密码错误");
        }
        return Map.of(
            "accessToken", jwt.issueAccess(u.getId(), u.getRole()),
            "refreshToken", jwt.issueRefresh(u.getId()));
    }

    public Map<String, String> refresh(String refreshToken) {
        var claims = jwt.parse(refreshToken);
        if (!"refresh".equals(claims.get("typ"))) throw new UnauthorizedException("无效的刷新令牌");
        Long userId = Long.valueOf(claims.getSubject());
        KeUserEntity u = users.selectById(userId);
        return Map.of("accessToken", jwt.issueAccess(u.getId(), u.getRole()),
                      "refreshToken", jwt.issueRefresh(u.getId()));
    }
}
```

同包异常类（各 3 行）：`BadRequestException extends RuntimeException`、`UnauthorizedException extends RuntimeException`。

`AuthController.java`：

```java
package com.ke.service.auth;

import com.ke.infra.entity.KeUserEntity;
import com.ke.service.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    public record RegisterReq(@NotBlank String phone, @NotBlank String password, @NotBlank String nickname) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterReq req) {
        KeUserEntity u = auth.register(req.phone(), req.password(), req.nickname());
        return ApiResponse.ok(Map.of("id", u.getId(), "nickname", u.getNickname(), "role", u.getRole()));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody RegisterReq req) {
        return ApiResponse.ok(auth.login(req.phone(), req.password()));
    }

    public record RefreshReq(@NotBlank String refreshToken) {}

    @PostMapping("/refresh")
    public ApiResponse<Map<String, String>> refresh(@Valid @RequestBody RefreshReq req) {
        return ApiResponse.ok(auth.refresh(req.refreshToken()));
    }
}
```

`MeController.java`（同包）：

```java
package com.ke.service.auth;

import com.ke.infra.entity.KeUserEntity;
import com.ke.infra.mapper.KeUserMapper;
import com.ke.service.common.ApiResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MeController {
    private final KeUserMapper users;
    public MeController(KeUserMapper users) { this.users = users; }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        long uid = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        KeUserEntity u = users.selectById(uid);
        return ApiResponse.ok(Map.of("id", u.getId(), "nickname", u.getNickname(), "role", u.getRole()));
    }
}
```

`JwtAuthFilter.java`：

```java
package com.ke.service.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    public JwtAuthFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws java.io.IOException, jakarta.servlet.ServletException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var claims = jwt.parse(header.substring(7));
                if ("access".equals(claims.get("typ"))) {
                    var auth = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + claims.get("role"))));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) { /* 无效令牌按未认证处理 */ }
        }
        chain.doFilter(req, res);
    }
}
```

`SecurityConfig.java`：

```java
package com.ke.service.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtAuthFilter jwtFilter;

    public SecurityConfig(JwtAuthFilter jwtFilter) { this.jwtFilter = jwtFilter; }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                 "/actuator/health").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

`GlobalExceptionHandler.java`：

```java
package com.ke.service.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, e.getMessage()));
    }

    @ExceptionHandler({UnauthorizedException.class, org.springframework.security.core.AuthenticationException.class})
    public ResponseEntity<ApiResponse<Void>> unauthorized(Exception e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "未认证或凭证无效"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .findFirst().map(f -> f.getField() + " " + f.getDefaultMessage()).orElse("参数错误");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(500, "服务暂时不可用"));
    }
}
```

MyBatis-Plus 需要扫 mapper：`ke-boot` 的 `KeApplication` 加 `@MapperScan("com.ke.infra.mapper")`（import `org.mybatis.spring.annotation.MapperScan`）。

- [ ] **Step 4: 运行测试至通过**

Run: `./mvnw -B -ntp -pl ke-boot test`
Expected: `AuthFlowIT` 2 个测试 PASS，`BootApplicationTest` 仍 PASS

- [ ] **Step 5: 手工冒烟（dev）**

```bash
docker compose -f docker-compose.dev.yml up -d postgres
./mvnw -B -ntp -pl ke-boot spring-boot:run
# 另开终端：
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"phone":"13800000003","password":"passw0rd!","nickname":"演示"}'
```

Expected: 201 + envelope；用返回流程登录拿 token 访问 `/api/me`。

- [ ] **Step 6: Commit**

```bash
git add ke-infra ke-service ke-boot
git commit -m "feat: 注册登录/刷新/me 与 JWT 安全链、统一错误 envelope"
```

---

### Task 5: LlmGateway 接口 + Spring AI 双档实现 + Mock

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/llm/LlmGateway.java`
- Create: `ke-service/src/main/java/com/ke/service/llm/ChatCommand.java`
- Create: `ke-service/src/main/java/com/ke/service/llm/ModelTier.java`
- Create: `ke-service/src/main/java/com/ke/service/llm/SpringAiLlmGateway.java`
- Create: `ke-service/src/main/java/com/ke/service/llm/MockLlmGateway.java`
- Modify: `ke-boot/src/main/resources/application-dev.yml`（追加 `ke.llm` 与 `spring.ai` 配置）
- Test: `ke-boot/src/test/java/com/ke/llm/SpringAiLlmGatewayIT.java`

**Interfaces:**
- Consumes: Task 1 的 spring-ai 依赖
- Produces: `record ChatCommand(String system, String user, ModelTier tier)`；`interface LlmGateway { String complete(ChatCommand command); }`；`enum ModelTier { ROUTER, GENERATOR }`；Mock 实现固定返回 `"{\"summary\":\"mock\"}"`（profile `test` 生效）；配置键 `ke.llm.router-model` / `ke.llm.generator-model` / `spring.ai.openai.base-url` / `spring.ai.openai.api-key`（Task 6 消费 `LlmGateway`）

- [ ] **Step 1: 写失败的 MockWebServer 测试**

```java
package com.ke.llm;

import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mockllm")   // 不激活 test 的 MockLlmGateway，用真实 SpringAi 实现
@Testcontainers
class SpringAiLlmGatewayIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg15");

    static MockWebServer llm;

    @BeforeAll
    static void startLlm() throws Exception {
        llm = new MockWebServer();
        llm.enqueue(new MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {"choices":[{"message":{"role":"assistant","content":"你好，来自 mock LLM"}}]}
                """));
        llm.start();
    }

    @AfterAll
    static void stopLlm() throws Exception { llm.shutdown(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.ai.openai.base-url", () -> llm.url("/v1").toString());
        r.add("spring.ai.openai.api-key", () -> "dummy");
    }

    @Autowired LlmGateway gateway;

    @Test
    void generatorTierCallsOpenAiCompatibleEndpoint() throws Exception {
        String out = gateway.complete(new ChatCommand("你是讲解员", "解释一句话", ModelTier.GENERATOR));
        assertThat(out).isEqualTo("你好，来自 mock LLM");
        var recorded = llm.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getBody().readUtf8()).contains("解释一句话");
    }
}
```

`ke-boot/src/test/resources/application-mockllm.yml`（复制 `application-test.yml` 内容，确保 Flyway 与 JWT 配置存在）。

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -B -ntp -pl ke-boot test -Dtest=SpringAiLlmGatewayIT` → Expected: 编译 FAIL（`LlmGateway` 不存在）

- [ ] **Step 3: 实现网关**

`ModelTier.java` / `ChatCommand.java`：

```java
package com.ke.service.llm;

public enum ModelTier { ROUTER, GENERATOR }
```

```java
package com.ke.service.llm;

public record ChatCommand(String system, String user, ModelTier tier) {}
```

`LlmGateway.java`：

```java
package com.ke.service.llm;

/** 02 §12.4：所有 LLM 调用收口于此接口；集成测试用 Mock，不依赖真实 API */
public interface LlmGateway {
    String complete(ChatCommand command);
}
```

`SpringAiLlmGateway.java`：

```java
package com.ke.service.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient generator;
    private final ChatClient router;

    public SpringAiLlmGateway(ChatModel chatModel,
                              @Value("${ke.llm.generator-model}") String generatorModel,
                              @Value("${ke.llm.router-model}") String routerModel) {
        this.generator = ChatClient.builder(chatModel)
            .defaultOptions(OpenAiChatOptions.builder().model(generatorModel).build()).build();
        this.router = ChatClient.builder(chatModel)
            .defaultOptions(OpenAiChatOptions.builder().model(routerModel).build()).build();
    }

    @Override
    public String complete(ChatCommand command) {
        ChatClient client = command.tier() == ModelTier.GENERATOR ? generator : router;
        return client.prompt().system(command.system()).user(command.user()).call().content();
    }
}
```

`MockLlmGateway.java`：

```java
package com.ke.service.llm;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class MockLlmGateway implements LlmGateway {
    @Override
    public String complete(ChatCommand command) {
        return "{\"summary\":\"mock\",\"tier\":\"" + command.tier() + "\"}";
    }
}
```

`application-dev.yml` 追加：

```yaml
spring:
  ai:
    openai:
      base-url: ${KE_LLM_BASE_URL:https://api.open.bigmodel.cn}
      api-key: ${KE_LLM_API_KEY:dummy}

ke:
  llm:
    generator-model: ${KE_LLM_GENERATOR:glm-4-air}
    router-model: ${KE_LLM_ROUTER:glm-4-flash}
```

- [ ] **Step 4: 运行测试至通过**

Run: `./mvnw -B -ntp -pl ke-boot test -Dtest=SpringAiLlmGatewayIT` → Expected: PASS（真实请求打到 MockWebServer 并断言了路径与请求体）

- [ ] **Step 5: Commit**

```bash
git add ke-service ke-boot
git commit -m "feat: llm 网关接口与 spring ai 双档实现（mock 可切换）"
```

---

### Task 6: agent_run 异步执行骨架（虚拟线程 + 心跳回收）

**Files:**
- Create: `ke-infra/src/main/java/com/ke/infra/entity/AgentRunEntity.java`
- Create: `ke-infra/src/main/java/com/ke/infra/mapper/AgentRunMapper.java`
- Create: `ke-service/src/main/java/com/ke/service/agent/AgentRunService.java`
- Create: `ke-service/src/main/java/com/ke/service/agent/AgentExecutorConfig.java`
- Test: `ke-boot/src/test/java/com/ke/agent/AgentRunIT.java`

**Interfaces:**
- Consumes: `LlmGateway.complete(ChatCommand)`（Task 5）、`AgentRunStatus`（Task 3）、`agent_run` 表（Task 2）
- Produces: `AgentRunService.submit(Long sessionId, String serviceType, String userInput): Long`（落库 QUEUED 并异步执行，返回 runId）；`GET /api/agent/runs/{id}` 查询状态（W2 起正式轮询端点复用此服务）；状态流转严格走 `canTransitionTo`

- [ ] **Step 1: 写失败的往返测试**

```java
package com.ke.agent;

import com.ke.service.agent.AgentRunService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AgentRunIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg15");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired AgentRunService runs;

    @Test
    void submitReachesDoneAndRecordsLatency() {
        Long runId = runs.submit(null, "explain", "为什么岳麓书院建在岳麓山下？");
        Awaitility.await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                var run = runs.get(runId);
                assertThat(run.getStatus()).isEqualTo("DONE");
                assertThat(run.getLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
                assertThat(run.getModel()).isNotBlank();
            });
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -B -ntp -pl ke-boot test -Dtest=AgentRunIT` → Expected: 编译 FAIL

- [ ] **Step 3: 实现实体、Mapper、执行器**

`AgentRunEntity.java`（`@TableName("agent_run")`，字段与 V1 列一一对应：id/sessionId/nodeId/serviceType/inputJson/status/artifactIds/model/tokensIn/tokensOut/cost/latencyMs/error/heartbeatAt/createdAt/updatedAt，全 getter/setter）。

`AgentRunMapper.java`：

```java
package com.ke.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ke.infra.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    @Update("update agent_run set status='TIMEOUT', error='heartbeat expired', updated_at=now() " +
            "where status='RUNNING' and heartbeat_at < now() - make_interval(secs => #{staleSeconds})")
    int recycleStale(@Param("staleSeconds") int staleSeconds);

    @Update("update agent_run set heartbeat_at=now() where id=#{id}")
    int touch(@Param("id") Long id);
}
```

`AgentExecutorConfig.java`：

```java
package com.ke.service.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.Executors;

/** 02 §5.1：LLM 秒级阻塞 IO，每任务一个虚拟线程，无需大线程池 */
@Configuration
public class AgentExecutorConfig {
    @Bean("agentExecutor")
    public TaskExecutor agentExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
```

`AgentRunService.java`：

```java
package com.ke.service.agent;

import com.ke.domain.enums.AgentRunStatus;
import com.ke.infra.entity.AgentRunEntity;
import com.ke.infra.mapper.AgentRunMapper;
import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AgentRunService {

    private final AgentRunMapper runs;
    private final LlmGateway llm;

    @Value("${ke.llm.generator-model:unknown}")
    private String generatorModel;

    @Value("${ke.agent.stale-seconds:120}")
    private int staleSeconds;

    public AgentRunService(AgentRunMapper runs, LlmGateway llm) {
        this.runs = runs; this.llm = llm;
    }

    public AgentRunEntity get(Long id) { return runs.selectById(id); }

    public Long submit(Long sessionId, String serviceType, String userInput) {
        AgentRunEntity run = new AgentRunEntity();
        run.setSessionId(sessionId);
        run.setServiceType(serviceType);
        run.setInputJson(com.baomidou.mybatisplus.extension.toolkit.JsonKit.toJsonString(
            java.util.Map.of("input", userInput == null ? "" : userInput)));
        run.setStatus(AgentRunStatus.QUEUED.name());
        runs.insert(run);
        execute(run.getId(), userInput);
        return run.getId();
    }

    @Async("agentExecutor")
    void execute(Long runId, String userInput) {
        transition(runId, AgentRunStatus.RUNNING, null);
        long start = System.currentTimeMillis();
        try {
            String out = llm.complete(new ChatCommand("你是知识讲解员，只依据给定资料回答。",
                userInput, ModelTier.GENERATOR));
            AgentRunEntity done = runs.selectById(runId);
            done.setStatus(AgentRunStatus.DONE.name());
            done.setLatencyMs((int) (System.currentTimeMillis() - start));
            done.setModel(generatorModel);
            done.setArtifactIds(com.baomidou.mybatisplus.extension.toolkit.JsonKit.toJsonString(
                java.util.List.of()));
            runs.updateById(done);
        } catch (Exception e) {
            AgentRunEntity failed = runs.selectById(runId);
            failed.setStatus(AgentRunStatus.FAILED.name());
            failed.setError(e.getMessage());
            runs.updateById(failed);
        }
    }

    /** RUNNING→DONE/FAILED 之外还要写业务字段，此处仅守护状态合法迁移 */
    private void transition(Long runId, AgentRunStatus next, String error) {
        AgentRunEntity run = runs.selectById(runId);
        AgentRunStatus current = AgentRunStatus.valueOf(run.getStatus());
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("非法状态迁移 " + current + "->" + next);
        }
        run.setStatus(next.name());
        if (error != null) run.setError(error);
        runs.updateById(run);
    }

    /** 心跳回收：W4 接 Resilience4j 60s 超时后，此兜底防卡死（02 §5.1） */
    @Scheduled(fixedDelayString = "${ke.agent.recycle-interval-ms:60000}")
    public void recycleStale() {
        runs.recycleStale(staleSeconds);
    }
}
```

- [ ] **Step 4: 运行测试至通过**

Run: `./mvnw -B -ntp -pl ke-boot test -Dtest=AgentRunIT` → Expected: PASS（Mock 网关秒回，DONE + latency 记录）

- [ ] **Step 5: Commit**

```bash
git add ke-infra ke-service ke-boot
git commit -m "feat: agent_run 虚拟线程异步执行骨架与心跳回收"
```

---

### Task 7: 前端 pnpm monorepo 骨架（双端壳 + lint 门槛）

**Files:**
- Create: `frontend/package.json`、`frontend/pnpm-workspace.yaml`、`frontend/tsconfig.base.json`
- Create: `frontend/eslint.config.js`、`frontend/.stylelintrc.json`
- Create: `frontend/apps/explorer/package.json`、`vite.config.ts`、`index.html`、`src/main.ts`、`src/App.vue`、`src/pages/Home.vue`
- Create: `frontend/apps/workbench/package.json`、`vite.config.ts`、`index.html`、`src/main.ts`、`src/App.vue`
- Modify: 根 `.gitignore`（追加 `node_modules/`、`dist/`）
- Test: `frontend/apps/explorer/src/__tests__/Home.spec.ts`、`frontend/apps/workbench/src/__tests__/App.spec.ts`

**Interfaces:**
- Produces: workspace 包 `@ke/explorer`、`@ke/workbench`（Task 8 的 `@ke/shared` 以 `workspace:*` 依赖接入）；根脚本 `pnpm --dir frontend lint|test|build`（Task 10 CI 消费）；stylelint 规则禁止 `.vue/.css` 内裸色值

- [ ] **Step 1: 建 workspace 与工具链**

`frontend/pnpm-workspace.yaml`：

```yaml
packages:
  - "packages/*"
  - "apps/*"
```

`frontend/package.json`：

```json
{
  "name": "ke-frontend",
  "private": true,
  "engines": { "node": ">=20", "pnpm": ">=9" },
  "scripts": {
    "lint": "eslint . && stylelint \"apps/**/*.{vue,css}\" \"packages/**/*.{vue,css}\"",
    "test": "pnpm -r --if-present test",
    "build": "pnpm -r --if-present build"
  },
  "devDependencies": {
    "eslint": "^9.12.0",
    "eslint-plugin-vue": "^9.29.0",
    "stylelint": "^16.10.0",
    "stylelint-config-standard": "^36.0.1",
    "typescript": "^5.6.0",
    "typescript-eslint": "^8.10.0",
    "vue-tsc": "^2.1.0"
  }
}
```

`frontend/tsconfig.base.json`：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "jsx": "preserve",
    "skipLibCheck": true,
    "paths": { "@ke/shared": ["../packages/shared/src/index.ts"] }
  }
}
```

`frontend/eslint.config.js`：

```js
import pluginVue from 'eslint-plugin-vue';
import tseslint from 'typescript-eslint';

export default [
  { ignores: ['**/dist/**', '**/node_modules/**'] },
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    rules: {
      'vue/multi-word-component-names': 'off'
    }
  }
];
```

`frontend/.stylelintrc.json`：

```json
{
  "extends": "stylelint-config-standard",
  "ignoreFiles": ["**/tokens.css", "**/theme-*.css"],
  "rules": {
    "declaration-property-value-disallowed-list": {
      "color": ["/^#/", "/^rgb/a?\\(/", "/^hsl/a?\\(/"],
      "background-color": ["/^#/", "/^rgb/a?\\(/"],
      "border-color": ["/^#/"]
    }
  }
}
```

- [ ] **Step 2: 写失败的冒烟测试（先于页面代码提交）**

`frontend/apps/explorer/src/__tests__/Home.spec.ts`：

```ts
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import Home from '../pages/Home.vue';

describe('Home', () => {
  it('渲染品牌标题与三专题', () => {
    const wrapper = mount(Home);
    expect(wrapper.find('h1').text()).toBe('知识探索');
    expect(wrapper.text()).toContain('书院地标');
    expect(wrapper.text()).toContain('湘菜风物');
    expect(wrapper.text()).toContain('声音科学');
  });
});
```

`frontend/apps/workbench/src/__tests__/App.spec.ts`：

```ts
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import App from '../App.vue';

describe('Workbench App', () => {
  it('渲染侧栏导航', () => {
    const wrapper = mount(App, { global: { stubs: { 'router-view': true } } });
    expect(wrapper.text()).toContain('卡片管理');
    expect(wrapper.text()).toContain('审核中心');
  });
});
```

- [ ] **Step 3: 建 explorer 应用**

`frontend/apps/explorer/package.json`：

```json
{
  "name": "@ke/explorer",
  "private": true,
  "scripts": { "build": "vite build", "test": "vitest run" },
  "dependencies": {
    "vant": "^4.9.0",
    "vue": "^3.5.0",
    "vue-router": "^4.5.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.1.0",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.0",
    "vite": "^6.0.0",
    "vitest": "^3.0.0"
  }
}
```

`vite.config.ts`：

```ts
import { fileURLToPath, URL } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@ke/shared': fileURLToPath(new URL('../../packages/shared/src/index.ts', import.meta.url)) }
  },
  test: { environment: 'jsdom' }
});
```

`index.html`：标准 Vite 模板（`<div id="app">` + `/src/main.ts`，`<title>知识探索</title>`）。

`src/main.ts`：

```ts
import { createApp } from 'vue';
import App from './App.vue';

createApp(App).mount('#app');
```

`src/App.vue`（W1 壳，无路由；W2 接 vue-router 与 Vant）：

```vue
<script setup lang="ts">
import Home from './pages/Home.vue';
</script>

<template>
  <Home />
</template>
```

`src/pages/Home.vue`（样式全部走 CSS 变量，Task 8 前 `--ke-*` 由 vite 内联兜底? 不——**本步骤先引用变量，Task 8 落 tokens 后生效**；为保测试可跑，颜色缺失仅影响外观不影响断言）：

```vue
<template>
  <div class="page">
    <h1 class="title">知识探索</h1>
    <p class="sub">从一张卡片出发，逐层深入，随时回望</p>
    <div class="themes">
      <div class="theme"><b>书院地标</b><span>12 张卡</span></div>
      <div class="theme"><b>湘菜风物</b><span>9 张卡</span></div>
      <div class="theme"><b>声音科学</b><span>7 张卡</span></div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--ke-bg, #f7f3eb); padding: 24px 16px; }
.title { font-family: var(--ke-font-display, serif); font-weight: 900; font-size: 32px; margin: 0; color: var(--ke-ink, #26221d); }
.sub { color: var(--ke-sub, #6e675c); margin: 8px 0 24px; }
.themes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.theme { background: var(--ke-surface, #fff); border: 1px solid var(--ke-line, #e7e1d4); border-radius: var(--ke-radius-m, 8px); padding: 14px 8px; text-align: center; }
.theme span { display: block; font-size: 11px; color: var(--ke-sub, #6e675c); margin-top: 4px; }
</style>
```

> 说明：本文件里的 `#f7f3eb` 等十六进制是 `var()` 的**回退值**，属于豁免场景——但为通过 stylelint，请改写为不带回退的 `var(--ke-bg)` 并在本 Task 结束前完成 Task 8 的 tokens 引入（两任务同日完成）。若 stylelint 报错，回退值临时用 `/* stylelint-disable-line declaration-property-value-disallowed-list */` 标注并在 Task 8 移除。

- [ ] **Step 4: 建 workbench 应用**

`package.json` 同 explorer（依赖换 `element-plus: ^2.10.0`，devDependencies 相同）；`vite.config.ts` 同（alias 一致）；`index.html` title「知识探索 · 工作台」。

`src/main.ts`：

```ts
import { createApp } from 'vue';
import App from './App.vue';

createApp(App).mount('#app');
```

`src/App.vue`：

```vue
<template>
  <div class="wb">
    <aside class="side">
      <div class="logo">知识探索 · 工作台</div>
      <nav>
        <a class="on">卡片管理</a>
        <a>入口编排</a>
        <a>审核中心</a>
      </nav>
    </aside>
    <main class="body">占位：卡片管理</main>
  </div>
</template>

<style scoped>
.wb { display: flex; height: 100vh; }
.side { width: 220px; background: var(--ke-side, #262119); color: var(--ke-side-text, #b0a794); padding: 18px 12px; }
.logo { color: #fff; font-weight: 700; padding: 0 10px 14px; border-bottom: 1px solid var(--ke-side-line, #3b342a); }
nav a { display: block; padding: 10px 12px; border-radius: var(--ke-radius-s, 5px); font-size: 13px; }
nav a.on { background: var(--ke-primary, #3a5fcd); color: #fff; }
.body { flex: 1; padding: 24px; background: var(--ke-bg, #f7f3eb); }
</style>
```

（回退值豁免同上，Task 8 移除。）

- [ ] **Step 5: 安装、测试、构建全绿**

```bash
pnpm --dir frontend install
pnpm --dir frontend test
pnpm --dir frontend build
```

Expected: 两个 vitest 冒烟 PASS；两个 vite build 成功；`pnpm lint` 通过（stylelint 对回退值按上文豁免处理）。

- [ ] **Step 6: Commit**

```bash
git add frontend .gitignore
git commit -m "feat: 前端 pnpm monorepo 骨架与双端应用壳"
```

---

### Task 8: @ke/shared——Token、图标集与四个语义组件

**Files:**
- Create: `frontend/packages/shared/package.json`
- Create: `frontend/packages/shared/src/tokens.css`（从 `docs/04-主题与UI规范.md` §11.1 代码块**原文复制**）
- Create: `frontend/packages/shared/src/styles/theme-vant.css`、`theme-element.css`（从 04 §11.2/§11.3 原文复制）
- Create: `frontend/packages/shared/src/icons/sprite.ts`（sprite HTML 字符串，取自 `prototype/styleguide.html` 的 `<defs>` 块全部 23 个 `<symbol>`）
- Create: `frontend/packages/shared/src/components/KeIcon.vue`、`ClaimBadge.vue`、`CitationTag.vue`、`SourceList.vue`、`PhaseTag.vue`
- Create: `frontend/packages/shared/src/index.ts`
- Modify: `frontend/apps/explorer/src/main.ts`、`frontend/apps/workbench/src/main.ts`（引入 tokens/theme 与 sprite 注入）；移除 Task 7 的样式回退值与豁免注释
- Test: `frontend/packages/shared/src/__tests__/ClaimBadge.spec.ts` 等（每组件一个）

**Interfaces:**
- Produces: `@ke/shared` 导出 `KeIcon`（prop `name: string`，渲染 `<use href="#i-<name>">`）、`ClaimBadge`（prop `type: 'fact'|'synth'|'gen'`）、`CitationTag`（prop `index: number`，emit `click`）、`SourceList`（props `sources: {index:number;title:string;locator:string;license?:string}[]`、`gap?: string`）、`PhaseTag`（props `phase: 'P2'|'P3'`、`label?: string`）；`installSprite()`（把图标 `<svg>` 注入 body）；CSS 变量全集 `--ke-*`

- [ ] **Step 1: 写失败的组件测试**

`ClaimBadge.spec.ts`：

```ts
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ClaimBadge from '../components/ClaimBadge.vue';

describe('ClaimBadge', () => {
  it.each([
    ['fact', '●', '事实', '事实陈述，有出处'],
    ['synth', '◐', '归纳', '归纳推断，基于出处综合'],
    ['gen', '○', '生成', '生成内容，仅供参考'],
  ])('%s 档渲染符号+文案+读屏释义', (type, symbol, label, aria) => {
    const wrapper = mount(ClaimBadge, { props: { type } });
    expect(wrapper.text()).toContain(symbol);
    expect(wrapper.text()).toContain(label);
    expect(wrapper.attributes('aria-label')).toBe(aria);
  });
});
```

`PhaseTag.spec.ts`：

```ts
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import PhaseTag from '../components/PhaseTag.vue';

describe('PhaseTag', () => {
  it('渲染分期文案', () => {
    expect(mount(PhaseTag, { props: { phase: 'P2' } }).text()).toBe('二期');
    expect(mount(PhaseTag, { props: { phase: 'P3' } }).text()).toBe('三期');
  });
  it('可覆盖标签文案', () => {
    expect(mount(PhaseTag, { props: { phase: 'P2', label: '二期 · 地图卡' } }).text()).toBe('二期 · 地图卡');
  });
});
```

`CitationTag.spec.ts`：

```ts
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import CitationTag from '../components/CitationTag.vue';

describe('CitationTag', () => {
  it('渲染角标并响应点击', async () => {
    const wrapper = mount(CitationTag, { props: { index: 2 } });
    expect(wrapper.text()).toBe('[2]');
    expect(wrapper.attributes('role')).toBe('button');
    await wrapper.trigger('click');
    expect(wrapper.emitted('click')).toHaveLength(1);
  });
});
```

`SourceList.spec.ts`：

```ts
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import SourceList from '../components/SourceList.vue';

describe('SourceList', () => {
  it('渲染出处行与证据缺口警示行', () => {
    const wrapper = mount(SourceList, {
      props: {
        sources: [{ index: 1, title: '岳麓书院史略', locator: '第一章 p12–14', license: '已授权' }],
        gap: '原始文献记载有限，结论主要为现代学者归纳'
      }
    });
    expect(wrapper.text()).toContain('[1] 岳麓书院史略 · 第一章 p12–14（已授权）');
    expect(wrapper.text()).toContain('证据缺口');
    expect(wrapper.find('.gap').attributes('class')).toContain('gap');
  });
});
```

- [ ] **Step 2: 建 shared 包与组件**

`frontend/packages/shared/package.json`：

```json
{
  "name": "@ke/shared",
  "version": "0.1.0",
  "private": true,
  "main": "src/index.ts",
  "dependencies": { "vue": "^3.5.0" },
  "devDependencies": {
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.0",
    "vitest": "^3.0.0"
  },
  "scripts": { "test": "vitest run" }
}
```

（根 `tsconfig.base.json` 的 paths 已指向 `src/index.ts`；shared 也需要自己的 `vite.config.ts` 提供 `test.environment: 'jsdom'`，内容与 explorer 相同但无 alias。）

`tokens.css` / `theme-vant.css` / `theme-element.css`：**打开 `docs/04-主题与UI规范.md` §11.1–11.3，逐字符复制代码块**（这是唯一取值来源，Global Constraints 禁止手抄改值）。`tokens.css` 复制时去掉外层 `:root{}` 包裹——保留包裹原样即可（页面级注入）。

`icons/sprite.ts`：

```ts
// 取值来源：prototype/styleguide.html 的 <svg width="0" ...><defs>…</defs></svg> 整块
// 复制 <defs> 内全部 23 个 <symbol>（id 清单见下方断言），包装为导出常量
export const SPRITE_HTML = `<svg xmlns="http://www.w3.org/2000/svg" style="position:absolute;width:0;height:0" aria-hidden="true"><defs>
/* ← 此处粘贴 styleguide.html 中 <defs> 与 </defs> 之间的全部 <symbol> 原文 → */
</defs></svg>`;

export const SPRITE_IDS = ['i-back','i-chev','i-compass','i-search','i-book','i-scale','i-layers',
  'i-plus','i-lock','i-check','i-alert','i-share','i-star','i-clock','i-users','i-pen','i-mountain',
  'i-temple','i-bowl','i-wave','i-home','i-path','i-me'];

export function installSprite() {
  if (!document.getElementById('ke-icon-sprite')) {
    const wrap = document.createElement('div');
    wrap.id = 'ke-icon-sprite';
    wrap.innerHTML = SPRITE_HTML;
    document.body.prepend(wrap);
  }
}
```

（`SPRITE_IDS` 同步加一条测试：`expect(SPRITE_HTML).to.contain(id)` 逐个断言，防止漏拷。）

`components/KeIcon.vue`：

```vue
<script setup lang="ts">
defineProps<{ name: string }>();
</script>

<template>
  <svg class="ke-icon" aria-hidden="true"><use :href="`#i-${name}`" /></svg>
</template>

<style>
.ke-icon { width: 20px; height: 20px; stroke: currentColor; fill: none; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; flex-shrink: 0; }
</style>
```

`components/ClaimBadge.vue`：

```vue
<script setup lang="ts">
const props = defineProps<{ type: 'fact' | 'synth' | 'gen' }>();
const CONF = {
  fact: { symbol: '●', label: '事实', aria: '事实陈述，有出处' },
  synth: { symbol: '◐', label: '归纳', aria: '归纳推断，基于出处综合' },
  gen: { symbol: '○', label: '生成', aria: '生成内容，仅供参考' }
} as const;
const conf = CONF[props.type];
</script>

<template>
  <span class="claim" :class="`c-${type}`" :aria-label="conf.aria">
    <span aria-hidden="true">{{ conf.symbol }} {{ conf.label }}</span>
  </span>
</template>

<style scoped>
.claim { display: inline-flex; font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: var(--ke-radius-xs); border: 1px solid; }
.c-fact { background: var(--ke-fact-soft); color: var(--ke-fact); border-color: rgba(31, 122, 77, 0.28); }
.c-synth { background: var(--ke-synth-soft); color: var(--ke-synth); border-color: rgba(14, 116, 144, 0.28); }
.c-gen { background: var(--ke-gen-soft); color: var(--ke-gen); border-color: rgba(168, 85, 16, 0.28); }
</style>
```

`components/CitationTag.vue`：

```vue
<script setup lang="ts">
defineProps<{ index: number }>();
defineEmits<{ click: [] }>();
</script>

<template>
  <sup class="cite" role="button" :aria-label="`查看出处 ${index}`" tabindex="0" @click="$emit('click')">[{{ index }}]</sup>
</template>

<style scoped>
.cite { color: var(--ke-primary); font-weight: 700; font-size: 11px; cursor: pointer; }
.cite:hover { text-decoration: underline; }
</style>
```

`components/SourceList.vue`：

```vue
<script setup lang="ts">
defineProps<{
  sources: { index: number; title: string; locator: string; license?: string }[];
  gap?: string;
}>();
</script>

<template>
  <div class="src">
    <b>出处清单</b>
    <p v-for="s in sources" :key="s.index" class="row">
      [{{ s.index }}] {{ s.title }} · {{ s.locator }}<template v-if="s.license">（{{ s.license }}）</template>
    </p>
    <p v-if="gap" class="gap">⚠ 证据缺口：{{ gap }}</p>
  </div>
</template>

<style scoped>
.src { background: var(--ke-surface-2); border: 1px solid var(--ke-line); border-radius: var(--ke-radius-s); padding: 10px 13px; font-size: 12px; color: var(--ke-sub); line-height: 1.85; }
.src b { color: var(--ke-ink); }
.row { margin: 0; }
.gap { margin: 4px 0 0; color: var(--ke-warn); }
</style>
```

`components/PhaseTag.vue`：

```vue
<script setup lang="ts">
const props = defineProps<{ phase: 'P2' | 'P3'; label?: string }>();
const text = () => props.label ?? (props.phase === 'P2' ? '二期' : '三期');
</script>

<template>
  <span class="phasetag">{{ text() }}</span>
</template>

<style scoped>
.phasetag { display: inline-flex; font-size: 11px; padding: 1px 8px; border-radius: var(--ke-radius-full); background: var(--ke-surface-2); color: var(--ke-sub); border: 1px dashed var(--ke-line-strong); }
</style>
```

`src/index.ts`：

```ts
export { default as KeIcon } from './components/KeIcon.vue';
export { default as ClaimBadge } from './components/ClaimBadge.vue';
export { default as CitationTag } from './components/CitationTag.vue';
export { default as SourceList } from './components/SourceList.vue';
export { default as PhaseTag } from './components/PhaseTag.vue';
export { installSprite, SPRITE_IDS } from './icons/sprite';
export * from './icons/sprite';
```

- [ ] **Step 3: 双端接入并清理回退值**

`apps/explorer/src/main.ts` 改为：

```ts
import { createApp } from 'vue';
import '@ke/shared/src/tokens.css';
import '@ke/shared/src/styles/theme-vant.css';
import { installSprite } from '@ke/shared';
import App from './App.vue';

installSprite();
createApp(App).mount('#app');
```

workbench 同理（引 `theme-element.css`）。两 app 的 `package.json` dependencies 加 `"@ke/shared": "workspace:*"`。删除 Task 7 中所有 `var(--x, #fallback)` 的回退值与 stylelint 豁免注释（tokens 已全局注入）。

> explorer 引 `theme-vant.css` 需要 Vant 已安装（Task 7 已加依赖）；W2 才真正挂 Vant 组件，`theme-vant.css` 仅设置 CSS 变量，可安全预引。

- [ ] **Step 4: 测试与 lint 全绿**

```bash
pnpm --dir frontend install
pnpm --dir frontend test   # shared 5 个测试文件 + 两 app 冒烟全过
pnpm --dir frontend lint   # 裸色值检查通过（回退值已移除）
pnpm --dir frontend build
```

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat: @ke/shared——token/主题映射/svg 图标集与四个语义组件"
```

---

### Task 9: OpenAPI 契约暴露（springdoc + 前端生成脚本）

**Files:**
- Modify: `ke-boot/src/main/resources/application.yml`（springdoc 配置）
- Create: `ke-boot/src/main/java/com/ke/openapi/OpenApiConfig.java`
- Modify: `frontend/package.json`（加 `gen:api` 脚本与 devDependency `@openapitools/openapi-generator-cli`）

**Interfaces:**
- Produces: dev 环境 Swagger UI `http://localhost:8080/swagger-ui.html`；OpenAPI JSON `http://localhost:8080/v3/api-docs`；命令 `pnpm --dir frontend gen:api` → 生成 `frontend/packages/shared/src/api/`（W2 起前端消费生成类型）

- [ ] **Step 1: 配置 springdoc**

`application.yml` 追加：

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    enabled: true
```

`OpenApiConfig.java`：

```java
package com.ke.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI keOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Knowledge Explorer API")
            .description("知识探索卡片系统——错误 envelope: {code, message, traceId, data}")
            .version("0.1.0"));
    }
}
```

- [ ] **Step 2: 前端生成脚本**

`frontend/package.json` scripts 追加，devDependencies 追加 `"@openapitools/openapi-generator-cli": "^2.15.0"`：

```json
"gen:api": "openapi-generator-cli generate -i http://localhost:8080/v3/api-docs -g typescript-fetch -o packages/shared/src/api --additional-properties=supportsES6=true,withoutRuntimeChecks=true"
```

- [ ] **Step 3: 验证**

```bash
./mvnw -B -ntp -pl ke-boot spring-boot:run &
sleep 20
curl -s localhost:8080/v3/api-docs | head -c 300   # 应输出 {"openapi":"3.1.x"...,"info":{"title":"Knowledge Explorer API"}}
pnpm --dir frontend gen:api
ls frontend/packages/shared/src/api               # 应有 api.ts / models/
kill %1
```

Expected: 上述全部成立。生成产物此阶段不入库（`.gitignore` 追加 `frontend/packages/shared/src/api/`，W2 契约冻结时再定入库策略）。

- [ ] **Step 4: Commit**

```bash
git add ke-boot frontend/package.json frontend/pnpm-lock.yaml .gitignore
git commit -m "feat: springdoc 契约暴露与前端类型生成脚本"
```

---

### Task 10: CI 工作流与工程 README

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `README.md`（追加「工程结构」一节）
- Modify: `.gitignore`（确保含 Java/Node 条目）

**Interfaces:**
- Produces: GitHub Actions 双 job；CI 绿 = W1 周五演示门槛（docs/05 W1）

- [ ] **Step 1: 写 CI 工作流**

`.github/workflows/ci.yml`：

```yaml
name: ci
on:
  push: { branches: [main] }
  pull_request:

jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21", cache: maven }
      - run: ./mvnw -B -ntp verify

  frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pnpm/action-setup@v4
        with: { version: 9 }
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: pnpm, cache-dependency-path: frontend/pnpm-lock.yaml }
      - run: pnpm --dir frontend install --frozen-lockfile
      - run: pnpm --dir frontend lint
      - run: pnpm --dir frontend test
      - run: pnpm --dir frontend build
```

（Testcontainers 依赖 Docker——GitHub ubuntu runner 自带，无需额外配置。）

- [ ] **Step 2: README 工程结构一节 + .gitignore 核对**

README 追加：

```markdown
## 工程结构（W1 起）

- 后端：Maven 多模块（ke-domain / ke-infra / ke-service / ke-boot），本地依赖 Docker Compose（`docker-compose.dev.yml`：PG15 + Redis）
- 前端：`frontend/` pnpm workspace（packages/shared + apps/explorer + apps/workbench）
- 环境：JDK 21、Maven ≥3.6.3（用 `./mvnw`）、Node ≥20、pnpm ≥9、Docker
- 常用命令：`./mvnw -B -ntp verify` · `pnpm --dir frontend test` · `pnpm --dir frontend lint` · `pnpm --dir frontend gen:api`（需后端在 8080 运行）
```

`.gitignore` 确保含：`target/`、`node_modules/`、`dist/`、`.env`、`*.log`、`frontend/packages/shared/src/api/`。

- [ ] **Step 3: 本地全量验证（= W1 演示彩排）**

```bash
./mvnw -B -ntp verify
pnpm --dir frontend install --frozen-lockfile && pnpm --dir frontend lint && pnpm --dir frontend test && pnpm --dir frontend build
```

Expected: 全绿。推送后确认 GitHub Actions 两 job 绿。

- [ ] **Step 4: Commit**

```bash
git add .github README.md .gitignore
git commit -m "ci: 后端/前端双工作流与工程 README"
```

---

## 验收（对齐 docs/05 W1 演示）

1. `./mvnw -B -ntp verify` 与 `pnpm --dir frontend lint && test && build` 全绿（CI 同样全绿）
2. 注册→登录→`/api/me` 全链路可用（Swagger UI 或 curl 演示）
3. `agent_run` 提交后经虚拟线程执行至 DONE，状态机非法迁移被拒
4. 双端壳启动即呈现 v1.1 纸墨主题（token 生效、图标集可用、无裸色值）
5. 13 张核心表在 dev 库就绪，`flyway_schema_history` 仅一条 V1

## Self-Review 记录

- **规格覆盖**：docs/05 W1 四条泳道任务全部落位——A→Task 1/2/4；B→Task 2/5/6；C→Task 7/8/9；CI→Task 10。W1 演示四项与「验收」一节一一对应。
- **占位符扫描**：唯一「粘贴源」是 `sprite.ts` 与三个 CSS 文件，取值来源均为仓库内现有文件（styleguide.html / docs/04 §11），已给出精确位置与 `SPRITE_IDS` 断言防漏拷，不属于 TBD。
- **类型一致性**：`ApiResponse.ok/error`、`JwtService.issueAccess/parse`、`LlmGateway.complete(ChatCommand)`、`AgentRunService.submit/get`、`installSprite()`、组件 props 在定义任务与消费任务间已逐一核对一致。
