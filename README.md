# 知识探索卡片系统 — 设计交付包

基于《知识探索卡片系统策划方案》产出的需求拆解、技术架构与可交互原型，全部以**可实现性优先**为原则。

## 目录结构

```
knowledge-explorer-design/
├── README.md                 # 本文件
├── docs/
│   ├── 01-需求拆解.md         # 功能清单、优先级、MVP 裁剪、风险与里程碑
│   ├── 02-技术架构.md         # 技术选型、数据模型、关键流程、部署与成本
│   ├── 03-原型设计说明.md     # 原型页面清单、交互流程与验证目标
│   ├── 04-主题与UI规范.md     # 主题色板（实测 WCAG）、字阶、组件规范、Token 与双端落地
│   └── 05-开发计划.md         # 12 周周/人任务分解、里程碑验收、协作与测试、需求追溯
└── prototype/
    ├── index.html            # 移动探索端原型（浏览器直接打开）
    ├── workbench.html        # 创作/运营工作台原型（浏览器直接打开）
    └── styleguide.html       # 可视化 UI 规范页（与 04 文档同源，色板/组件/状态全景）
```

## 本地环境（WSL PostgreSQL / Redis）

开发与测试直接使用 WSL 中的 PostgreSQL 与 Redis，Windows 侧经 `localhost` 访问，不使用 Docker。

- **PostgreSQL 16.10**：`localhost:5432`，用户 `ke` / 密码 `ke`；业务库 `ke`，测试库 `ke_test`（`ke_test` 为共享测试库，测试运行前会被重置：`DROP SCHEMA public CASCADE` 后由 Flyway 重新迁移）
- **Redis 6.0.16**：`localhost:6379`，密码经环境变量 `KE_REDIS_PASSWORD` 注入（取自仓库根 `.env`，该文件已 gitignore，请勿把密码明文写入任何入库文件）

一次性建库语句（WSL 内执行）：

```bash
sudo -u postgres psql -c "CREATE USER ke WITH PASSWORD 'ke' CREATEDB;"
sudo -u postgres createdb -O ke ke
sudo -u postgres createdb -O ke ke_test
```

两个服务随 WSL 自启；若未运行，执行：

```bash
wsl -e bash -c "sudo service postgresql start && sudo service redis-server start"
```

## 快速查看原型

两个原型均为**单文件、零依赖**的 HTML，直接用浏览器打开即可（无需构建、可离线）：

```bash
# 方式一：直接双击/打开文件
# 方式二：起一个本地服务
cd knowledge-explorer-design/prototype && python3 -m http.server 8080
# 浏览器访问 http://127.0.0.1:8080
```

- `index.html`：移动探索端，含 17 个界面状态，覆盖核心闭环（发现卡片 → 选择入口 → 智能体服务 → 路径分支 → 成果整理 → 分享 → 接续探索）
- `workbench.html`：网页工作台，含卡片管理、入口编排（自然语言生成配置）、审核中心、知识资源、数据看板 5 个模块
- `styleguide.html`：主题与 UI 规范的可视化呈现（「纸墨编辑部」方向：色彩 / 字体 / 组件 / 状态 / Token），与 `docs/04` 同源维护

## 核心结论速览

| 主题 | 结论 |
|------|------|
| MVP 范围 | 4 种卡片模板 + 3 个专题 + 2 类智能体服务 + 路径分支 + 一句话新增个人入口 + 分享接续，人工审核 |
| 技术路线 | 后端：Java 模块化单体（Java 21 虚拟线程 + Spring Boot 3 + MyBatis-Plus + PostgreSQL/pgvector），LLM 走 Spring AI 云 API 起步、量级验证后再 vLLM 自托管；前端：双端统一 Vue3（探索端 Vant / 工作台 Element Plus，pnpm monorepo） |
| 移动端 | H5 优先（分享链接要求免安装打开），小程序二期包装 |
| 最大风险 | 自然语言入口编排成功率、内容生产成本，均已给出收窄与降级策略（见 01 文档风险表） |
| 团队与周期 | 3 名开发 + 1 名编辑（兼职），一期约 12 周完成 MVP 验收 |

详细依据见 `docs/` 下三份文档。
