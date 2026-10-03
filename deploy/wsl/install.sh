# 知识探索 · WSL 原生部署(单 nginx 双上下文)
#
# 拓扑: WSL 内 nginx(:8081) 静态托管双前端 + 反代后端(:8090,prod profile)
#   http://localhost:8081/app/        探索端(explorer)
#   http://localhost:8081/workbench/  工作台(workbench)
#   http://localhost:8081/api/…       后端 API(含 /api/images 配图)
#   http://localhost:8081/s/{token}   免登录分享(HTML→308 /app/s/…,JSON→后端)
# 依赖: WSL 里的 PostgreSQL(:5432) 与 Redis(:6379),Java 21(openjdk-21-jre-headless),nginx
#
# 安装(在仓库 deploy/wsl 目录执行一次):
#   sudo cp nginx-ke.conf /etc/nginx/sites-available/ke.conf
#   sudo ln -sf /etc/nginx/sites-available/ke.conf /etc/nginx/sites-enabled/ke.conf
#   sudo nginx -t && sudo systemctl reload nginx
#   ./install.sh   # 生成 deploy.env(密钥)并启动后端
#
# 日常: ./start.sh | ./stop.sh | tail -f logs/backend.log

set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# ---------- deploy.env:首次生成,后续复用(密钥不入库) ----------
if [ ! -f "$DIR/deploy.env" ]; then
  REPO_ENV="${REPO_ENV:-/mnt/d/code/knowledge-explorer/.env}"
  [ -f "$REPO_ENV" ] || { echo "缺少仓库 .env($REPO_ENV):需要 KE_LLM_API_KEY / KE_REDIS_PASSWORD"; exit 1; }
  # shellcheck disable=SC1090
  LLM_KEY="$(grep -E '^KE_LLM_API_KEY=' "$REPO_ENV" | cut -d= -f2-)"
  REDIS_PW="$(grep -E '^KE_REDIS_PASSWORD=' "$REPO_ENV" | cut -d= -f2-)"
  cat > "$DIR/deploy.env" <<EOF
# 由 install.sh 生成(含密钥,勿入库勿外传)
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8090
KE_DB_URL=jdbc:postgresql://localhost:5432/ke?stringtype=unspecified
KE_DB_USER=ke
KE_PG_PASSWORD=ke
KE_REDIS_HOST=localhost
KE_REDIS_PASSWORD=${REDIS_PW}
KE_LLM_API_KEY=${LLM_KEY}
KE_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
KE_UPLOAD_DIR=$DIR/data/uploads
EOF
  chmod 600 "$DIR/deploy.env"
  echo "deploy.env 已生成"
fi

# ---------- 启动 ----------
if [ -f backend.pid ] && kill -0 "$(cat backend.pid)" 2>/dev/null; then
  echo "后端已在运行(pid=$(cat backend.pid))"; exit 0
fi
set -a; source "$DIR/deploy.env"; set +a
nohup java -jar "$DIR/app.jar" > "$DIR/logs/backend.log" 2>&1 &
echo $! > backend.pid
echo "后端启动中(pid=$(cat backend.pid))…"
for _ in $(seq 1 40); do
  if curl -sf "http://127.0.0.1:${SERVER_PORT}/actuator/health" >/dev/null 2>&1; then
    echo "后端就绪: http://127.0.0.1:${SERVER_PORT}/actuator/health"
    echo "探索端:   http://localhost:8081/app/"
    echo "工作台:   http://localhost:8081/workbench/"
    exit 0
  fi
  sleep 1
done
echo "后端 40s 内未就绪,查看 logs/backend.log"; exit 1
