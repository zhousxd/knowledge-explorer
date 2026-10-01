#!/usr/bin/env bash
# PostgreSQL 每日全量备份(Task 36 / 02 §10.1)
#
# 用法:
#   BACKUP_DIR=/var/backups/ke RETAIN_DAYS=14 bash ops/backup.sh
#   (在仓库根,或任意能连到 docker compose 的机器上执行)
#
# 建议生产 cron(每天 03:00):
#   0 3 * * * cd /opt/knowledge-explorer && BACKUP_DIR=/var/backups/ke bash ops/backup.sh >> /var/log/ke-backup.log 2>&1
#
# ★ WAL 归档(连续归档/PITR)为二期项(02 §10.1):本脚本仅每日全量,两次备份之间的
#   数据变更不在恢复点内。二期启用时需配置 postgres 的 archive_command + 备份侧 restore_command,
#   并把 WAL 段推送对象存储(MinIO 版本化)——本脚本预留 BACKUP_DIR 目录结构不变。
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/ke}"
RETAIN_DAYS="${RETAIN_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"

echo "[backup $STAMP] pg_dump 全量(自定义格式 -Fc,支持 pg_restore 并行/选择性恢复)"
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  pg_dump -U "${KE_DB_USER:-ke}" -d ke -Fc \
  > "$BACKUP_DIR/ke-full-$STAMP.dump"

SIZE="$(du -h "$BACKUP_DIR/ke-full-$STAMP.dump" | cut -f1)"
echo "[backup $STAMP] 完成:$BACKUP_DIR/ke-full-$STAMP.dump($SIZE)"

# 可选:同步到对象存储(MinIO/OSS)——一期单机 MVP 先落本地盘,生产建议加密后异机存放
#   mc cp "$BACKUP_DIR/ke-full-$STAMP.dump" ke-oss/backups/

echo "[backup $STAMP] 清理 ${RETAIN_DAYS} 天前的旧备份"
find "$BACKUP_DIR" -name 'ke-full-*.dump' -mtime "+$RETAIN_DAYS" -print -delete

echo "[backup $STAMP] 备份可恢复性建议每日抽检一个文件:"
echo "  docker compose -f $COMPOSE_FILE exec -T postgres pg_restore --list < $BACKUP_DIR/ke-full-$STAMP.dump | head"
