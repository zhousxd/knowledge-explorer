#!/usr/bin/env bash
# 种子内容装载脚本（01 §6 最小启动量工具 + 样例,Task 37）
#
# 职责：注册/登录两个运营账号（卡维护者 + 编辑）→ psql 提权（CREATOR/EDITOR）→
#       编辑账号导入知识资产 seed/assets-book.csv（幂等:种子 title 全部已存在则跳过导入,
#       导入端点对重复 title 不去重,故由本脚本预检收敛）→
#       逐张装载 seed/cards-yuelu.json（幂等:按 title 查重,已存在且已发布则跳过;
#       已存在但 DRAFT/PENDING 则复用 cardId 继续送审+发布,重跑收敛到全 PUBLISHED）→
#       建卡(DRAFT) → 送审(PENDING) → 编辑账号发布(PUBLISHED) → 尾部打印统计。
#
# 依赖：bash + curl + python3（拆种子数组/解 JWT role，均为各平台基线工具，零额外安装）；
#       断言用 curl + grep 宽松断言（envelope code=0 / 字段逐字）。psql 仅用于提权：
#       本机无 psql 时自动回退 `wsl -e psql`（本仓库开发环境约定），再无则打印 SQL 退出，
#       由执行者手工执行后重跑（幂等，重跑自动续传）。
#
# 环境变量（均可覆盖，默认值对齐 README「本地环境」）：
#   KE_BASE_URL          后端基址            默认 http://localhost:8080
#   SEED_CREATOR_PHONE / SEED_CREATOR_PASSWORD / SEED_CREATOR_NICKNAME  卡维护者账号
#   SEED_EDITOR_PHONE  / SEED_EDITOR_PASSWORD  / SEED_EDITOR_NICKNAME    审核发布账号
#   ADMIN_DB_URL         psql 连接串（提权用）默认 postgresql://ke:ke@localhost:5432/ke
# 注：KE_LLM_API_KEY 与本脚本无关（装载路径不触发智能体）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SEED_FILE="$ROOT/seed/cards-yuelu.json"
ASSETS_CSV="$ROOT/seed/assets-book.csv"

# 仓库根 .env 存在则读取其中 KE_*/SEED_*/ADMIN_* 键（宽松解析,不覆盖已有导出;须先于默认值推导）
if [ -f "$ROOT/.env" ]; then
  while IFS='=' read -r k v; do
    case "$k" in KE_*|SEED_*|ADMIN_DB_URL) [ -n "${!k:-}" ] || export "$k=$v" ;; esac
  done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$ROOT/.env" || true)
fi

BASE_URL="${KE_BASE_URL:-http://localhost:8080}"
CREATOR_PHONE="${SEED_CREATOR_PHONE:-13900000001}"
CREATOR_PASSWORD="${SEED_CREATOR_PASSWORD:-Seed#123456}"
CREATOR_NICKNAME="${SEED_CREATOR_NICKNAME:-种子编辑·山长}"
EDITOR_PHONE="${SEED_EDITOR_PHONE:-13900000002}"
EDITOR_PASSWORD="${SEED_EDITOR_PASSWORD:-Seed#654321}"
EDITOR_NICKNAME="${SEED_EDITOR_NICKNAME:-种子审读·压卷}"
ADMIN_DB_URL="${ADMIN_DB_URL:-postgresql://ke:ke@localhost:5432/ke}"

CREATED=0; SKIPPED=0; FAILED=0; FAILED_TITLES=()

log()  { printf '[load.sh] %s\n' "$*"; }
# 诊断输出到 stderr:ensure_role 在 $( ) 命令替换内执行,stdout 会被并入返回的
# token——日志若走 stdout 会污染 Bearer 头(Tomcat 以 400 拒绝,报 header 不合规)
elog() { printf '[load.sh] %s\n' "$*" >&2; }
die()  { printf '[load.sh] ERROR: %s\n' "$*" >&2; exit 2; }

need() { command -v "$1" >/dev/null 2>&1 || die "缺少依赖命令: $1"; }
need curl; need python3

[ -f "$SEED_FILE" ] || die "种子文件不存在: $SEED_FILE"

# 服务可达性（任意 HTTP 响应即可,连接拒绝才视为未启动）
curl -s -o /dev/null --max-time 3 "$BASE_URL/" || die "后端不可达: $BASE_URL（先启动 ke-boot dev）"

# ---------- 账号 ----------

# post_json <url> <json-body> [bearer] → 响应体。
# 走临时文件（--data-binary @file）而非命令行参数：Git Bash 调 Windows 原生 curl 时
# 命令行里的中文按活动代码页转码会损坏 JSON,文件字节直传无此问题。
post_json() {
  local url="$1" json="$2" token="${3:-}" body_file
  body_file="$(mktemp)"
  printf '%s' "$json" > "$body_file"
  if [ -n "$token" ]; then
    curl -sS --max-time 15 -X POST -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $token" --data-binary @"$body_file" "$url"
  else
    curl -sS --max-time 15 -X POST -H 'Content-Type: application/json' \
      --data-binary @"$body_file" "$url"
  fi
  local rc=$?
  rm -f "$body_file"
  return $rc
}

# register_and_login <phone> <password> <nickname> → 输出 accessToken
register_and_login() {
  local phone="$1" password="$2" nickname="$3" resp token
  resp="$(post_json "$BASE_URL/api/auth/register" \
    "{\"phone\":\"$phone\",\"password\":\"$password\",\"nickname\":\"$nickname\"}")" || true
  case "$resp" in
    *'"code":0'*|*'该手机号已注册'*) : ;; # 新注册成功或幂等已存在
    *) die "注册失败(phone=$phone): $resp" ;;
  esac
  resp="$(post_json "$BASE_URL/api/auth/login" \
    "{\"phone\":\"$phone\",\"password\":\"$password\"}")" || die "登录请求失败(phone=$phone)"
  printf '%s' "$resp" | grep -q '"code":0' || die "登录失败(phone=$phone): $resp"
  token="$(printf '%s' "$resp" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)"
  [ -n "$token" ] || die "登录响应缺 accessToken: $resp"
  printf '%s' "$token"
}

# role_of <accessToken> → JWT role claim（python3 解 payload,零依赖;
# 走 stdout.buffer 字节输出:Windows python 文本模式会把 \n 翻译成 \r\n 污染返回值）
role_of() {
  python3 -c '
import base64, json, sys
payload = sys.argv[1].split(".")[1]
payload += "=" * (-len(payload) % 4)
role = json.loads(base64.urlsafe_b64decode(payload)).get("role", "")
sys.stdout.buffer.write(role.encode("utf-8"))' "$1"
}

# run_psql <db-url> <sql> → 0 成功；127 本机与 WSL 均无 psql
run_psql() {
  if command -v psql >/dev/null 2>&1; then
    psql "$1" -v ON_ERROR_STOP=1 -c "$2"
  elif command -v wsl >/dev/null 2>&1 || command -v wsl.exe >/dev/null 2>&1; then
    wsl -e psql "$1" -v ON_ERROR_STOP=1 -c "$2"
  else
    return 127
  fi
}

# ensure_role <phone> <password> <nickname> <期望角色>（stdout 只输出 token,诊断一律走 stderr）
ensure_role() {
  local phone="$1" password="$2" nickname="$3" want="$4" token actual sql rc psql_out
  token="$(register_and_login "$phone" "$password" "$nickname")"
  # token 形状门禁:三段 base64url JWT。任何被 $() 捕获进来的杂散输出都会在此被拦下
  printf '%s' "$token" | grep -qE '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$' \
    || die "$phone 访问令牌格式异常(期望三段 JWT),实际: ${token:0:60}"
  actual="$(role_of "$token")"
  if [ "$actual" != "$want" ]; then
    sql="UPDATE ke_user SET role = '$want' WHERE phone = '$phone';"
    elog "账号 $phone 当前角色=$actual,提权为 $want ..."
    rc=0; psql_out="$(run_psql "$ADMIN_DB_URL" "$sql" 2>&1)" || rc=$?
    [ -n "$psql_out" ] && printf '[load.sh] %s\n' "$psql_out" >&2
    if [ "$rc" -eq 127 ]; then
      # 本机与 WSL 均无 psql：打印 SQL 让执行者手工执行（幂等,重跑自动续传）
      cat >&2 <<EOF
[load.sh] 本机与 WSL 均无 psql,请手工执行以下 SQL 后重跑本脚本:
  psql "$ADMIN_DB_URL" -c "$sql"
EOF
      exit 2
    elif [ "$rc" -ne 0 ]; then
      die "psql 提权失败(rc=$rc):检查 ADMIN_DB_URL=$ADMIN_DB_URL 是否可达"
    fi
    token="$(register_and_login "$phone" "$password" "$nickname")" # 重新登录换新 role 的 token
    [ -n "$token" ] || die "提权后重新登录失败,终止"
    actual="$(role_of "$token")"
    [ "$actual" = "$want" ] || die "提权后角色仍为 $actual(期望 $want),检查 ADMIN_DB_URL 连接的库是否为后端业务库"
  fi
  printf '%s' "$token"
}

log "步骤 1/4 准备账号（creator=$CREATOR_PHONE / editor=$EDITOR_PHONE）"
CREATOR_TOKEN="$(ensure_role "$CREATOR_PHONE" "$CREATOR_PASSWORD" "$CREATOR_NICKNAME" CREATOR)"
EDITOR_TOKEN="$(ensure_role "$EDITOR_PHONE" "$EDITOR_PASSWORD" "$EDITOR_NICKNAME" EDITOR)"

# ---------- 步骤 2/4:导入知识资产（FR-O01,仅 EDITOR/OPERATOR,用编辑账号） ----------

log "步骤 2/4 导入知识资产（$ASSETS_CSV,编辑账号执行）"
[ -f "$ASSETS_CSV" ] || die "资产 CSV 不存在: $ASSETS_CSV"

# 路径适配:Git Bash 下原生 curl 的 -F file=@ 需 Windows 形式路径;WSL/原生 psql 环境原样
ASSETS_CSV_CURL="$(cygpath -m "$ASSETS_CSV" 2>/dev/null || printf '%s' "$ASSETS_CSV")"

# 统计种子里已入库的资产条数（导入端点对重复 title 不去重,重复导入会翻倍,故先预检收敛）
count_seed_assets() {
  curl -sS --max-time 10 "$BASE_URL/api/wb/assets?size=100" \
    -H "Authorization: Bearer $EDITOR_TOKEN" | python3 -c '
import csv, json, sys
resp = json.loads(sys.stdin.buffer.read().decode("utf-8"))
rows = list(csv.reader(open(sys.argv[1], encoding="utf-8")))[1:]
seed_titles = [r[1] for r in rows if r]
items = (resp.get("data") or {}).get("items") or []
titles = {a.get("title") for a in items}
sys.stdout.buffer.write(str(sum(1 for t in seed_titles if t in titles)).encode())' "$ASSETS_CSV" || printf '0'
}
SEED_ASSET_ROWS="$(python3 -c 'import csv,sys;sys.stdout.buffer.write(str(sum(1 for r in csv.reader(open(sys.argv[1],encoding="utf-8")) if r)).encode())' "$ASSETS_CSV")"
SEED_ASSET_ROWS=$((SEED_ASSET_ROWS - 1)) # 去表头行

EXISTING="$(count_seed_assets)"
if [ "$EXISTING" -ge "$SEED_ASSET_ROWS" ]; then
  log "资产已存在（$EXISTING/$SEED_ASSET_ROWS 条种子资产已入库),跳过导入"
  ASSETS_IMPORTED=0
else
  [ "$EXISTING" -gt 0 ] && elog "注意:已有 $EXISTING/$SEED_ASSET_ROWS 条（部分导入状态),本次导入可能与存量重复,建议清库后重跑"
  resp="$(curl -sS --max-time 20 -X POST "$BASE_URL/api/wb/assets/import" \
    -H "Authorization: Bearer $EDITOR_TOKEN" \
    -F "file=@$ASSETS_CSV_CURL;type=text/csv")" || true
  if ! printf '%s' "$resp" | grep -q '"code":0'; then
    die "资产导入失败: $resp"
  fi
  ASSETS_IMPORTED="$(printf '%s' "$resp" | grep -o '"imported":[0-9]*' | head -1 | cut -d: -f2)"
  ASSETS_IMPORTED="${ASSETS_IMPORTED:-0}"
  EXISTING="$(count_seed_assets)" # 导入后复核实际入库条数
  log "资产导入完成:本次导入 $ASSETS_IMPORTED 条,种子资产入库 $EXISTING/$SEED_ASSET_ROWS 条"
fi

# ---------- 步骤 3/4:装载卡片 ----------

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mapfile -t CARD_FILES < <(python3 - "$SEED_FILE" "$WORK" <<'PY'
import json, os, sys
seed, work = sys.argv[1], sys.argv[2]
for i, card in enumerate(json.load(open(seed, encoding="utf-8"))):
    path = os.path.join(work, f"card-{i:02d}.json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(card, f, ensure_ascii=False)
    # stdout.buffer 字节输出:Windows python 文本模式会把 \n 翻译成 \r\n 污染路径
    sys.stdout.buffer.write((path + "\n").encode("utf-8"))
PY
)
[ "${#CARD_FILES[@]}" -gt 0 ] || die "种子 JSON 损坏或为空数组:未拆出任何卡($SEED_FILE)"
log "步骤 3/4 装载 ${#CARD_FILES[@]} 张卡（已存在且已发布按 title 跳过,未发布收敛续跑）"

for f in "${CARD_FILES[@]}"; do
  title="$(python3 -c 'import json,sys;sys.stdout.buffer.write(json.load(open(sys.argv[1],encoding="utf-8"))["title"].encode("utf-8"))' "$f")"

  # 幂等查重:工作台列表按 title 精确命中（ILIKE 检索 + python 逐字比对标题）,
  # 命中则解析出 cardId 与状态:已发布→跳过;DRAFT/PENDING 等未发布态→复用 cardId 收敛续跑。
  # q 值须在 python 里百分号编码:Git Bash 向原生 curl 传中文命令行参数会按本地代码页
  # 转码（GBK）,服务端收到乱码导致永远查不到 → 重复建卡;编码后仅 ASCII 字节无此问题。
  qenc="$(python3 -c 'import sys,urllib.parse;sys.stdout.buffer.write(urllib.parse.quote(sys.argv[1].encode("utf-8"),safe="").encode("ascii"))' "$title")"
  resp="$(curl -sS --max-time 10 "$BASE_URL/api/wb/cards?q=$qenc&size=50" \
    -H "Authorization: Bearer $CREATOR_TOKEN")" \
    || { FAILED=$((FAILED+1)); FAILED_TITLES+=("$title(查重请求失败)"); continue; }
  # resp 走 stdin、卡文件走 argv(ASCII 临时路径),规避中文经命令行参数的编码损耗
  dup="$(printf '%s' "$resp" | python3 -c '
import json, sys
resp = json.loads(sys.stdin.buffer.read().decode("utf-8"))
card = json.load(open(sys.argv[1], encoding="utf-8"))
items = (resp.get("data") or {}).get("items") or []
hit = next((i for i in items if i.get("title") == card.get("title")), None)
out = "" if hit is None else "%s %s" % (hit["id"], hit["status"])
sys.stdout.buffer.write(out.encode("utf-8"))' "$f")" || { FAILED=$((FAILED+1)); FAILED_TITLES+=("$title(查重解析失败)"); continue; }

  cardId=""; existStatus=""
  if [ -n "$dup" ]; then
    cardId="${dup%% *}"; existStatus="${dup##* }"
    if [ "$existStatus" = "PUBLISHED" ]; then
      log "跳过（已存在且已发布）: $title (cardId=$cardId)"
      SKIPPED=$((SKIPPED+1)); continue
    fi
    log "已存在 cardId=$cardId 但状态=$existStatus,收敛:继续送审+发布（不重复建卡）"
  fi

  if [ -z "$cardId" ]; then
    # 建卡（请求体即种子卡对象,与 CreateCardReq 字段一一对应）
    resp="$(curl -sS --max-time 15 -X POST "$BASE_URL/api/wb/cards" \
      -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
      --data-binary @"$f")" || true
    if ! printf '%s' "$resp" | grep -q '"code":0'; then
      log "建卡失败: $title → $resp"
      FAILED=$((FAILED+1)); FAILED_TITLES+=("$title(建卡)"); continue
    fi
    cardId="$(printf '%s' "$resp" | grep -o '"cardId":[0-9]*' | head -1 | cut -d: -f2)"
    [ -n "$cardId" ] || { FAILED=$((FAILED+1)); FAILED_TITLES+=("$title(缺cardId)"); continue; }
  fi

  # 送审（creator;已存在即 PENDING 的卡不再重复送审,避免 PENDING→PENDING 状态机 400）
  if [ "$existStatus" != "PENDING" ]; then
    resp="$(curl -sS --max-time 10 -X POST "$BASE_URL/api/wb/cards/$cardId/submit" \
      -H "Authorization: Bearer $CREATOR_TOKEN")" || true
    if ! printf '%s' "$resp" | grep -q '"status":"PENDING"'; then
      log "送审失败: $title(cardId=$cardId) → $resp"
      FAILED=$((FAILED+1)); FAILED_TITLES+=("$title(送审)"); continue
    fi
  fi

  # 发布（editor≠维护者,满足自审禁绝）
  resp="$(curl -sS --max-time 10 -X POST "$BASE_URL/api/wb/cards/$cardId/publish" \
    -H "Authorization: Bearer $EDITOR_TOKEN")" || true
  if ! printf '%s' "$resp" | grep -q '"status":"PUBLISHED"'; then
    log "发布失败: $title(cardId=$cardId) → $resp"
    FAILED=$((FAILED+1)); FAILED_TITLES+=("$title(发布)"); continue
  fi

  log "已发布: $title (cardId=$cardId)"
  CREATED=$((CREATED+1))
done

# ---------- 步骤 4/4:统计 ----------
log "步骤 4/4 统计"
printf '[load.sh] ===== 装载完成 =====\n'
printf '[load.sh] 知识资产: 本次导入 %d 条(种子资产入库 %s/%s)\n' "${ASSETS_IMPORTED:-0}" "${EXISTING:-0}" "${SEED_ASSET_ROWS:-?}"
printf '[load.sh] 新发布: %d 张\n' "$CREATED"
printf '[load.sh] 跳过(已存在且已发布): %d 张\n' "$SKIPPED"
printf '[load.sh] 失败: %d 张\n' "$FAILED"
if [ "$FAILED" -gt 0 ]; then
  for t in "${FAILED_TITLES[@]}"; do printf '[load.sh]   - %s\n' "$t"; done
  exit 1
fi
printf '[load.sh] 种子专题 academy(书院地标)装载成功。\n'
