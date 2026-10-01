#!/usr/bin/env bash
# 组装生产前端 dist(Task 36)—— docker-compose.prod.yml 挂载 ./frontend/dist:ro
#
# 前端是 pnpm workspace 双 SPA(frontend/apps/explorer 公众端、frontend/apps/workbench 工作台),
# `pnpm build` 产出各自 app 目录下的 dist/;本脚本把它们归一到 frontend/dist/ 下,
# 与 nginx.conf 的两个 root(/usr/share/nginx/html/{explorer,workbench})一一对应。
#
# 用法:在仓库根执行  bash ops/build-frontend.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/frontend"

echo "[1/3] pnpm install(如已安装可跳过)"
pnpm install --frozen-lockfile

echo "[2/3] 构建双 SPA"
pnpm --filter @ke/explorer build
pnpm --filter @ke/workbench build

echo "[3/3] 组装 $ROOT/frontend/dist"
rm -rf "$ROOT/frontend/dist"
mkdir -p "$ROOT/frontend/dist/explorer" "$ROOT/frontend/dist/workbench"
cp -r apps/explorer/dist/.  "$ROOT/frontend/dist/explorer/"
cp -r apps/workbench/dist/. "$ROOT/frontend/dist/workbench/"

echo "完成:frontend/dist/{explorer,workbench} 已就绪,可 docker compose -f docker-compose.prod.yml up -d"
