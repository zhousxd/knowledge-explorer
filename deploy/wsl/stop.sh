#!/usr/bin/env bash
# 停止部署在 WSL 的后端(不动 nginx;前端为静态文件无需停)
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$DIR/backend.pid" ] && kill -0 "$(cat "$DIR/backend.pid")" 2>/dev/null; then
  kill "$(cat "$DIR/backend.pid")"
  echo "后端已停止(pid=$(cat "$DIR/backend.pid"))"
  rm -f "$DIR/backend.pid"
else
  echo "后端未在运行"
fi
