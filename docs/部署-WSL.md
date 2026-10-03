# WSL 原生部署（单 nginx 双上下文）

> 2026-10-03 落地。后端 Spring Boot（prod profile）与双前端静态产物全部运行在 WSL 内，
> 由一个 nginx 实例按上下文路径分发；PG/Redis 沿用 WSL 内既有实例，与 dev 共库。

## 拓扑与入口

| 入口 | 地址 | 说明 |
|---|---|---|
| 探索端 | http://localhost:8081/app/ | explorer（vite `--base=/app/` 构建） |
| 工作台 | http://localhost:8081/workbench/ | workbench（vite `--base=/workbench/` 构建） |
| 后端 API | http://localhost:8081/api/… | 反代 127.0.0.1:8090（含 /api/images 配图） |
| 免登录分享 | http://localhost:8081/s/{token} | `Sec-Fetch-Dest: document` 的导航 302 归一到 `/app/s/{token}`，fetch 的 JSON 直代理后端 |
| 根路径 | http://localhost:8081/ | 308 → `/app/` |

```
Windows 浏览器 ──► WSL nginx :8081 ─┬─ /app/        → 静态 www/app/（explorer dist）
                                    ├─ /workbench/  → 静态 www/workbench/（workbench dist）
                                    ├─ /api/ /s/    → 127.0.0.1:8090（app.jar，prod）
                                    └─ 其余          → 308 /app/
后端(WSL) ──► PostgreSQL localhost:5432/ke ＋ Redis localhost:6379(db0,有密码) ＋ DeepSeek 外网
配图文件 ──► /home/zsx/workspace/knowledge-explorer/data/uploads（{yyyy}/{MM}/{uuid}.ext）
```

## 部署目录

`/home/zsx/workspace/knowledge-explorer/`

```
app.jar          # ke-boot 打包产物(prod profile 由 deploy.env 的 SPRING_PROFILES_ACTIVE 决定)
www/app/         # explorer 静态产物
www/workbench/   # workbench 静态产物
data/uploads/    # 卡片配图本地存储(KE_UPLOAD_DIR)
logs/backend.log # 后端日志
deploy.env       # 运行环境量(含密钥,chmod 600,勿入库)
install.sh       # 首次:生成 deploy.env 并启动后端(此后等价 start.sh,幂等)
start.sh         # 启动(已在运行则退出)
stop.sh          # 停止
backend.pid      # 运行中 pid
```

`deploy.env` 关键项：`SERVER_PORT=8090`、`KE_DB_URL=jdbc:postgresql://localhost:5432/ke`、
`KE_PG_PASSWORD`、`KE_REDIS_HOST=localhost`、`KE_REDIS_PASSWORD`、`KE_LLM_API_KEY`、
`KE_JWT_SECRET`（openssl 随机生成）、`KE_UPLOAD_DIR`。

## nginx 站点

- 站点文件：`/etc/nginx/sites-available/ke.conf`（仓库源 `deploy/wsl/nginx-ke.conf`），listen 8081。
- **本机 nginx.conf 特殊性**：标准 `include sites-enabled/*` 被注释（81/9001/9002 为手工直写的其他项目），
  因此在 `/etc/nginx/nginx.conf` 里**单独加了一行** `include /etc/nginx/sites-enabled/ke.conf;`——
  升级 nginx.conf 时注意保留该行。
- `/s/` 的 HTML 导航与 API 分流用 `Sec-Fetch-Dest: document` 判别。**不要用 Accept 嗅探**：
  Electron 等内核的 fetch 默认 Accept 也含 text/html，会把 API 误 308 到 SPA。
- 分流跳转用 **302** 而非 308：308 可被浏览器永久缓存，一旦误配过会把错误映射钉死在用户缓存里。

## 日常操作

```bash
# 在 WSL 内
cd /home/zsx/workspace/knowledge-explorer
./start.sh            # 启动/已是运行态则跳过;40s 内 health 探活
./stop.sh             # 停止后端
tail -f logs/backend.log
```

```bash
# 在 Windows 仓库根(Git Bash)——发版重建
JAVA_HOME="D:/develop/jdk/jdk-21.0.12+8" ./mvnw install -DskipTests -q
wsl -e bash -lc 'cp /mnt/d/code/knowledge-explorer/ke-boot/target/ke-boot-0.1.0-SNAPSHOT.jar \
  /home/zsx/workspace/knowledge-explorer/app.jar && \
  /home/zsx/workspace/knowledge-explorer/stop.sh && \
  /home/zsx/workspace/knowledge-explorer/start.sh'
```

## 前端重建（注意 MSYS 路径转换坑）

Git Bash 里 `vite build --base=/app/` 的 `/app/` 会被 MSYS 转成 `C:/Program Files/Git/app/`，
产物资源 URL 全错。必须屏蔽参数转换：

```bash
cd frontend/apps/explorer  && MSYS2_ARG_CONV_EXCL="--base" pnpm exec vite build --base=/app/
cd frontend/apps/workbench && MSYS2_ARG_CONV_EXCL="--base" pnpm exec vite build --base=/workbench/
```

两端路由已改为 `createWebHistory(import.meta.env.BASE_URL)`（dev 下 BASE_URL=/，行为不变），
并各补了标准 `src/vite-env.d.ts`（提供 `import.meta.env` 与 `?raw` 类型）。

构建产物拷入 WSL 后无需重启后端（静态文件即时生效）：

```bash
wsl -e bash -lc 'rm -rf /home/zsx/workspace/knowledge-explorer/www/{app,workbench} && \
  cp -r /mnt/d/code/knowledge-explorer/frontend/apps/explorer/dist  /home/zsx/workspace/knowledge-explorer/www/app && \
  cp -r /mnt/d/code/knowledge-explorer/frontend/apps/workbench/dist /home/zsx/workspace/knowledge-explorer/www/workbench'
```

## 验收记录（2026-10-03）

- `/app/` `/app/cards/1`（含配图 `/api/images/1`）`/workbench/` 登录、`/api/*` 全通；
- 根路径 308、`/s/{token}` 导航 302→`/app/s/{token}` 匿名渲染快照、fetch JSON 直达后端；
- 数据与 dev 共库（同一 PG），卡片/分享/账号完全一致。
