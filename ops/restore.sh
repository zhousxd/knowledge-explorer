#!/usr/bin/env bash
# PostgreSQL 恢复脚本(Task 36)—— 恢复步骤注释化 + 交互确认执行
#
# ★ 上线前必须在演练环境完整走一遍本流程并记录到 docs/上线检查单.md「备份可恢复演练」,
#   未经演练的备份不算备份。
#
# 用法:BACKUP_FILE=/var/backups/ke/ke-full-20260930-030000.dump bash ops/restore.sh
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
BACKUP_FILE="${BACKUP_FILE:?BACKUP_FILE required, e.g. /var/backups/ke/ke-full-YYYYMMDD-HHMMSS.dump}"

[ -f "$BACKUP_FILE" ] || { echo "备份文件不存在:$BACKUP_FILE"; exit 1; }

cat <<'EOF'
恢复步骤(本脚本自动执行;手工操作时按此逐条):
  1. 停写:docker compose -f docker-compose.prod.yml stop app app-worker
     (nginx 保留时,公网将收到 502——如需维护页,先把 nginx 切到静态维护页)
  2. 清库重建 schema:
        docker compose -f docker-compose.prod.yml exec -T postgres \
          psql -U ke -d ke -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
     (如对象属主非 ke,先 DROP OWNED BY ke CASCADE)
  3. 恢复全量:
        docker compose -f docker-compose.prod.yml exec -T postgres \
          pg_restore -U ke -d ke --no-owner --role=ke < BACKUP_FILE
  4. 起服务:docker compose -f docker-compose.prod.yml up -d
     Flyway 校验已恢复库的 schema_history 与代码版本一致,不匹配会拒绝启动(属预期,核对版本)
  5. 验收:登录冒烟 + 卡片列表 + 分享页打开;RPO=上次全量时点(两次备份间变更丢失,
     二期 WAL 归档启用后收敛到分钟级,见 ops/backup.sh 头注)
EOF

printf '确认恢复?将停止 app/app-worker 并清空当前库 [yes/N]: '
read -r ANSWER
[ "$ANSWER" = "yes" ] || { echo "已取消"; exit 1; }

echo "[restore 1/4] 停 app / app-worker"
docker compose -f "$COMPOSE_FILE" stop app app-worker

echo "[restore 2/4] 清库重建 schema"
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  psql -U "${KE_DB_USER:-ke}" -d ke \
  -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'

echo "[restore 3/4] pg_restore $BACKUP_FILE"
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  pg_restore -U "${KE_DB_USER:-ke}" -d ke --no-owner --role="${KE_DB_USER:-ke}" \
  < "$BACKUP_FILE"

echo "[restore 4/4] 重启服务"
docker compose -f "$COMPOSE_FILE" up -d

echo "恢复完成。请执行验收:登录冒烟 / 卡片列表 / 分享页打开,并记录到 docs/上线检查单.md"
