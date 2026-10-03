#!/usr/bin/env bash
# 种子内容装载脚本（01 §6 最小启动量工具 + 样例,Task 37;多数据集与入口装载扩展）
#
# 职责：注册/登录两个运营账号（卡维护者 + 编辑）→ psql 提权（CREATOR/EDITOR）→
#       逐数据集（DATASETS 注册表）：
#       ① 编辑账号导入知识资产 <assets.csv>（幂等:种子 title 全部已存在则跳过导入,
#          导入端点对重复 title 不去重,故由本脚本预检收敛）；
#       ② 逐张装载 <cards.json>（幂等:按 title 查重,已存在且已发布则跳过;
#          已存在但 DRAFT/PENDING 则复用 cardId 继续送审+发布,重跑收敛到全 PUBLISHED）；
#          建卡前按「来源 title → 资产 id」给 sources[].assetId 挂接资产（匹配不到仅告警）；
#       ③ 若注册了 <entries.json>：装载卡片入口（幂等:按 mine 中 cardId+name 查重）——
#          LINK_CARD 同主题跳转按 target 卡题解析 targetCardId；AGENT_SERVICE/COMPARE
#          服务入口以宿主卡挂接资产集作 assetScope（缺挂接即规划失败）；scope=PUBLIC
#          走前置审核：建入口(PENDING) → 编辑账号在审核队列逐条 approve → ACTIVE；
#          重跑收敛：已存在入口跳过，遗留 PENDING 的种子入口自动补审。
#       尾部打印统计，任何卡片/入口失败则退出码 1。
#
# 依赖：bash + curl + python3（拆种子数组/解 JWT role/分页拉取与入口规划,均为各平台
#       基线工具,零额外安装）；断言用 curl + grep 宽松断言（envelope code=0 / 字段逐字）。
#       psql 仅用于提权：本机无 psql 时自动回退 `wsl -e psql`（本仓库开发环境约定），
#       再无则打印 SQL 退出，由执行者手工执行后重跑（幂等，重跑自动续传）。
#       分页拉全量（assets/cards/reviews 列表）用 python3 urllib（URL/令牌均 ASCII，
#       不经 shell 传中文；响应按 UTF-8 解码，规避 Git Bash 本地代码页转码问题）。
#
# 环境变量（均可覆盖，默认值对齐 README「本地环境」）：
#   KE_BASE_URL          后端基址            默认 http://localhost:8080
#   SEED_CREATOR_PHONE / SEED_CREATOR_PASSWORD / SEED_CREATOR_NICKNAME  卡维护者账号
#   SEED_EDITOR_PHONE  / SEED_EDITOR_PASSWORD  / SEED_EDITOR_NICKNAME    审核发布账号
#   ADMIN_DB_URL         psql 连接串（提权用）默认 postgresql://ke:ke@localhost:5432/ke
# 注：KE_LLM_API_KEY 与本脚本无关（装载路径不触发智能体）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 数据集注册表：卡片JSON | 资产CSV | 入口JSON(空=无入口) | 完成标语
DATASETS=(
  "cards-yuelu.json|assets-book.csv||academy(书院地标)"
  "cards-xiangcai.json|assets-xiangcai.csv|entries-xiangcai.json|cuisine(湘菜风物)"
)

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
E_CREATED=0; E_SKIPPED=0; E_APPROVED=0; E_FAILED=0; E_FAILED_NAMES=()
DATASET_SUMMARY=()

log()  { printf '[load.sh] %s\n' "$*"; }
# 诊断输出到 stderr:ensure_role 在 $( ) 命令替换内执行,stdout 会被并入返回的
# token——日志若走 stdout 会污染 Bearer 头(Tomcat 以 400 拒绝,报 header 不合规)
elog() { printf '[load.sh] %s\n' "$*" >&2; }
die()  { printf '[load.sh] ERROR: %s\n' "$*" >&2; exit 2; }

need() { command -v "$1" >/dev/null 2>&1 || die "缺少依赖命令: $1"; }
need curl; need python3

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

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

# fetch_pages <bearer-token> <path-with-query(不含page/size)> <outfile>
# 分页拉全量并合并为 {items,total}（assets/cards/reviews 三个 items/total/page/size
# 信封端点通用；URL 与令牌均为 ASCII,中文只在响应体里按 UTF-8 解码）
fetch_pages() {
  local token="$1" path="$2" out="$3"
  python3 - "$token" "$BASE_URL$path" "$out" <<'PY' || die "分页拉取失败: $path"
import json, sys, urllib.request
token, base, out = sys.argv[1], sys.argv[2], sys.argv[3]
items, page = [], 1
while True:
    url = base + ("&" if "?" in base else "?") + "page=%d&size=100" % page
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    body = json.loads(urllib.request.urlopen(req, timeout=20).read().decode("utf-8"))
    data = body.get("data") or {}
    batch = data.get("items") or []
    items.extend(batch)
    total = int(data.get("total") or 0)
    if not batch or len(items) >= total or page >= 50:
        break
    page += 1
with open(out, "w", encoding="utf-8", newline="\n") as f:
    json.dump({"items": items, "total": len(items)}, f, ensure_ascii=False)
PY
}

# ---------- 数据集步骤①：导入知识资产（FR-O01,仅 EDITOR/OPERATOR,用编辑账号） ----------

# 统计 <csv> 中已入库的资产条数（导入端点对重复 title 不去重,重复导入会翻倍,故先预检收敛）
count_seed_assets() {
  local csv="$1"
  curl -sS --max-time 10 "$BASE_URL/api/wb/assets?size=100" \
    -H "Authorization: Bearer $EDITOR_TOKEN" | python3 -c '
import csv, json, sys
resp = json.loads(sys.stdin.buffer.read().decode("utf-8"))
rows = list(csv.reader(open(sys.argv[1], encoding="utf-8")))[1:]
seed_titles = [r[1] for r in rows if r]
items = (resp.get("data") or {}).get("items") or []
titles = {a.get("title") for a in items}
sys.stdout.buffer.write(str(sum(1 for t in seed_titles if t in titles)).encode())' "$csv" || printf '0'
}

# import_assets <csv> → 全局 DS_ASSET_ROWS/DS_EXISTING/DS_ASSET_IMPORTED
import_assets() {
  local csv="$1" resp existing csv_curl
  [ -f "$csv" ] || die "资产 CSV 不存在: $csv"
  # 路径适配:Git Bash 下原生 curl 的 -F file=@ 需 Windows 形式路径;WSL/原生 psql 环境原样
  csv_curl="$(cygpath -m "$csv" 2>/dev/null || printf '%s' "$csv")"
  DS_ASSET_ROWS="$(python3 -c 'import csv,sys;sys.stdout.buffer.write(str(sum(1 for r in csv.reader(open(sys.argv[1],encoding="utf-8")) if r)).encode())' "$csv")"
  DS_ASSET_ROWS=$((DS_ASSET_ROWS - 1)) # 去表头行
  DS_EXISTING="$(count_seed_assets "$csv")"
  DS_ASSET_IMPORTED=0
  if [ "$DS_EXISTING" -ge "$DS_ASSET_ROWS" ]; then
    log "资产已存在（$DS_EXISTING/$DS_ASSET_ROWS 条种子资产已入库),跳过导入"
  else
    [ "$DS_EXISTING" -gt 0 ] && elog "注意:已有 $DS_EXISTING/$DS_ASSET_ROWS 条（部分导入状态),本次导入可能与存量重复,建议清库后重跑"
    resp="$(curl -sS --max-time 20 -X POST "$BASE_URL/api/wb/assets/import" \
      -H "Authorization: Bearer $EDITOR_TOKEN" \
      -F "file=@$csv_curl;type=text/csv")" || true
    if ! printf '%s' "$resp" | grep -q '"code":0'; then
      die "资产导入失败: $resp"
    fi
    DS_ASSET_IMPORTED="$(printf '%s' "$resp" | grep -o '"imported":[0-9]*' | head -1 | cut -d: -f2)"
    DS_ASSET_IMPORTED="${DS_ASSET_IMPORTED:-0}"
    DS_EXISTING="$(count_seed_assets "$csv")" # 导入后复核实际入库条数
    log "资产导入完成:本次导入 $DS_ASSET_IMPORTED 条,种子资产入库 $DS_EXISTING/$DS_ASSET_ROWS 条"
  fi
}

# ---------- 数据集步骤②：装载卡片 ----------

# build_asset_map → $WORK/asset-map.json（title → id,服务入口 assetScope 与来源挂接共用）
build_asset_map() {
  fetch_pages "$EDITOR_TOKEN" "/api/wb/assets" "$WORK/asset-raw.json"
  python3 -c '
import json, sys
items = json.load(open(sys.argv[1], encoding="utf-8"))["items"]
m = {i["title"]: i["id"] for i in items if i.get("title") and i.get("id")}
json.dump(m, open(sys.argv[2], "w", encoding="utf-8", newline="\n"), ensure_ascii=False)' \
    "$WORK/asset-raw.json" "$WORK/asset-map.json"
}

# inject_payload <card-file> <asset-map> <out-file>：按来源 title 匹配资产补 assetId
# （匹配不到仅告警——卡片写路径不强制挂接；服务入口的 assetScope 校验由后端硬拦）
inject_payload() {
  python3 - "$1" "$2" "$3" <<'PY'
import json, sys
card = json.load(open(sys.argv[1], encoding="utf-8"))
assets = json.load(open(sys.argv[2], encoding="utf-8"))
miss = []
for s in card.get("sources") or []:
    aid = assets.get(s.get("title"))
    if aid is None:
        miss.append(s.get("title"))
    else:
        s["assetId"] = aid
if miss:
    sys.stderr.write("[load.sh] 提示:来源未匹配到资产(不影响建卡): %s\n" % ", ".join(miss))
with open(sys.argv[3], "w", encoding="utf-8", newline="\n") as f:
    json.dump(card, f, ensure_ascii=False)
PY
}

# load_cards <cards.json>
load_cards() {
  local seed="$1" prefix="$WORK/ds${DS_IDX}-card-"
  [ -f "$seed" ] || die "种子文件不存在: $seed"
  mapfile -t CARD_FILES < <(python3 - "$seed" "$prefix" <<'PY'
import json, os, sys
seed, prefix = sys.argv[1], sys.argv[2]
for i, card in enumerate(json.load(open(seed, encoding="utf-8"))):
    path = "%s%02d.json" % (prefix, i)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(card, f, ensure_ascii=False)
    # stdout.buffer 字节输出:Windows python 文本模式会把 \n 翻译成 \r\n 污染路径
    sys.stdout.buffer.write((path + "\n").encode("utf-8"))
PY
  )
  [ "${#CARD_FILES[@]}" -gt 0 ] || die "种子 JSON 损坏或为空数组:未拆出任何卡($seed)"
  log "装载 ${#CARD_FILES[@]} 张卡（已存在且已发布按 title 跳过,未发布收敛续跑）"

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
      # 建卡（请求体即种子卡对象,与 CreateCardReq 字段一一对应;
      # 先按资产映射补 sources[].assetId,再以文件字节直传）
      inject_payload "$f" "$WORK/asset-map.json" "$f.payload.json"
      resp="$(curl -sS --max-time 15 -X POST "$BASE_URL/api/wb/cards" \
        -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
        --data-binary @"$f.payload.json")" || true
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
}

# ---------- 数据集步骤③：装载入口（LINK_CARD 链 + 服务入口,PUBLIC 过审） ----------

# load_entries <entries.json> <cards.json>
load_entries() {
  local entries_f="$1" cards_f="$2" prefix="$WORK/ds${DS_IDX}-entry-" resp entryId
  [ -f "$entries_f" ] || die "入口种子文件不存在: $entries_f"

  # 卡题 → cardId（仅 PUBLISHED；入口宿主与目标都必须已发布）
  fetch_pages "$CREATOR_TOKEN" "/api/wb/cards" "$WORK/wbcards.json"
  # 作者（creator）名下已有入口 → 幂等查重键 cardId|name + 收敛补审 PENDING
  curl -sS --max-time 10 "$BASE_URL/api/entries/mine" \
    -H "Authorization: Bearer $CREATOR_TOKEN" > "$WORK/mine.json" \
    || die "拉取我的入口失败: $BASE_URL/api/entries/mine"

  # 规划：解析引用 → 生成 POST 载荷与计划表（CREATE/FAILED 行）；未命中引用只报失败不建
  python3 - "$entries_f" "$cards_f" "$WORK/wbcards.json" "$WORK/asset-map.json" \
    "$WORK/mine.json" "$prefix" <<'PY'
import json, sys
entries_f, cards_f, wbcards_f, assetmap_f, mine_f, prefix = sys.argv[1:7]
spec = json.load(open(entries_f, encoding="utf-8"))
cards = json.load(open(cards_f, encoding="utf-8"))
card_ids = {}
for it in json.load(open(wbcards_f, encoding="utf-8"))["items"]:
    if it.get("status") == "PUBLISHED" and it.get("title"):
        card_ids[it["title"]] = it["id"]
# 宿主卡的来源标题序列（assetScope 按此顺序解析资产 id,去重保序）
src_titles = {c["title"]: [s["title"] for s in (c.get("sources") or [])] for c in cards}
assets = json.load(open(assetmap_f, encoding="utf-8"))
mine = (json.load(open(mine_f, encoding="utf-8")).get("data")) or []
mine_by_key = {"%s|%s" % (e.get("cardId"), e.get("name")): e for e in mine}

plan = open(prefix + "plan.tsv", "w", encoding="utf-8", newline="\n")
pending = open(prefix + "pending-ids.txt", "w", encoding="utf-8", newline="\n")
created = skipped = failed = 0
for i, ent in enumerate(spec.get("entries") or []):
    host, name, typ = ent.get("card"), ent.get("name"), ent.get("type")
    host_id = card_ids.get(host)
    reason = "宿主卡未发布或不存在" if host_id is None else None
    if reason is None:
        exist = mine_by_key.get("%s|%s" % (host_id, name))
        if exist is not None:
            skipped += 1
            if exist.get("status") == "PENDING":  # 上次跑到建完没过审 → 本次补审
                pending.write("%d\n" % exist.get("id"))
            continue
    config = {"name": name, "type": typ}
    if typ == "LINK_CARD":
        # 同主题跳转不带 relationLabel/why/source（EntryConfigValidator 硬约束,跨主题种子不适用）
        target_id = card_ids.get(ent.get("target"))
        if target_id is None:
            reason = "目标卡未发布或不存在: %s" % ent.get("target")
        else:
            config["targetCardId"] = target_id
    else:
        config["serviceType"] = ent.get("serviceType")
        config["goal"] = ent.get("goal")
        if ent.get("outputSpec"):
            config["outputSpec"] = ent.get("outputSpec")
        # assetScope = 宿主卡已挂接资产全集（同 EntryAuthorizedAssets 口径,缺失即规划失败）
        scope_ids = []
        for t in src_titles.get(host) or []:
            aid = assets.get(t)
            if aid is not None and aid not in scope_ids:
                scope_ids.append(aid)
        if not scope_ids:
            reason = "宿主卡无已挂接资产,无法满足服务入口的资料范围(assetScope)"
        else:
            config["assetScope"] = scope_ids
    if reason is not None:
        failed += 1
        plan.write("FAILED\t%s\t%s\t%s\n" % (host, name, reason))
        continue
    payload_path = "%spayload-%03d.json" % (prefix, i)
    with open(payload_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"cardId": host_id, "config": config, "scope": "PUBLIC"}, f, ensure_ascii=False)
    plan.write("CREATE\t%s\t%s\t%s\n" % (host, name, payload_path))
    created += 1
plan.close(); pending.close()
with open(prefix + "skip-count", "w", encoding="utf-8", newline="\n") as f:
    f.write("%d\n" % skipped)
sys.stderr.write("[load.sh] 入口规划:待建 %d / 跳过 %d / 失败 %d\n" % (created, skipped, failed))
PY

  : > "${prefix}created-ids"
  while IFS=$'\t' read -r act host name arg4; do
    case "$act" in
      CREATE)
        # 载荷含中文,不走命令行参数:文件字节经 --data-binary @file 直传(与建卡同一纪律)
        resp="$(curl -sS --max-time 15 -X POST -H 'Content-Type: application/json' \
          -H "Authorization: Bearer $CREATOR_TOKEN" --data-binary @"$arg4" "$BASE_URL/api/entries")" || true
        if printf '%s' "$resp" | grep -q '"code":0'; then
          entryId="$(printf '%s' "$resp" | grep -o '"entryId":[0-9]*' | head -1 | cut -d: -f2)"
          if [ -n "$entryId" ]; then
            printf '%s\n' "$entryId" >> "${prefix}created-ids"
            log "已建入口(PENDING待审): $host · $name (entryId=$entryId)"
            E_CREATED=$((E_CREATED+1))
          else
            E_FAILED=$((E_FAILED+1)); E_FAILED_NAMES+=("$host · $name(缺entryId)")
          fi
        else
          log "建入口失败: $host · $name → $resp"
          E_FAILED=$((E_FAILED+1)); E_FAILED_NAMES+=("$host · $name(创建)")
        fi
        ;;
      FAILED)
        elog "入口规划失败: $host · $name → $arg4"
        E_FAILED=$((E_FAILED+1)); E_FAILED_NAMES+=("$host · $name($arg4)")
        ;;
    esac
  done < "${prefix}plan.tsv"
  E_SKIPPED=$((E_SKIPPED + $(cat "${prefix}skip-count" 2>/dev/null || printf '0')))

  # 过审：PUBLIC 入口建后为 PENDING,编辑账号在审核队列逐条 approve → ACTIVE
  if [ -s "${prefix}created-ids" ] || [ -s "${prefix}pending-ids.txt" ]; then
    fetch_pages "$EDITOR_TOKEN" "/api/wb/reviews?status=PENDING&objectType=ENTRY" "$WORK/reviews.json"
    python3 - "$WORK/reviews.json" "${prefix}created-ids" "${prefix}pending-ids.txt" "${prefix}review-ids" <<'PY'
import json, sys
items = json.load(open(sys.argv[1], encoding="utf-8"))["items"]
want = set()
for p in sys.argv[2:4]:
    for line in open(p, encoding="utf-8"):
        line = line.strip()
        if line:
            want.add(int(line))
ids = [r["id"] for r in items
       if r.get("objectType") == "ENTRY" and r.get("objectId") in want and r.get("status") == "PENDING"]
with open(sys.argv[4], "w", encoding="utf-8", newline="\n") as f:
    f.write("".join("%d\n" % i for i in ids))
PY
    while read -r rid; do
      [ -n "$rid" ] || continue
      resp="$(post_json "$BASE_URL/api/wb/reviews/$rid/approve" '{"notes":"种子数据入口过审"}' "$EDITOR_TOKEN")" || true
      if printf '%s' "$resp" | grep -q '"code":0'; then
        E_APPROVED=$((E_APPROVED+1))
      else
        log "入口过审失败: reviewTaskId=$rid → $resp"
        E_FAILED=$((E_FAILED+1)); E_FAILED_NAMES+=("审核任务#$rid(过审)")
      fi
    done < "${prefix}review-ids"
    log "入口过审完成: $E_APPROVED 条"
  else
    log "入口无待建且无遗留待审,全部跳过"
  fi
}

# ---------- 主流程 ----------

log "步骤 1/3 准备账号（creator=$CREATOR_PHONE / editor=$EDITOR_PHONE）"
CREATOR_TOKEN="$(ensure_role "$CREATOR_PHONE" "$CREATOR_PASSWORD" "$CREATOR_NICKNAME" CREATOR)"
EDITOR_TOKEN="$(ensure_role "$EDITOR_PHONE" "$EDITOR_PASSWORD" "$EDITOR_NICKNAME" EDITOR)"

log "步骤 2/3 装载数据集（${#DATASETS[@]} 个）"
DS_IDX=0
for DS in "${DATASETS[@]}"; do
  IFS='|' read -r CARDS_JSON ASSETS_CSV ENTRIES_JSON DS_LABEL <<<"$DS"
  DS_IDX=$((DS_IDX+1))
  log "── 数据集 $DS_IDX/${#DATASETS[@]} $DS_LABEL ──"
  import_assets "$ROOT/seed/$ASSETS_CSV"
  DATASET_SUMMARY+=("$DS_LABEL:资产 $DS_EXISTING/$DS_ASSET_ROWS 入库,本次导入 $DS_ASSET_IMPORTED")
  build_asset_map
  load_cards "$ROOT/seed/$CARDS_JSON"
  if [ -n "$ENTRIES_JSON" ]; then
    load_entries "$ROOT/seed/$ENTRIES_JSON" "$ROOT/seed/$CARDS_JSON"
  else
    log "该数据集无入口种子,跳过入口装载"
  fi
done

log "步骤 3/3 统计"
printf '[load.sh] ===== 装载完成 =====\n'
for s in "${DATASET_SUMMARY[@]}"; do printf '[load.sh] %s\n' "$s"; done
printf '[load.sh] 卡片: 新发布 %d 张 / 跳过 %d 张 / 失败 %d 张\n' "$CREATED" "$SKIPPED" "$FAILED"
printf '[load.sh] 入口: 新建 %d 条 / 过审 %d 条 / 失败 %d 条\n' "$E_CREATED" "$E_APPROVED" "$E_FAILED"
if [ "$FAILED" -gt 0 ]; then
  for t in "${FAILED_TITLES[@]}"; do printf '[load.sh]   卡片失败 - %s\n' "$t"; done
fi
if [ "$E_FAILED" -gt 0 ]; then
  for t in "${E_FAILED_NAMES[@]}"; do printf '[load.sh]   入口失败 - %s\n' "$t"; done
fi
if [ "$FAILED" -gt 0 ] || [ "$E_FAILED" -gt 0 ]; then
  exit 1
fi
printf '[load.sh] 种子数据集装载成功:%s。\n' "$(printf '%s、' "${DATASET_SUMMARY[@]%%:*}" | sed 's/、$//')"
