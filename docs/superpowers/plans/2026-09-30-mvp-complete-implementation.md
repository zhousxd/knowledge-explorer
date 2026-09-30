# 知识探索 MVP 全量实施计划（01 需求 × 02 架构 → 全部 P0 功能）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 01-需求拆解 §1 的五条验收（A1–A5）：4 模板卡片体系、会话路径树与分支、讲解/整理两类智能体服务（可信三档标识+出处）、一句话新增入口、分享与接续，以及工作台/审核/埋点/配额等全部 P0 支撑功能。

**Architecture:** 按 02-技术架构执行：Maven 四模块单体（ke-domain 纯领域 / ke-infra 持久化 / ke-service 业务 / ke-boot 启动，web+worker 同 jar）+ PG15（业务/全文/pgvector）+ Redis（配额/缓存）+ MinIO；LLM 经 `LlmGateway`（Spring AI OpenAI 兼容，路由/生成双档），固定流水线「路由→受限检索→生成→校验」而非自主 Agent；前端 pnpm monorepo 双端（Vant 探索端 / Element Plus 工作台）+ `@ke/shared`（Token、语义组件、图标、API 类型）。

**Tech Stack:** Java 21（虚拟线程）· Spring Boot 3.5 · MyBatis-Plus · Flyway · PostgreSQL 15 + pg_trgm · Redis · jjwt · Spring AI 1.x · Resilience4j · springdoc + openapi-generator · Vue3 · Vant · Element Plus · Vitest · Testcontainers

## Global Constraints（01 §5 / 02 全文提炼，所有任务隐含遵守）

- **性能**：卡片首屏 ≤1.5s；讲解服务完成 ≤20s（轮询 2s）；分享页首开 ≤2s、首屏 chunk ≤50KB gz
- **成本**：单次讲解 ≤¥0.03（路由档小模型 + 生成档大模型）；单用户日服务 30 次上限
- **可信**：生成内容逐段 `claim_type` + `citations[]`；引用必须 ⊆ 本次检索返回集合，越界剥离并降级归纳；证据缺口字段强制
- **安全**：JWT Access 2h / Refresh 7d；分享 token 用 SecureRandom nanoid 不可枚举；快照生成时做资源级权限过滤（ke-domain 纯函数，穷举单测）；分享路由 permitAll，其余 authenticated
- **合规**：生成内容过敏感词 DFA + 显著标识；审核动作/状态流转/权限变更 AOP 审计留痕
- **数据**：`card_version`/`share_snapshot` 不可变（只 INSERT + DB 触发器兜底）；路径分支不建表（`parent_node_id` 树 + 递归 CTE）；`content_json`/`config_json` 用 jsonb + record 绑定 + jakarta.validation 写前校验
- **工程**：依赖版本只锁父 POM / 根 package.json；接口先冻结（springdoc → openapi-generator 生成 TS）；前端禁裸色值、控件图标禁 emoji；每个任务以 Conventional Commit 收尾
- **入口铁律**：入口类型白名单仅 3 类（link_card / agent_service / compare）；自然语言不得赋予新工具或数据权限（`EntryConfigValidator` 代码层校验，非提示词层）

**前置**：Phase 0 = `docs/superpowers/plans/2026-09-30-w1-project-skeleton.md`（工程骨架/13 表/认证/LLM 网关/前端 monorepo/CI，10 任务）。本计划 Phase 1 起直接在其产物之上增量开发。

---

## Phase 0 · 工程底座（已另文细化，此处为入口清单）

按 `2026-09-30-w1-project-skeleton.md` 执行完 10 个任务（Maven 多模块、Flyway V1 13 表、领域枚举、认证+JWT、LlmGateway 双档+Mock、agent_run 虚拟线程骨架、前端 monorepo、@ke/shared、openapi 流水线、CI）。**本计划所有任务的包名/表名/组件名与其保持一致。**

---

## Phase 1 · 内容域后端（D1 卡片体系 + D6 内容运营后端）

### Task 1: card_version 不可变 + 卡片摘要列（V2 迁移）

**Files:**
- Create: `ke-infra/src/main/resources/db/migration/V2__card_summary_and_immutability.sql`
- Modify: `ke-infra/src/main/java/com/ke/infra/entity/CardEntity.java`（W1 后续任务建立的实体；若无则新建）、`CardVersionEntity.java`
- Test: `ke-infra/src/test/java/com/ke/infra/CardVersionImmutabilityIT.java`

**Interfaces:**
- Produces: `card.summary_text` 列（发布时由版本回填，供搜索）；`card_version` UPDATE/DELETE 被 DB 触发器拒绝

- [ ] **Step 1: 写失败测试**

```java
@Testcontainers
@JdbcTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class CardVersionImmutabilityIT {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg15");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }
    @Autowired JdbcTemplate jdbc;

    @Test
    void updateAndDeleteAreRejectedByTrigger() {
        jdbc.update("insert into ke_user(phone,nickname) values('13900000001','编辑')");
        jdbc.update("insert into card(theme,template_type,title) values('书院地标','TEXT','岳麓书院')");
        jdbc.update("insert into card_version(card_id,version_no,content_json) values(1,1,'{\"summary\":\"s\"}'::jsonb)");
        assertThatThrownBy(() -> jdbc.update("update card_version set content_json='{\"x\":1}'::jsonb where id=1"))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from card_version where id=1"))
            .isInstanceOf(DataAccessException.class);
    }
}
```

- [ ] **Step 2: 迁移脚本（含 pg_trgm 检索准备）**

```sql
-- 发布时回填的搜索摘要列
ALTER TABLE card ADD COLUMN summary_text VARCHAR(200);

-- card_version 不可变（触发器兜底，代码层本就只 INSERT）
CREATE OR REPLACE FUNCTION forbid_card_version_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'card_version is immutable (id=%)', OLD.id;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_card_version_immutable
  BEFORE UPDATE OR DELETE ON card_version
  FOR EACH ROW EXECUTE FUNCTION forbid_card_version_mutation();

-- 中文检索用 pg_trgm（01 FR-C02：标题+摘要；zhparser 二期再评估）
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_card_title_trgm ON card USING gin (title gin_trgm_ops);
CREATE INDEX idx_card_summary_trgm ON card USING gin (summary_text gin_trgm_ops);
```

- [ ] **Step 3: 测试通过后提交** — `git commit -m "feat: card_version 不可变触发器与检索列"`

### Task 2: 四模板 content_json 模型与校验（FR-C03/C04/C05/C06）

**Files:**
- Create: `ke-domain/src/main/java/com/ke/domain/card/content/TextCardContent.java`、`CompareCardContent.java`、`TimelineCardContent.java`、`TaskCardContent.java`、`CardContentValidator.java`
- Test: `ke-domain/src/test/java/com/ke/domain/card/content/CardContentValidatorTest.java`

**Interfaces:**
- Produces: `CardContentValidator.parseAndValidate(String templateType, String json)` → 四种 record 之一（Jackson + jakarta.validation）；后续「工作台保存」「卡片详情 API」都经它把关。结构对齐 02 §4.3：

- [ ] **Step 1: 写失败测试（每模板 1 正例 + 结构错误例）**

```java
class CardContentValidatorTest {
    ObjectMapper mapper = new ObjectMapper();

    @Test
    void textCardValid() { /* summary≤120、sections 非空、related.relation 非空 → 解析成功 */ }
    @Test
    void textCardSummaryTooLongRejected() { /* 121 字 summary → ConstraintViolation */ }
    @Test
    void compareCellsMustMatchDimensionsByObjects() { /* 2 对象×4 维度但 cells 只有 3 行 → 拒绝 */ }
    @Test
    void timelineEventsRequired() { /* events 空 → 拒绝 */ }
    @Test
    void taskStepsMinutesPositive() { /* minutes=0 → 拒绝 */ }
}
```

- [ ] **Step 2: 实现 records + 校验器**

```java
public record TextCardContent(
    @NotBlank @Size(max = 120) String summary,
    @NotEmpty @Valid List<Section> sections,
    List<Related> related) {
  public record Section(@NotBlank String h, @NotBlank String body, List<Integer> citations) {}
  public record Related(@NotNull Long cardId, @NotBlank String relation, @NotBlank String why, Long source) {}
}

public record CompareCardContent(
    @NotEmpty List<String> objects,          // 对象（列）
    @NotEmpty List<String> dimensions,       // 维度（行）
    @NotEmpty List<List<String>> cells,      // rows=dimensions.size, cols=objects.size
    List<Integer> citations) {}

public record TimelineCardContent(@NotEmpty @Valid List<Event> events) {
  public record Event(@NotBlank String year, @NotBlank String title, String body, Long cardId, List<Integer> citations) {}
}

public record TaskCardContent(
    @NotBlank String goal,
    @NotEmpty @Valid List<Step> steps,
    @NotEmpty List<String> recordSchema) {   // 记录形式：文本/照片
  public record Step(String place, @NotBlank String observe, @Positive int minutes) {}
}
```

`CardContentValidator`：`switch (templateType)` → 目标 record → `mapper.readValue` → `Validator.validate` → 额外规则：对比卡 `cells.size()==dimensions.size() && cells行内长度==objects.size()`；图文卡正文引用编号 ≥0。

- [ ] **Step 3: 测试通过后提交** — `git commit -m "feat: 四模板内容模型与写前校验"`

### Task 3: 卡片服务与 API——CRUD/版本/发布（FR-C07/C08）

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/card/CardService.java`、`CardAdminController.java`、`CardPublicController.java`
- Create: `ke-infra/src/main/java/com/ke/infra/mapper/CardMapper.java`、`CardVersionMapper.java`
- Test: `ke-boot/src/test/java/com/ke/card/CardLifecycleIT.java`

**Interfaces:**
- Produces（02 §7 端点表）：
  - 工作台（需 CREATOR，写操作 `@PreAuthorize("hasAnyRole('CREATOR','EDITOR','OPERATOR')")`）：`POST /wb/cards`（建卡+首版）、`PUT /wb/cards/{id}/content`（**存新版本** version_no+1，不改旧版）、`POST /wb/cards/{id}/submit`（DRAFT→PENDING）、`POST /wb/cards/{id}/publish`（PENDING→PUBLISHED，仅 EDITOR/OPERATOR，回填 summary_text 与 current_version_id）、`POST /wb/cards/{id}/disable`（PUBLISHED→DISABLED）
  - 探索端（permitAll 之外默认认证）：`GET /cards?theme=&q=&cursor=`、`GET /cards/{id}`（返回 current_version 内容）、`GET /cards/{id}/entries`（Task 6）
- Consumes: Task 1/2 产物；`CardStatus.canComeFrom`（W1 Task 3）

- [ ] **Step 1: 写失败集成测试**

```java
@SpringBootTest(webEnvironment = RANDOM_PORT) @ActiveProfiles("test") @Testcontainers
class CardLifecycleIT {
    // 建号角色：CREATOR 建 TEXT 卡 → submit → EDITOR publish → 公开端点可见、summary_text 已回填
    @Test void creatorSubmitEditorPublishFlow() { /* 断言 status 流转 + GET /cards 返回该卡 + 版本号=1 */ }
    @Test void saveContentCreatesNewImmutableVersion() { /* PUT content 两次 → card_version 2 行，旧版内容不变 */ }
    @Test void explorerCannotPublish() { /* EXPLORER 调 publish → 403 envelope */ }
}
```

- [ ] **Step 2: 实现服务**（关键点：`saveContent` 只 INSERT card_version；`publish` 校验 `CardStatus.canComeFrom` + `CardContentValidator.parseAndValidate` + 回填 `summary_text=content.summary()` 截 200 字；keyset 游标 `(sort,id)`）
- [ ] **Step 3: 测试通过后提交** — `git commit -m "feat: 卡片生命周期 api 与不可变版本"`

### Task 4: 审核工作流 + 审计切面（FR-O03 / 02 §9）

**Files:**
- Create: `ke-infra/src/main/resources/db/migration/V3__review_and_audit.sql`
- Create: `ke-service/src/main/java/com/ke/service/review/ReviewService.java`、`ReviewController.java`、`AuditAspect.java`、`@Audited`（注解）
- Test: `ke-boot/src/test/java/com/ke/review/ReviewIT.java`

**Interfaces:**
- Produces: `GET /wb/reviews?status=PENDING&objectType=`、`POST /wb/reviews/{id}/approve`、`POST /wb/reviews/{id}/reject`（body: notes）——approve 内部调对应对象（卡片/入口）的 publish 动作；`audit_log(id, actor_id, action, object_type, object_id, detail_jsonb, created_at)` 由 AOP 自动写入，`@Audited(action="…")` 标注审核/状态流转/权限变更方法

- [ ] **Step 1: 失败测试**：CREATOR submit 卡片 → EDITOR 在队列看到 → approve → 卡 PUBLISHED 且 audit_log 有 `CARD_PUBLISH` 记录；reject 带 notes → 卡回 DRAFT
- [ ] **Step 2: 实现**（V3: audit_log 表；ReviewService 桥接 CardService.publish；AuditAspect `@Around` 落 actor=SecurityContext）
- [ ] **Step 3: 提交** — `git commit -m "feat: 审核队列与审计留痕"`

### Task 5: 知识资产导入与引用（FR-O01/O02）

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/asset/AssetImportService.java`、`AssetController.java`
- Create: `ke-infra/src/main/java/com/ke/infra/mapper/KnowledgeAssetMapper.java`、`CitationMapper.java`
- Test: `ke-boot/src/test/java/com/ke/asset/AssetImportIT.java`、`seed/assets-book.csv`（样例）

**Interfaces:**
- Produces: `POST /wb/assets/import`（CSV 批量：kind,title,source_meta,locator_json,license,license_expire,content_extract）、`GET /wb/assets?kind=&q=`、`POST /wb/assets/{id}/citations`（object_type+object_id+quote → citation 行）；`citation` 结构 = 资产+定位器+原文摘录（02 §4.2）
- locator jsonb 约定：`{"chapter":"第一章","pages":"12-14"}` / `{"t":"00:12:30-00:15:00"}`

- [ ] **Step 1: 失败测试**：CSV 3 行导入成功（locator 解析为 jsonb）；授权到期日过期的资产在 `GET` 标注 `expired=true`
- [ ] **Step 2: 实现 + 样例 CSV**（`seed/assets-book.csv` 给 5 行真实格式数据，后续 Task 43 种子导入复用）
- [ ] **Step 3: 提交** — `git commit -m "feat: 知识单元批量导入与统一引用"`

### Task 6: 入口模型与只读 API + 关系校验（FR-E02/C09/N07 白名单）

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/entry/EntryService.java`、`EntryPublicController.java`
- Create: `ke-infra/src/main/java/com/ke/infra/mapper/EntryMapper.java`
- Test: `ke-boot/src/test/java/com/ke/entry/EntryApiIT.java`

**Interfaces:**
- Produces: `GET /cards/{id}/entries` → `{defaultEntries:[…5 个], folded:[…其余]}`（按 `sort`）；每项 `{id,name,type,relationLabel,targetCardId,serviceType,scope,status}`；四类关系标签常量 `深入了解/相关联/相比较/去实践`
- 写接口（CRUD/试运行/自然语言草稿）在 Task 30–31 增量补齐

- [ ] **Step 1: 失败测试**：7 条入口 → default 5 + folded 2；`type` 落在 `EntryType` 3 类之外插入被拒（Service 层校验）
- [ ] **Step 2: 实现并提交** — `git commit -m "feat: 入口只读 api 与类型白名单"`

---

## Phase 2 · 工作台前端（Element Plus）

### Task 7: 工作台框架与 API 类型接入

**Files:**
- Create: `frontend/apps/workbench/src/router/index.ts`、`src/layout/WbLayout.vue`、`src/api/http.ts`
- Modify: `frontend/apps/workbench/src/App.vue`、`main.ts`（挂路由 + Pinia）
- Test: `frontend/apps/workbench/src/__tests__/WbLayout.spec.ts`

**Interfaces:**
- Consumes: `@ke/shared`（Token/主题/KeIcon）、`gen:api` 产物 `@ke/shared/src/api`
- Produces: 路由 `/cards /entries /reviews /assets /metrics`；`http.ts`：fetch 封装（自动附 Bearer、401 跳登录、envelope 解包抛业务错误）

- [ ] **Step 1: 失败测试**：布局渲染 5 个菜单项与当前用户角色徽标
- [ ] **Step 2: 实现（`http.ts` 全量代码 ~60 行：baseURL 注入、token 读写 localStorage、ApiResponse 解包、traceId 透传到错误对象）**
- [ ] **Step 3: 提交** — `git commit -m "feat: 工作台框架与 http 封装"`

### Task 8: 卡片管理页 + 版本历史抽屉（FR-C07/C08 界面）

**Files:**
- Create: `frontend/apps/workbench/src/views/CardsView.vue`、`src/components/CardDrawer.vue`、`VersionHistory.vue`
- Test: `frontend/apps/workbench/src/__tests__/CardsView.spec.ts`

**Interfaces:**
- Consumes: Task 3 API
- Produces: 表格（模板/状态标签/版本列）+ 状态筛选 tabs + 详情抽屉（内容 + 版本历史时间线：版本号/作者/时间，旧版只读）+ 新建/送审/停用操作

- [ ] **Step 1: 失败测试**：mock API 渲染 3 行卡片；点击行打开抽屉并显示版本历史 2 条
- [ ] **Step 2: 实现（状态标签映射 04 §2.4：草稿灰/待审核警示/已发布绿/停用红）**
- [ ] **Step 3: 提交** — `git commit -m "feat: 工作台卡片管理与版本历史"`

### Task 9: 四模板编辑器（动态表单）

**Files:**
- Create: `frontend/apps/workbench/src/views/CardEditView.vue`、`src/components/editors/TextEditor.vue`、`CompareEditor.vue`、`TimelineEditor.vue`、`TaskEditor.vue`
- Test: `frontend/apps/workbench/src/__tests__/editors/CompareEditor.spec.ts`（其余编辑器同规格各 1 例）

**Interfaces:**
- Produces: `POST /wb/cards` / `PUT content` 的表单体与 Task 2 record 一一对应；对比编辑器维护 `objects[]×dimensions[]→cells[][]` 网格（增删行列联动）；保存前本地校验与后端一致（summary ≤120 等）

- [ ] **Step 1: 失败测试**：CompareEditor 初始 2×2，加一维度 → 网格 3×2，cells 数据保留
- [ ] **Step 2: 实现四编辑器 + 提交校验提示（错误定位到字段）**
- [ ] **Step 3: 提交** — `git commit -m "feat: 四模板卡片编辑器"`

### Task 10: 审核中心（FR-O03 界面，≤3 步完成审核）

**Files:**
- Create: `frontend/apps/workbench/src/views/ReviewsView.vue`
- Test: `frontend/apps/workbench/src/__tests__/ReviewsView.spec.ts`

**Interfaces:**
- Consumes: Task 4 API
- Produces: 待审卡片/入口两队列；每项摘要 + 机器预检标签（W1 骨架上补：出处有效=引用资产均存在且授权未过期、无越权、敏感词通过）+「通过并发布 / 驳回（意见必填）」

- [ ] **Step 1: 失败测试**：渲染待审项与三个预检标签；驳回未填意见时按钮禁用
- [ ] **Step 2: 实现（预检标签数据来自 `GET /wb/reviews` 返回的 `precheck` 字段，后端在 Task 4 返回体补上）**
- [ ] **Step 3: 提交** — `git commit -m "feat: 审核中心三步操作"`

### Task 11: 知识资源页

**Files:**
- Create: `frontend/apps/workbench/src/views/AssetsView.vue`（导入 CSV 上传、列表、授权状态/到期列、被引用次数）
- Test: `frontend/apps/workbench/src/__tests__/AssetsView.spec.ts`（列表渲染 + 到期警示色）

**步骤：失败测试 → 实现 → `git commit -m "feat: 知识资源管理页"`**

---

## Phase 3 · 探索端基础（Vant）

### Task 12: 探索端框架 + 登录页 + 短信验证码（FR-U01）

**Files:**
- Create: `frontend/apps/explorer/src/router/index.ts`、`src/views/LoginView.vue`、`src/api/http.ts`
- Create: `ke-service/src/main/java/com/ke/service/auth/SmsController.java`、`SmsSender.java`、`DevSmsSender.java`、`HttpSmsSender.java`（`ke.sms.gateway-url` 可配）
- Test: `ke-boot/src/test/java/com/ke/auth/SmsLoginIT.java`、`frontend/apps/explorer/src/__tests__/LoginView.spec.ts`

**Interfaces:**
- Produces: `POST /api/auth/sms/send {phone}`（60s 冷却，Redis 计数）；`POST /api/auth/sms/login {phone,code}`（dev/test 固定码 `246810`；prod 走 `HttpSmsSender`）；前端登录页双 Tab（验证码/密码）

- [ ] **Step 1: 失败测试**：dev 发码（日志输出）→ 用 `246810` 登录成功拿 token；错误码 401
- [ ] **Step 2: 实现（验证码存 Redis `sms:{phone}` 5 分钟 TTL）+ 前端登录页与 http 封装**
- [ ] **Step 3: 提交** — `git commit -m "feat: 短信验证码登录与探索端框架"`

### Task 13: 首页（专题/搜索/继续探索卡）

**Files:**
- Create: `frontend/apps/explorer/src/views/HomeView.vue`、`src/components/ResumeCard.vue`、`ThemeGrid.vue`
- Test: `frontend/apps/explorer/src/__tests__/HomeView.spec.ts`

**Interfaces:**
- Consumes: `GET /cards?theme=`、`GET /sessions/latest`（Task 17 提供；未登录隐藏 ResumeCard）
- Produces: 首页 = 品牌标题（宋体）+ 搜索框 + 继续探索卡（深靛实底、宋体标题、进度摘要）+ 三专题格（SVG 图标 i-temple/i-bowl/i-wave）+ 推荐入口列表

**步骤：失败测试（三专题渲染/搜索触发 emit）→ 实现 → `git commit -m "feat: 探索端首页"`**

### Task 14: 卡片页 + 四模板渲染器（02 §8 CardRenderer 分发）

**Files:**
- Create: `frontend/apps/explorer/src/views/CardView.vue`、`src/components/CardRenderer/index.ts`、`TextCard.vue`、`CompareCard.vue`、`TimelineCard.vue`、`TaskCard.vue`、`ServiceBar.vue`
- Test: `frontend/apps/explorer/src/__tests__/CardRenderer.spec.ts`、`TextCard.spec.ts`

**Interfaces:**
- Produces: `CardRenderer` 按 `templateType` 分发；`TextCard` 结构 = chip 行 → 宋体标题 → 摘要（虚线分隔）→ 插图位 → 正文段（CitationTag + ClaimBadge 内联）→ SourceList → 入口列表（Task 15 接交互）；事件 `@enter(entry)`；对比卡横向滚动 + 对象名吸顶；时间线事件 `@open(cardId)`
- 模板→组件映射常量放 `CardRenderer/index.ts`：新增卡型只加渲染器

- [ ] **Step 1: 失败测试**：分发器对 4 类型渲染对应组件；TextCard 渲染 `summary/两段正文/出处行`，角标与徽标用 shared 组件
- [ ] **Step 2: 实现四渲染器 + ServiceBar 三键（讲清楚/帮我比较/整理发现，emit 对应事件）**
- [ ] **Step 3: 提交** — `git commit -m "feat: 卡片页与四模板渲染器"`

### Task 15: 出处交互与收藏（FR-S05 前端 / FR-C10）

**Files:**
- Create: `frontend/apps/explorer/src/components/CitationPopover.vue`（角标点击 → 高亮 SourceList 对应行）
- Modify: `TextCard.vue`、`CardView.vue`
- Create: `ke-service/src/main/java/com/ke/service/card/FavoriteController.java`（`POST/DELETE /cards/{id}/favorite`、`GET /me/favorites`）
- Test: `frontend/apps/explorer/src/__tests__/CitationPopover.spec.ts`、`ke-boot/.../FavoriteIT.java`

**步骤：失败测试（点击 [2] → 出处清单第 2 行高亮 class）→ 实现 → `git commit -m "feat: 出处联动与收藏"`**

---

## Phase 4 · 会话与路径树（D2 探索）

### Task 16: 会话与节点 API + 递归子树（FR-E01/E03/E05）

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/explore/SessionService.java`、`PathNodeService.java`、`SessionController.java`
- Create: `ke-infra/src/main/java/com/ke/infra/mapper/PathNodeMapper.java`、`SessionMapper.java`、实体两枚
- Test: `ke-boot/src/test/java/com/ke/explore/PathTreeIT.java`

**Interfaces:**
- Produces（02 §7）：`POST /sessions {theme,goal}`、`GET /sessions`（我的路径列表，含最近节点标题）、`GET /sessions/{id}`（断点续探：会话+树）、`POST /sessions/{id}/nodes {cardVersionId,entryId,parentNodeId,questionText}` → 节点落库（分支=指定历史 parentNodeId 即成，不建新表）
- `PathNodeMapper.selectSubtree(nodeId)` 递归 CTE（成果整理/分享都要用）

- [ ] **Step 1: 失败测试**：建会话 → 顺序 3 节点成链；对节点 1 再挂 1 节点 → `selectSubtree(根)` 返回 5 节点、节点 1 有 2 子（A2 数据面）
- [ ] **Step 2: 实现 mapper CTE（`WITH RECURSIVE subtree AS (...) SELECT * FROM subtree ORDER BY visited_at`）与服务**
- [ ] **Step 3: 提交** — `git commit -m "feat: 会话与路径树（递归子树）"`

### Task 17: 路径页与断点续探前端（FR-E01/E04/E05 界面）

**Files:**
- Create: `frontend/apps/explorer/src/views/PathView.vue`、`src/components/PathTree.vue`、`src/store/path.ts`（Pinia：nodes/childrenMap/curNode、`addNode(parentId,node)`）
- Create: `frontend/apps/explorer/src/components/TopBar.vue`（返回+面包屑+指南针，二级页常驻）
- Test: `frontend/apps/explorer/src/__tests__/PathTree.spec.ts`

**Interfaces:**
- Produces: 路径页 = 树（缩进+连线+dot 状态：当前=主色描边/分支=「分支」微标/叶=灰）+ 节点点击两义（历史节点=「回到此节点」继续、产生新子节点即分支）+ 底部三键（整理发现→Task 29、暂存=离开、换个方向=回首页）+ 未决疑问列表（Task 24 数据）；首次进入引导条文案按 04 §7.2

- [ ] **Step 1: 失败测试**：store `addNode` 挂到历史节点 → 该节点 childrenMap+1，节点带分支标
- [ ] **Step 2: 实现树组件与页 → Step 3: 提交** — `git commit -m "feat: 路径树页面与断点续探"`

---

## Phase 5 · 智能体服务（D3 全链路，产品核心）

### Task 18: 受限检索与讲解服务流水线（FR-S02 / 02 §5.2）

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/agent/RetrievalService.java`（受限检索：卡片挂接的知识单元 + 授权过滤，返回 `Map<Long,String>` assetId→摘录）
- Create: `ke-service/src/main/java/com/ke/service/agent/ExplainService.java`、`dto/ExplainOutput.java`、`dto/ExplainResult.java`
- Test: `ke-boot/src/test/java/com/ke/agent/ExplainPipelineIT.java`

**Interfaces:**
- Produces: `ExplainService.explain(cardVersionId, question, level, sessionId, nodeId) → runId`（异步）；结构化输出 record：

```java
public record ExplainOutput(
    String summary,
    List<Section> sections,
    List<String> openQuestions,     // FR-E09 未决问题落库复用
    List<String> evidenceGaps) {    // FR-E12 强制字段
  public record Section(String body, ClaimType claimType, List<Long> citations) {}
}
```

三档提示词（`explain-prompts.properties`）：`SIMPLE=面向成人科普…` / `DEEP=引用原文并展开论证…` / `CHILD=用比喻和提问讲解…`（system 前缀统一：「只依据提供的资料回答；每段给出 claim_type(FACT/SYNTHESIS/GEN) 与所引资料 id；资料不足以判断时写进 evidence_gaps；输出 JSON」）

- [ ] **Step 1: 失败测试（Mock 网关返回合法 ExplainOutput JSON）**：run 终态 DONE；`agent_run.artifact_ids` 指向落库的讲解 artifact（content_json 含 summary/sections/open_questions/evidence_gaps）；citation 行写入
- [ ] **Step 2: 实现（Jackson 绑定失败自动重试 1 次；成功后写 artifact(type=EXPLAIN)）**
- [ ] **Step 3: 提交** — `git commit -m "feat: 讲解服务流水线与结构化输出"`

### Task 19: 引用校验器 + 降级（FR-S05 / R2，ke-domain 纯函数）

**Files:**
- Create: `ke-domain/src/main/java/com/ke/domain/trust/CitationSanitizer.java`
- Test: `ke-domain/src/test/java/com/ke/domain/trust/CitationSanitizerTest.java`（穷举）

**Interfaces:**
- Produces: `CitationSanitizer.sanitize(List<Section>, Set<Long> allowedAssetIds)`：越界引用剔除；剔除后 FACT 段若无有效引用 → 降级 SYNTHESIS；返回 `SanitizeReport{sections, strippedCitations, downgradedSections}`（报告进 agent_run.error 字段旁路日志）

- [ ] **Step 1: 穷举测试**：全合法 FACT 保持 / 1 个越界 id 被剥离 / FACT 全部越界 → 降级 SYNTHESIS / SYNTHESIS 带越界 → 剥离但不降级 / 空集合 → 全部 FACT 降级
- [ ] **Step 2: 实现（纯函数无 IO）→ Step 3: 提交** — `git commit -m "feat: 引用校验降级器（纯函数）"`

### Task 20: 后处理管道——敏感词/标识/计量 + 配额/超时（FR-S13/S10/S04 / R8）

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/agent/post/SensitiveWordFilter.java`（DFA）、`ClaimLabelAppender.java`（生成标识文案）、`RunMetrics.java`
- Create: `ke-service/src/main/java/com/ke/service/quota/QuotaService.java`（Redis INCR）、`QuotaController.java`（`GET /me/quota`）
- Modify: `AgentRunService`（接入 Resilience4j TimeLimiter 60s + QuotaService 前置 + 后处理链）
- Test: `ke-boot/src/test/java/com/ke/agent/PostPipelineIT.java`

**Interfaces:**
- Produces: 提交前 `QuotaService.tryConsume(uid)`（key `user:quota:{uid}:{yyyyMMdd}`，30 次/日，首次设 48h TTL，超限 429 envelope code=4291）；执行包 `TimeLimiter`（60s 超时 → status=TIMEOUT，允许重试、路径无损）；后处理顺序=敏感词→标识→CitationSanitizer（19）→计量（model/tokens/cost/latency 写 agent_run）；`GET /me/quota → {used,limit,resetAt}`

- [ ] **Step 1: 失败测试**：第 31 次 → 429；Mock 网关挂起 61s（用 `MockLlmGateway` 注入 delay）→ TIMEOUT；命中敏感词 → 段落替换 `**` 且 run 标记 `filtered=true`
- [ ] **Step 2: 实现（DFA 词表 `seed/sensitive-words.txt` 启动加载）→ Step 3: 提交** — `git commit -m "feat: 服务护栏——配额/超时/敏感词/计量"`

### Task 21: 执行态页与轮询（FR-S04 界面）

**Files:**
- Create: `frontend/apps/explorer/src/views/RunView.vue`、`src/api/runs.ts`（2s 轮询，组件卸载停止；离开页面结果落地后 toast）
- Test: `frontend/apps/explorer/src/__tests__/RunView.spec.ts`（fake timers：3 次轮询后 DONE 渲染结果入口）

**步骤：失败测试 → 实现（步骤文案：读取上下文→检索→生成校验；超时/失败态按 04 §8.4 模板与重试按钮）→ `git commit -m "feat: 执行态与轮询"`**

### Task 22: 讲解卡结果页 + 追问/档位/停顿（FR-E08/E09/E10/E11/E12 界面）

**Files:**
- Create: `frontend/apps/explorer/src/views/ExplainResultView.vue`、`src/components/AskBar.vue`、`ContextBar.vue`、`ExplainLevelSwitch.vue`
- Modify: `ke-service/.../ExplainService.java`（`POST /agent/runs` 支持 `parentRunId` 追问链与会话档位记忆 `exploration_session.explain_level`）、`SessionController`（`PUT /sessions/{id}/explain-level`）
- Test: `ke-boot/.../FollowUpIT.java`、`frontend/.../ExplainResultView.spec.ts`

**Interfaces:**
- Produces: 结果页 = chip「讲解卡 · 由智能体生成」+档位 chip + 宋体问题标题 + 分段正文（ClaimBadge/CitationTag/SourceList 全套 + 证据缺口警示行）+「还可以继续问」入口 + 受控生成声明脚注；AskBar 常驻底部（44 高、聚焦主色描边）；ContextBar 显示当前卡+会话目标+档位（E11：服务只读当前会话上下文——后端组装时仅取本 session 摘要）

- [ ] **Step 1: 失败测试**：后端——追问 run 的 input_json.parentRunId 指向前次；前端——三档切换调 PUT、AskBar emit 提交触发 run
- [ ] **Step 2: 实现 → Step 3: 提交** — `git commit -m "feat: 讲解结果页与追问链"`

### Task 23: 「帮我比较」对比输出 + 跨主题关系（FR-S06 一期形态 / C09）

**Files:**
- Modify: `ke-service/.../ExplainService.java`（`serviceType=compare`：system 追加「输出 CompareCardContent JSON」；产出 artifact(type=COMPARE_CARD)）
- Create: `ke-service/src/main/java/com/ke/service/entry/RelationGuard.java`
- Test: `ke-boot/src/test/java/com/ke/agent/CompareOutputIT.java`

**Interfaces:**
- Produces: compare 服务的 artifact.content_json 直接满足 `CompareCardContent` 校验（前端 CompareCard 免改渲染）；`RelationGuard.validateCrossTheme(entry)`：跨主题（卡 theme ≠ 目标卡 theme）入口必须 `relationLabel` + 关系说明（config_json.why）+ 出处，否则 400

- [ ] **Step 1: 失败测试**：compare run 输出可被 `CardContentValidator` 解析；跨主题入口缺 why → 拒绝
- [ ] **Step 2: 实现 → Step 3: 提交** — `git commit -m "feat: 比较服务输出与跨主题关系守卫"`

### Task 24: 成果整理服务 + 疑问清单（FR-S03/E07/E09）

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/agent/SummaryService.java`、`SummaryController.java`（`POST /artifacts/summarize {sessionId, nodeIds[]}`、`POST /artifacts`）
- Test: `ke-boot/src/test/java/com/ke/agent/SummarizeIT.java`

**Interfaces:**
- Produces: 输入勾选节点（跨分支，`selectSubtree` 取材料）→ 输出 artifact(type=REPORT)：`{keyFindings:[{body,claimType,citations}], openQuestions:[…复用各 run 的 openQuestions], branchView:[{rootNodeTitle, nodes:[…]}]}`；未决疑问列表 `GET /sessions/{id}/open-questions` 聚合讲解 run

- [ ] **Step 1: 失败测试**：两分支各 1 节点勾选 → REPORT 含 branchView 2 支 + openQuestions 去重合并
- [ ] **Step 2: 实现（生成档大模型 + 校验 citations ⊆ 材料集合，复用 Sanitizer）→ Step 3: 提交** — `git commit -m "feat: 成果整理服务"`

### Task 25: 成果整理页（FR-E07/E09 界面）

**Files:**
- Create: `frontend/apps/explorer/src/views/SummaryView.vue`（跨分支勾选树 + 报告预览 + 保存/去分享）
- Test: `frontend/.../SummaryView.spec.ts`（勾选两支节点 → 提交 body 含 2 个 nodeIds）

**步骤：失败测试 → 实现 → `git commit -m "feat: 成果整理页"`**

---

## Phase 6 · 自然语言新增入口（D4）

### Task 26: 意图分类（4 类白名单，FR-N01 / 02 §5.3）

**Files:**
- Create: `ke-domain/src/main/java/com/ke/domain/entry/NlIntent.java`、`ke-service/src/main/java/com/ke/service/entry/NlIntentClassifier.java`
- Test: `ke-boot/src/test/java/com/ke/entry/NlIntentIT.java`（Mock 网关按用例返回枚举 JSON）

**Interfaces:**
- Produces: `classify(text) → LINK_CARD | EXPLAIN | COMPARE | OUT_OF_SCOPE`（路由档小模型；提示词：「只输出以下之一…预订/购买/新工具/越权数据一律 OUT_OF_SCOPE」；绑定失败兜底 OUT_OF_SCOPE——安全侧错报优于漏报）

- [ ] **Step 1: 失败测试**：4 类各 1 例 + 「帮我订机票」→ OUT_OF_SCOPE + 非 JSON 返回兜底
- [ ] **Step 2: 实现 → Step 3: 提交** — `git commit -m "feat: 入口意图白名单分类"`

### Task 27: 配置草稿抽取 + EntryConfigValidator（FR-N01/N02/N07）

**Files:**
- Create: `ke-domain/src/main/java/com/ke/domain/entry/EntryConfig.java`、`EntryConfigValidator.java`、`EntryConfigViolationException.java`
- Create: `ke-service/src/main/java/com/ke/service/entry/EntryDraftService.java`（`POST /entries/nl-draft {cardId, text}`）
- Test: `ke-domain/.../EntryConfigValidatorTest.java`（穷举）+ `ke-boot/.../EntryDraftIT.java`

**Interfaces:**
- Produces: `record EntryConfig(String name, EntryType type, String goal, List<String> inputs, String serviceType, Set<Long> assetScope, String outputSpec, String why)`；`EntryConfigValidator.validate(config, allowedServiceTypes, allowedAssetScope)`：**serviceType 必须 ∈ 白名单、assetScope ⊆ 卡片已授权集合**（代码层硬约束，FR-N07）；草稿接口对 OUT_OF_SCOPE 返回替代建议文案（「暂不支持预订类入口，可试试：…」）；EXPLAIN/COMPARE 抽取 goal/output_spec；LINK_CARD 站内检索匹配目标卡；要求宽泛时给默认方案（不追问超过 1 轮，FR-N06）

- [ ] **Step 1: 穷举测试**：越权 serviceType 拒 / 越界 assetScope 拒 / 合法通过 / OUT_OF_SCOPE 文案
- [ ] **Step 2: 实现 → Step 3: 提交** — `git commit -m "feat: 入口草稿抽取与硬约束校验"`

### Task 28: 试运行 + 保存/送审 + 前端四步流（FR-N02–N05 界面含）

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/entry/EntryService.java`（`POST /entries`、`POST /entries/{id}/test`=限额内真实执行一次）、`EntryMutationController.java`
- Create: `frontend/apps/explorer/src/views/EntryCreateView.vue`（一句话→草稿表单全可编辑+🔒资料范围禁用→试运行预览→保存个人空间/提交审核双通道）
- Create: `frontend/apps/workbench/src/views/EntryStudioView.vue`（四步条+已有入口表：版本/使用量/试运行合格率）
- Test: `ke-boot/.../EntrySaveIT.java`、`frontend/.../EntryCreateView.spec.ts`

**Interfaces:**
- Produces: 私人入口 scope=PRIVATE 直接保存（个人空间可见）；公共 scope=PUBLIC → 建 review_task（复用 Task 4）；试运行结果与合格率计数落 entry 字段 `test_pass/test_total`

- [ ] **Step 1: 失败测试**：私人保存即 `GET /cards/{id}/entries` 可见带紫色「私人入口」chip；公共送审出现在审核队列
- [ ] **Step 2: 实现前后端 → Step 3: 提交** — `git commit -m "feat: 入口试运行与双通道发布"`

---

## Phase 7 · 分享与接续（D5）

### Task 29: 快照权限过滤（ke-domain 纯函数，02 §9「有权阅读≠有权转发」）

**Files:**
- Create: `ke-domain/src/main/java/com/ke/domain/share/SnapshotFilter.java`、`dto/SnapshotJson.java`
- Test: `ke-domain/src/test/java/com/ke/domain/share/SnapshotFilterTest.java`（穷举）

**Interfaces:**
- Produces: `SnapshotFilter.build(sessionTree, selectedNodeIds, visibleCardVersionIds, editor)`：输出不可变 `SnapshotJson{title, summary, nodes:[{title, cardVersionId, entries, findings}], artifacts:[…], generatedAt}`；规则：未勾选节点不入快照、隐藏分支不入、私人追问不入、接收方无权阅读的卡版本整节点剥离并留 `{removed:true, note}` 占位

- [ ] **Step 1: 穷举测试**：勾选子集生效 / 隐藏分支剥离 / 越权版本剥离+占位 / 标题摘要覆盖
- [ ] **Step 2: 实现（零 IO）→ Step 3: 提交** — `git commit -m "feat: 分享快照权限过滤器"`

### Task 30: 分享创建/撤销/免登录浏览（FR-H01–H04/H06）

**Files:**
- Create: `ke-service/src/main/java/com/ke/service/share/ShareService.java`、`ShareController.java`（`POST /shares`、`DELETE /shares/{token}`）、`SharePublicController.java`（`GET /s/{token}` permitAll）
- Modify: `SecurityConfig`（`/s/**` permitAll）
- Test: `ke-boot/src/test/java/com/ke/share/ShareIT.java`

**Interfaces:**
- Produces: token = `SecureRandom` nanoid(21) 不可枚举；`POST /shares {objectType, objectId, nodeIds[], title, summary}` → 过滤 → 快照 INSERT-only；撤销置 `revoked=true`；匿名 `GET /s/{token}` 返回快照 JSON（撤销/不存在统一 404 不泄露存在性）；重复访问埋点 `share_view`

- [ ] **Step 1: 失败测试**：创建→匿名可读且内容=快照（A4 数据面）；撤销后 404；token 长度=21 且两次生成不同
- [ ] **Step 2: 实现（nanoid 用自实现 Base62 SecureRandom，不引库）→ Step 3: 提交** — `git commit -m "feat: 分享快照与免登录浏览"`

### Task 31: 接续副本 + 差异提示（FR-H05/H07）

**Files:**
- Modify: `ShareService`（`POST /s/{token}/continue`，登录态）
- Test: `ke-boot/src/test/java/com/ke/share/ContinueIT.java`

**Interfaces:**
- Produces: 按快照复制新 session（`origin_share_id` 记源）+ 树节点副本；原分享者路径零写入（A5）；接收者重新运行服务时响应附 `notice:"来源/模型可能已更新，结果或有差异"`（前端 toast 常驻提示条）

- [ ] **Step 1: 失败测试**：接续后原会话 `updated_at` 不变、新会话树=快照节点数、`origin_share_id` 非空、埋点 `share_continue`
- [ ] **Step 2: 实现 → Step 3: 提交** — `git commit -m "feat: 接续副本与差异提示"`

### Task 32: 分享设置页 + 接收者视角页 + 分享页（FR-H02/H04 界面）

**Files:**
- Create: `frontend/apps/explorer/src/views/ShareSetupView.vue`（勾选节点/改标题摘要/自动移除私密提示条/权限开关）、`ShareView.vue`（接收者：路径时间线+成果+「沿此路径继续」）
- Create: `frontend/apps/explorer/share-app/`（独立入口 `share.html` + `main.ts`，Vite 多页构建 → 独立 chunk，仅引入 shared 组件，≤50KB gz）
- Test: `frontend/.../ShareSetupView.spec.ts`、`share bundle size` 检查（`pnpm --dir frontend exec vite build` 后断言 `share.html` 相关 chunk gz ≤50KB，写入 `share-app/__tests__/size.spec.ts` 用 `zlib.gzipSync` 计）

**步骤：失败测试 → 实现 → `git commit -m "feat: 分享设置/接收者页与免登录分享页"`**

---

## Phase 8 · 运营、账户与合规收尾（D6/D7）

### Task 33: 埋点事件与 6 指标（FR-O05 / 01 §5）

**Files:**
- Create: `ke-infra/src/main/resources/db/migration/V4__analytics.sql`（`analytics_event(id, user_id, event_type, payload jsonb, created_at)` + 指标视图）
- Create: `ke-service/src/main/java/com/ke/service/analytics/AnalyticsService.java`、`MetricsController.java`（`GET /wb/metrics`）
- Test: `ke-boot/src/test/java/com/ke/analytics/MetricsIT.java`

**Interfaces:**
- Produces: 8 事件常量 `session_start / node_visit(含 isNewKnowledge) / service_run(成败/时延/成本) / artifact_save / share_create / share_view / share_continue / entry_create(含试运行成败) / favorite`；各域服务埋点（一行业务一调用）；6 指标 SQL 视图：

```sql
CREATE VIEW v_metric_deepen AS            -- 1 有效深入率
SELECT count(*) FILTER (WHERE is_new_knowledge) * 1.0 / nullif(count(*),0) AS rate
FROM path_node WHERE visited_at > now() - interval '7 days';
-- 2 成果保存率 = artifact_save / 有 DONE 服务的会话数
-- 3 分享接续率 = share_continue / share_view
-- 4 入口成功率 = entry_create.test_pass / entry_create
-- 5 来源完整率 = 带 citation 的 agent_run / DONE 的 agent_run
-- 6 服务成本时延 = avg(cost), avg(latency_ms) FROM agent_run WHERE status='DONE'
```

- [ ] **Step 1: 失败测试**：造数（2 节点 1 新知）→ `GET /wb/metrics` 返回 6 键且 deepen=0.5
- [ ] **Step 2: 实现 → Step 3: 提交** — `git commit -m "feat: 埋点与六指标"`

### Task 34: 数据看板页 + 配额页 + 个人空间（FR-U02/U05/O05 界面）

**Files:**
- Create: `frontend/apps/workbench/src/views/MetricsView.vue`（6 指标卡 + 专题速览 + 成本 ¥/次 + 周柱状图）
- Create: `frontend/apps/explorer/src/views/MeView.vue`（四入口：收藏/会话/成果/私人入口 + 配额条与超额文案 04 §8.6）
- Test: 两个 view 的渲染 spec（指标卡 tabular-nums；配额满变警示色）

**步骤：失败测试 → 实现 → `git commit -m "feat: 看板/配额/个人空间页"`**

### Task 35: 全旅程端到端测试（A1–A5 自动化，01 §4 七步）

**Files:**
- Test: `ke-boot/src/test/java/com/ke/journey/JourneyIT.java`（Mock 网关 + Testcontainers）

**Interfaces:**
- Produces: 一条测试方法串七步，每步断言对应验收：

```java
@Test
void fullJourneyA1toA5() {
    // 1 A1① 登录→岳麓书院卡→入口 explain→DONE→讲解 artifact 含出处非空
    // 2 A1② 追问 compare→COMPARE_CARD artifact 可校验解析
    // 3 A1③ 时间线入口→node is_new_knowledge=true（第 3 个新知识对象）
    // 4 A2   回节点1 挂新子→原链路节点不变、新分支存在
    // 5 A3   nl-draft→草稿字段齐→试运行 DONE→私人保存→入口列表可见
    // 6 A4   summarize 勾选两支→REPORT→share 创建→匿名 GET /s/token 内容一致
    // 7 A5   新用户 continue→副本会话=快照节点数、原会话零变更
}
```

- [ ] **Step 1: 按注释逐步写实断言（每步用对应 Service/API，不 mock 域逻辑）**
- [ ] **Step 2: 全绿后提交** — `git commit -m "test: A1–A5 全旅程集成测试"`

### Task 36: 生产部署与合规项（02 §10 / R8）

**Files:**
- Create: `docker-compose.prod.yml`、`nginx/nginx.conf`（静态+反代 `/api`+限流+`/s/` 直通）
- Create: `ke-boot/src/main/resources/application-worker.yml`（`spring.main.web-application-type: none`）、`application-prod.yml`
- Create: `docs/上线检查单.md`（备份恢复演练、告警、算法备案评估状态、生成标识与日志留存确认、限额生效确认）

**Interfaces:**
- Produces: compose 五容器（nginx / app / app-worker `SPRING_PROFILES_ACTIVE=worker` / postgres / redis / minio）；PG 每日全量+WAL 归档脚本 `ops/backup.sh`；生成内容显著标识文案常量（讲解卡脚注 + 分享页页脚——Task 22/32 已渲染）

- [ ] **Step 1: 本地 `docker compose -f docker-compose.prod.yml up` 冒烟（健康检查全绿、worker 消费 run）**
- [ ] **Step 2: 检查单文档 + 提交** — `git commit -m "chore: 生产部署编排与上线检查单"`

### Task 37: 种子内容装载（01 §6 最小启动量）

**Files:**
- Create: `seed/cards-yuelu.json`（10 张示范卡全量 content_json，含入口与出处）、`seed/assets-book.csv`、`seed/assets-topic.csv`、`seed/load.sh`（登录→导入资产→建卡→送审→编辑账号发布）

**步骤：样例数据真实可导（结构过 `CardContentValidator`）→ `seed/load.sh` 在 dev 全量跑通 → `git commit -m "chore: 种子内容装载脚本"`（150 卡/500 单元的全量内容由编辑按 01 §6 生产，本任务交付工具与样例）**

---

## 验收映射（自查用）

| 验收 | 落点 |
|------|------|
| A1 三次有效深入 | Task 16/18/22 + JourneyIT 步 1–3 |
| A2 回到节点建分支 | Task 16/17 + JourneyIT 步 4 |
| A3 一句话入口 ≤30s | Task 26/27/28 + JourneyIT 步 5 |
| A4 分享免登录一致 | Task 29/30/32 + JourneyIT 步 6 |
| A5 接续副本不覆盖 | Task 31 + JourneyIT 步 7 |

## P0 需求覆盖矩阵

| 域 | FR → Task |
|----|-----------|
| D1 | C01→13 · C02→1/3 · C03/C04/C05/C06→2/14 · C07→3/4 · C08→1/3/8 · C09→6/23 · C10→15 |
| D2 | E01→16/17 · E02→6/14 · E03→16 · E04→17 · E05→16/17 · E07→24/25 · E08→22 · E09→22/24 · E10→22 · E11→22 · E12→18/22 · E13→2/14（任务卡模板） |
| D3 | S01→14 · S02→18 · S03→24/25 · S04→20/21 · S05→19/22 · S06→23 · S10→20 · S13→20/33 |
| D4 | N01→26/27 · N02→27/28 · N03→28 · N04→28 · N05→4/28 · N06→27 · N07→27 |
| D5 | H01/H02/H03→29/30/32 · H04→30/32 · H05→31/32 · H06→30/32 |
| D6 | O01/O02→5/11 · O03→4/10 · O05→33/34 |
| D7 | U01→W1+12 · U02→15/34 · U03→3/4/31 · U05→20/34 |

## 范围外（01 文档自身定义的 P1/P2，明确不做，防scope漂移）

- **P1**：C11 音视频卡·C12 地图卡·C13 关系卡、E06 重复访问提示、E13 通用目标拆解、S07/S08 服务、S11 结果缓存、N08 入口版本管理全量·N09 模板库、H07 差异详情·H08 停用节点标识·H09 模板分享·H10 分享统计看板、O04 反馈系统·O06 成果沉淀、U04 偏好、埋点看板二期增强、微信登录
- **P2**：C14 互动卡、S09 推演演示、N10 多人贡献、O07 机构授权、付费服务

## Self-Review 记录

- **规格覆盖**：D1–D7 全部 P0 FR 均可在矩阵指到 Task；非功能约束（性能/成本/合规/审计）进入 Global Constraints 与 Task 20/33/36。
- **占位符扫描**：唯一的「示例性注释」是 JourneyIT 的分步注释——步骤本身即规格（每步对应 A1–A5 断言点），配套各任务的领域测试已给出可抄用例；其余任务均含具体文件/签名/规则。
- **类型一致性**：`CardContentValidator.parseAndValidate(templateType,json)`、`CitationSanitizer.sanitize(sections,allowed)`、`SnapshotFilter.build(...)`、`EntryConfigValidator.validate(config,allowedTypes,allowedScope)`、`QuotaService.tryConsume(uid)`、`selectSubtree(nodeId)` 在生产/消费任务间一致；前端组件事件（`@enter/@open`）与渲染器映射在 14/15/17 一致。
- **与 W1 计划衔接**：Phase 0 为入口；本计划 Task 编号独立，执行顺序 = Phase 0 → 1 → 2/3（可并行）→ 4 → 5 → 6 → 7 → 8。
