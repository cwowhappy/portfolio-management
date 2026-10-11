#!/usr/bin/env bash
# 端到端冒烟：健康检查 → fixture 新鲜度（腾讯上游字段漂移探测）→ 行情接口 → （有 key 时）真实对话
#            → 邮件 → research 复盘闭环 → 情报数据源网络探测（MS-20）→ 深度研究研报（MS-32 真 LLM 全链）
set -u
BASE=${BACKEND_URL:-http://localhost:8080}
FE=${FRONTEND_URL:-http://localhost:3000}

pass() { printf "  OK %s
" "$1"; }
fail() { printf "  FAIL %s
" "$1"; exit 1; }

echo "== 1. 健康检查 =="
HEALTH=$(curl -s --max-time 15 $BASE/api/agent/health) || fail "后端不可达"
echo "$HEALTH" | grep -q '"status"' && pass "后端存活（liveness）" || fail "后端存活"
STATUS=$(curl -s --max-time 15 $BASE/api/agent/status) || fail "状态接口不可达"
echo "$STATUS" | grep -q '"market":{"ok":true' && pass "行情源连通" || fail "行情源异常"
echo "$STATUS"

echo "== 2. fixture 新鲜度检查（腾讯上游字段漂移探测）=="
# 直连腾讯实时行情接口（URL 构造与 TencentClient.quote 保持一致），
# 校验返回仍是 ~ 分隔且关键位置可解析（名称/价格/昨收/今开/量/涨跌/涨跌幅/高低/时间戳/额/PE/PB）；
# 位置漂移或字段缺失说明上游格式变更，后端解析与测试 fixture 已失真，冒烟应先红
TX_QUOTE_URL="https://qt.gtimg.cn/q=sh600519"
TX_UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
RAW_QUOTE=$(curl -s --max-time 15 -H "User-Agent: $TX_UA" "$TX_QUOTE_URL" | iconv -f GBK -t UTF-8) || fail "腾讯行情接口不可达"
[ -n "$RAW_QUOTE" ] || fail "腾讯行情接口返回为空"
echo "$RAW_QUOTE" | grep -q 'v_sh600519="' || fail "腾讯行情响应缺少 v_sh600519 节点：上游接口漂移"
PAYLOAD=$(echo "$RAW_QUOTE" | sed 's/^[^"]*"//; s/"[^"]*$//')
IFS='~' read -r -a TXF <<< "$PAYLOAD"
[ "${#TXF[@]}" -ge 47 ] || fail "腾讯行情字段数不足(${#TXF[@]}<47)：上游接口漂移"
[ -n "${TXF[1]}" ] || fail "名称位置[1]为空：上游接口漂移"
for pos in 3 4 5 6 31 32 33 34 37; do
  [[ "${TXF[$pos]}" =~ ^-?[0-9]+([.][0-9]+)?$ ]] || fail "位置[$pos]非数值(${TXF[$pos]})：上游接口漂移"
done
[[ "${TXF[30]}" =~ ^[0-9]{14}$ ]] || fail "时间戳位置[30]非14位数字(${TXF[30]})：上游接口漂移"
# 估值位置[39/46]（PE/PB）非空断言以 600519 恒有估值为前提：改探其他标的（指数/场内基金）时
# PE/PB 可合法为空，需放宽为允许空串（PR #52 终审 deferred #7，issue #54 留档）
for pos in 39 46; do
  [[ "${TXF[$pos]}" =~ ^-?[0-9]+([.][0-9]+)?$ ]] || fail "估值位置[$pos]非数值(${TXF[$pos]})：上游接口漂移"
done
pass "fixture 字段与上游一致"

echo "== 3. 行情接口 =="
curl -s --max-time 15 "$BASE/api/market/overview" | grep -q "上证指数" && pass "大盘速览" || fail "大盘速览"
curl -s --max-time 15 "$BASE/api/market/quote/600519" | grep -q "贵州茅台" && pass "实时行情" || fail "实时行情"
curl -s --max-time 15 "$BASE/api/market/kline/600519?limit=5" | grep -q '"date"' && pass "K线" || fail "K线"
curl -s --max-time 15 "$BASE/api/market/financials/600519" | grep -q '"pe"' && pass "财务指标" || fail "财务指标"
curl -s --max-time 15 "$BASE/api/market/news/600519?limit=3" | grep -q '"title"' && pass "新闻" || fail "新闻"

echo "== 4. 前端反代 =="
curl -s --max-time 10 "$FE/api/market/overview" | grep -q "上证指数" && pass "前端行情反代" || fail "前端行情反代"

echo "== 5. AI 对话 =="
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "  - 未设置 DEEPSEEK_API_KEY，跳过对话冒烟（在 .env 配置后重跑）"
elif [ -z "${ADMIN_USERNAME:-}" ] || [ -z "${ADMIN_PASSWORD:-}" ]; then
  echo "  - 未设置 ADMIN_USERNAME/ADMIN_PASSWORD，跳过对话冒烟（/agui/run 需登录，在 .env 配置后重跑）"
else
  # 按次唯一 threadId（issue #57）：固定 threadId 会撞上残留未应答 HITL 中断，
  # 使该 thread 永久 RUN_ERROR AGUI_INTERRUPT_CONTRACT_ERROR 且重启不自愈（须删 .agentscope/state）
  RUN_TAG=$(date +%s)
  COOKIE_JAR=$(mktemp)
  # fail() 即 exit 1：EXIT trap 保证管理员会话 cookie 不因任何断言失败路径残留在磁盘
  trap 'rm -f "$COOKIE_JAR"' EXIT
  curl -s --max-time 15 -c "$COOKIE_JAR" -X POST "$BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | grep -q '"username"' || { rm -f "$COOKIE_JAR"; fail "管理员登录失败"; }
  RESP=$(curl -s --max-time 120 -b "$COOKIE_JAR" -X POST "$BASE/agui/run" \
    -H "Content-Type: application/json" \
    -d "{\"threadId\":\"smoke-$RUN_TAG\",\"runId\":\"smoke-1\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"用一句话介绍你自己\"}],\"state\":{},\"tools\":[]}")
  echo "$RESP" | grep -q "TEXT_MESSAGE" && pass "Agent 流式回答" || fail "Agent 回答异常: $RESP"

  # MS-12：5 个新工具链路（明确指令降低模型不调工具的偶发；「只用内置工具」防 LLM 顺手
  # 调 MCP 工具触发 HITL 审批挂起（sw_daily 实测）；断言 AG-UI 工具调用事件）
  tool_smoke() {
    local run_id="$1" question="$2" label="$3"
    local resp
    resp=$(curl -s --max-time 120 -b "$COOKIE_JAR" -X POST "$BASE/agui/run" \
      -H "Content-Type: application/json" \
      -d "{\"threadId\":\"$run_id-$RUN_TAG\",\"runId\":\"$run_id\",\"messages\":[{\"id\":\"m-$run_id\",\"role\":\"user\",\"content\":\"$question\"}],\"state\":{},\"tools\":[]}")
    echo "$resp" | grep -q "TOOL_CALL_START" && pass "$label 工具调用" || fail "$label 未观察到工具调用: $(echo "$resp" | head -c 200)"
    echo "$resp" | grep -q "TEXT_MESSAGE" && pass "$label 文本回答" || fail "$label 无文本回答"
  }
  tool_smoke smoke-ms12-1 "只用内置工具：请调用筛选工具 screen_stocks，筛选 ROE 大于 15% 且 PE 小于 20 的股票" "筛选"
  tool_smoke smoke-ms12-2 "只用内置工具：请调用持仓分析工具 analyze_portfolio 分析我的持仓组合" "持仓分析"
  tool_smoke smoke-ms12-3 "只用内置工具：请调用配置建议工具 suggest_allocation 给我资产配置建议" "配置建议"
  tool_smoke smoke-ms12-4 "只用内置工具：请调用财报解读工具 analyze_financials 分析 600519 的财报" "财报解读"
  tool_smoke smoke-ms12-5 "只用内置工具：请调用行业分析工具 analyze_industry 看看银行行业" "行业分析"
  rm -f "$COOKIE_JAR"
fi

echo "== 6. 邮件服务（opt-in：MAIL_SMTP_HOST/USERNAME/PASSWORD/FROM 四变量全配置才真发）=="
# 固定码假绿守卫：MAIL_TEST_FIXED_CODE 环境下 send 短路、恒报成功，不构成任何 SMTP 证据
if [ -n "${MAIL_TEST_FIXED_CODE:-}" ]; then
  echo "  - 跳过（检测到 MAIL_TEST_FIXED_CODE 固定码环境——send 短路，不构成 SMTP 证据）"
# 半配置守卫：缺任一要素时真实发信必 526/503 红色误导，跳过并指出缺哪类
elif [ -z "${MAIL_SMTP_HOST:-}" ] || [ -z "${MAIL_SMTP_USERNAME:-}" ] \
   || [ -z "${MAIL_SMTP_PASSWORD:-}" ] || [ -z "${MAIL_FROM:-}" ]; then
  MISSING=""
  [ -z "${MAIL_SMTP_HOST:-}" ] && MISSING="$MISSING MAIL_SMTP_HOST"
  [ -z "${MAIL_SMTP_USERNAME:-}" ] && MISSING="$MISSING MAIL_SMTP_USERNAME"
  [ -z "${MAIL_SMTP_PASSWORD:-}" ] && MISSING="$MISSING MAIL_SMTP_PASSWORD"
  [ -z "${MAIL_FROM:-}" ] && MISSING="$MISSING MAIL_FROM"
  echo "  - SMTP 配置不完整（缺:${MISSING}），跳过邮件冒烟（在 .env 补齐后重跑）"
else
  # M01-F06：向 MAIL_FROM 本身真实发一封注册验证码（SMTP 全链路；用户名按次唯一）。
  # 收件人必须是真实邮箱：阿里企业邮箱无 catch-all，虚构同域地址会被 RCPT TO 拒收（MAIL_SEND_FAILED）
  SMOKE_MAILBOX="$MAIL_FROM"
  CODE_RESP=$(curl -s --max-time 15 -X POST "$BASE/api/auth/register-code" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"smoke_$(date +%s)\",\"password\":\"Smoke123x\",\"email\":\"$SMOKE_MAILBOX\"}") \
    || fail "register-code 接口不可达"
  echo "$CODE_RESP" | grep -q "验证码已发送" \
    && pass "注册验证码已发送至 ${SMOKE_MAILBOX}（SMTP 全链路通）" \
    || fail "验证码发送失败: $CODE_RESP"
fi

echo "== 7. research 复盘闭环 =="
# 纯 CRUD 链路（无 LLM 依赖）：立项 → 策略定稿 → 创建复盘（快照定格含口径标注）→
# 修正作答 → 回流 wiki → 知识库检索到 SOP_REVIEW 条目（MS-27 验收：复盘结论可在 wiki 检索）。
# 「无数据」标注依赖账号当月无交易，环境相关断言留在 BDD（research-flow.feature）做确定性验证。
if [ -z "${ADMIN_USERNAME:-}" ] || [ -z "${ADMIN_PASSWORD:-}" ]; then
  echo "  - 未设置 ADMIN_USERNAME/ADMIN_PASSWORD，跳过复盘闭环冒烟（在 .env 配置后重跑）"
else
  COOKIE_JAR=$(mktemp)
  trap 'rm -f "$COOKIE_JAR"' EXIT
  curl -s --max-time 15 -c "$COOKIE_JAR" -X POST "$BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | grep -q '"username"' || { rm -f "$COOKIE_JAR"; fail "管理员登录失败"; }
  RUN_TAG=$(date +%s)
  # 立项
  PROJ=$(curl -s --max-time 15 -b "$COOKIE_JAR" -X POST "$BASE/api/research/projects" \
    -H "Content-Type: application/json" \
    -d "{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"冒烟复盘 $RUN_TAG\"}")
  PROJ_ID=$(echo "$PROJ" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  [ -n "$PROJ_ID" ] || { rm -f "$COOKIE_JAR"; fail "研究项目立项失败: $(echo "$PROJ" | head -c 200)"; }
  # 策略暂存 → 定稿
  curl -s --max-time 15 -b "$COOKIE_JAR" -X PUT "$BASE/api/research/projects/$PROJ_ID/strategy" \
    -H "Content-Type: application/json" \
    -d '{"thesis":"冒烟","valuationLow":1500,"valuationHigh":1800}' \
    | grep -q '"DRAFT"' || { rm -f "$COOKIE_JAR"; fail "策略草稿暂存失败"; }
  curl -s --max-time 15 -b "$COOKIE_JAR" -X POST "$BASE/api/research/projects/$PROJ_ID/strategy/finalize" \
    | grep -q '"FINALIZED"' || { rm -f "$COOKIE_JAR"; fail "策略定稿失败"; }
  # 创建复盘（创建即定格快照：口径标注写入快照字段）
  REVIEW=$(curl -s --max-time 15 -b "$COOKIE_JAR" -X POST "$BASE/api/research/projects/$PROJ_ID/reviews" \
    -H "Content-Type: application/json" \
    -d "{\"tier\":\"MONTHLY\",\"periodStart\":\"$(date +%Y-%m-01)\",\"periodEnd\":\"$(date +%F)\"}")
  echo "$REVIEW" | grep -q '"priceBasis":"东财收盘"' \
    || { rm -f "$COOKIE_JAR"; fail "复盘快照缺价格口径标注: $(echo "$REVIEW" | head -c 200)"; }
  echo "$REVIEW" | grep -q '"navBasis"' \
    || { rm -f "$COOKIE_JAR"; fail "复盘快照缺净值口径标注"; }
  REVIEW_ID=$(echo "$REVIEW" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  # 修正作答（叙述必填，回流 wiki 正文源）
  curl -s --max-time 15 -b "$COOKIE_JAR" -X PUT "$BASE/api/research/projects/$PROJ_ID/reviews/$REVIEW_ID" \
    -H "Content-Type: application/json" \
    -d '{"answers":{"4.1":"冒烟复盘作答"},"narrative":"冒烟复盘叙述：回流知识库验证"}' \
    | grep -q '"narrative":"冒烟复盘叙述：回流知识库验证"' \
    || { rm -f "$COOKIE_JAR"; fail "复盘修正失败"; }
  # 确认回流 → REFLOWN
  curl -s --max-time 15 -b "$COOKIE_JAR" -X POST \
    "$BASE/api/research/projects/$PROJ_ID/reviews/$REVIEW_ID/reflux" \
    | grep -q '"REFLOWN"' || { rm -f "$COOKIE_JAR"; fail "复盘回流失败"; }
  # 知识库检索：RESEARCH_NOTE 列表含 SOP_REVIEW 条目（标题「复盘·…」）
  WIKI=$(curl -s --max-time 15 -b "$COOKIE_JAR" "$BASE/api/wiki/entries?type=RESEARCH_NOTE")
  echo "$WIKI" | grep -q '"SOP_REVIEW"' && echo "$WIKI" | grep -q "复盘·冒烟复盘 $RUN_TAG" \
    && pass "复盘回流知识库（SOP_REVIEW 条目可检索）" \
    || { rm -f "$COOKIE_JAR"; fail "知识库未检索到复盘条目: $(echo "$WIKI" | head -c 200)"; }
  rm -f "$COOKIE_JAR"
fi

echo "== 8. 情报数据源网络探测（MS-20，决策 #17）=="
# 出站可达性探测：新闻双源（东财 7×24 主源 / 新浪 zhibo 降级源）+ 公告主源（巨潮，P2 消费）。
# 2xx/3xx/4xx 皆算可达——4xx 也证明网络出口与 DNS 正常（鉴权/参数错误属上游语义非网络问题）；
# 仅 000/超时（网络不通、DNS 解析失败）判 FAIL——部署机采集任务静默失败的先兆排查入口。
probe_intel_source() {
  local name="$1" url="$2" code
  code=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$url") || code="000"
  if [ "$code" = "000" ]; then
    fail "$name 不可达（HTTP $code / 超时）——检查部署机出站网络与 DNS"
  else
    pass "$name 可达（HTTP ${code}）"
  fi
}
probe_intel_source "东财 7×24 快讯（新闻主源）" "https://np-listapi.eastmoney.com/comm/web/getFastNewsList?client=web&biz=web_724&fastColumn=102&pageSize=1&req_trace=1"
probe_intel_source "新浪 zhibo 快讯（新闻降级源）" "https://zhibo.sina.com.cn/api/zhibo/feed?page=1&page_size=1"
probe_intel_source "巨潮公告（公告主源，P2 消费）" "https://www.cninfo.com.cn/new/hisAnnouncement/query"

echo "== 9. 深度研究研报（MS-32，真 LLM 全链）=="
# 真 LLM 端到端：立项（幂等复用固定「冒烟研报」项目）→ 节首 psql 清残留（防上次活跃任务撞
# uq_report_task_active 部分唯一索引 409 卡死重复跑）→ 发起 202 → 轮询至终态（15s 间隔，
# 上限 18m = execution total-timeout 15m + 3m 缓冲；RUNNING 打 stage 进度行）→
# SUCCEEDED 断言研报 PENDING_CONFIRM / contentMd 含「关键假设」/ 假设条条有锚点 →
# confirm 定稿 → journal「研报定稿」事件 psql 复核。
# FAILED 终态如实红：输出 errorCode/errorMessage + 任务表行供归因——真 LLM 可能因行情源/
# 模型抖动失败，不做重试宽容（假绿比红更危险）。
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "  - 未设置 DEEPSEEK_API_KEY，跳过研报冒烟（研报生成依赖后端 LLM，在 .env 配置后重跑）"
elif [ -z "${ADMIN_USERNAME:-}" ] || [ -z "${ADMIN_PASSWORD:-}" ]; then
  echo "  - 未设置 ADMIN_USERNAME/ADMIN_PASSWORD，跳过研报冒烟（在 .env 配置后重跑）"
else
  COOKIE_JAR=$(mktemp)
  trap 'rm -f "$COOKIE_JAR"' EXIT
  curl -s --max-time 15 -c "$COOKIE_JAR" -X POST "$BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | grep -q '"username"' || { rm -f "$COOKIE_JAR"; fail "管理员登录失败"; }

  # 幂等前置：固定标题「冒烟研报」项目存在则复用，否则立项（600519 数据面全，沿 §7 口径）
  LIST=$(curl -s --max-time 15 -b "$COOKIE_JAR" "$BASE/api/research/projects") \
    || { rm -f "$COOKIE_JAR"; fail "研究项目列表不可达"; }
  PROJ_JSON=$(echo "$LIST" | grep -o '{[^{}]*"title":"冒烟研报"[^{}]*}' | head -1)
  if [ -n "$PROJ_JSON" ]; then
    PROJ_ID=$(echo "$PROJ_JSON" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  else
    PROJ=$(curl -s --max-time 15 -b "$COOKIE_JAR" -X POST "$BASE/api/research/projects" \
      -H "Content-Type: application/json" \
      -d '{"stockCode":"600519","stockName":"贵州茅台","title":"冒烟研报"}')
    PROJ_ID=$(echo "$PROJ" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
    [ -n "$PROJ_ID" ] || { rm -f "$COOKIE_JAR"; fail "研报冒烟项目立项失败: $(echo "$PROJ" | head -c 200)"; }
  fi

  # 节首清理该项目历史任务/研报行（DELETE by project；连接口径沿 scripts/e2e-cleanup.sh）：
  # FK 依赖序 assumption（引 anchor）→ anchor → report → task
  export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" \
    PGUSER="${POSTGRES_USER:-invest}" PGDATABASE="${POSTGRES_DB:-invest}" \
    PGPASSWORD="${POSTGRES_PASSWORD:-invest}"
  psql -v ON_ERROR_STOP=1 -q -c "
    DELETE FROM research_report_assumption WHERE report_id IN (SELECT id FROM research_report WHERE project_id = ${PROJ_ID});
    DELETE FROM research_report_anchor    WHERE report_id IN (SELECT id FROM research_report WHERE project_id = ${PROJ_ID});
    DELETE FROM research_report           WHERE project_id = ${PROJ_ID};
    DELETE FROM research_report_task      WHERE project_id = ${PROJ_ID};" \
    || { rm -f "$COOKIE_JAR"; fail "psql 研报残留清理失败（研报节幂等前置，检查本机 psql 与 DB 连接）"; }

  # 发起（F01）：202 异步受理，body {taskId, quotaRemaining}
  START_EPOCH=$(date +%s)
  START_RESP=$(curl -s --max-time 15 -w '\n%{http_code}' -b "$COOKIE_JAR" \
    -X POST "$BASE/api/research/projects/$PROJ_ID/report-tasks") \
    || { rm -f "$COOKIE_JAR"; fail "研报任务发起请求失败"; }
  START_CODE=$(echo "$START_RESP" | tail -n 1)
  START_BODY=$(echo "$START_RESP" | sed '$d')
  [ "$START_CODE" = "202" ] \
    || { rm -f "$COOKIE_JAR"; fail "研报任务发起失败（HTTP ${START_CODE}，预期 202）: $(echo "$START_BODY" | head -c 200)"; }
  TASK_ID=$(echo "$START_BODY" | grep -o '"taskId":[0-9]*' | head -1 | cut -d: -f2)
  QUOTA_LEFT=$(echo "$START_BODY" | grep -o '"quotaRemaining":[0-9]*' | head -1 | cut -d: -f2)
  [ -n "$TASK_ID" ] || { rm -f "$COOKIE_JAR"; fail "发起响应缺 taskId: $(echo "$START_BODY" | head -c 200)"; }
  echo "  任务受理 taskId=${TASK_ID}（当日配额余量 ${QUOTA_LEFT:-?} tokens）——真 LLM 全链生成中"

  # 轮询至终态：15s 间隔；超时上限 18m（1080s）= total-timeout(15m) + 3m 缓冲
  POLL_DEADLINE=$(( START_EPOCH + 1080 ))
  TASK_JSON=""
  while :; do
    TASK_JSON=$(curl -s --max-time 15 -b "$COOKIE_JAR" \
      "$BASE/api/research/projects/$PROJ_ID/report-tasks/latest") \
      || { rm -f "$COOKIE_JAR"; fail "研报任务轮询请求失败"; }
    T_STATUS=$(echo "$TASK_JSON" | grep -o '"status":"[A-Z]*"' | head -1 | cut -d'"' -f4)
    T_STAGE=$(echo "$TASK_JSON" | grep -o '"stage":"[A-Z]*"' | head -1 | cut -d'"' -f4)
    T_TOKENS=$(echo "$TASK_JSON" | grep -o '"tokenUsage":[0-9]*' | head -1 | cut -d: -f2)
    printf '    %4ss  status=%s stage=%s tokens=%s\n' \
      "$(( $(date +%s) - START_EPOCH ))" "${T_STATUS:-?}" "${T_STAGE:-?}" "${T_TOKENS:-?}"
    case "$T_STATUS" in
      SUCCEEDED|FAILED) break ;;
    esac
    if [ "$(date +%s)" -ge "$POLL_DEADLINE" ]; then
      echo "  任务表诊断（research_report_task id=${TASK_ID}）："
      psql -v ON_ERROR_STOP=1 -c "SELECT id, status, stage, token_usage, error_code, error_message, created_at, started_at, finished_at FROM research_report_task WHERE id = ${TASK_ID};" \
        || echo "  （任务表诊断查询失败）"
      rm -f "$COOKIE_JAR"
      fail "研报任务超时未达终态（status=${T_STATUS:-?}，上限 18m = total-timeout 15m + 3m 缓冲）——须归因"
    fi
    sleep 15
  done

  # FAILED 终态：如实红 + 任务表诊断行（归因入口）
  if [ "$T_STATUS" = "FAILED" ]; then
    echo "  任务表诊断（research_report_task id=${TASK_ID}）："
    psql -v ON_ERROR_STOP=1 -c "SELECT id, status, stage, token_usage, error_code, error_message, created_at, started_at, finished_at FROM research_report_task WHERE id = ${TASK_ID};" \
      || echo "  （任务表诊断查询失败）"
    rm -f "$COOKIE_JAR"
    fail "研报任务 FAILED——真 LLM 链路失败须归因（不重试宽容）: $(echo "$TASK_JSON" | head -c 400)"
  fi

  # SUCCEEDED 终态：断言 reportId 回连 → 详情（PENDING_CONFIRM / contentMd 含关键假设 / 假设有锚点）
  ELAPSED=$(( $(date +%s) - START_EPOCH ))
  REPORT_ID=$(echo "$TASK_JSON" | grep -o '"reportId":[0-9]*' | head -1 | cut -d: -f2)
  [ -n "$REPORT_ID" ] || { rm -f "$COOKIE_JAR"; fail "任务 SUCCEEDED 但响应缺 reportId: $(echo "$TASK_JSON" | head -c 300)"; }
  pass "研报生成 SUCCEEDED（耗时 ${ELAPSED}s，tokens=${T_TOKENS:-0}，reportId=${REPORT_ID}）"

  DETAIL=$(curl -s --max-time 30 -b "$COOKIE_JAR" "$BASE/api/research/reports/$REPORT_ID") \
    || { rm -f "$COOKIE_JAR"; fail "研报详情请求失败"; }
  echo "$DETAIL" | grep -q '"status":"PENDING_CONFIRM"' \
    || { rm -f "$COOKIE_JAR"; fail "研报初态非 PENDING_CONFIRM: $(echo "$DETAIL" | head -c 200)"; }
  case "$DETAIL" in
    *'"contentMd":""'*|*'"contentMd":null'*)
      { rm -f "$COOKIE_JAR"; fail "研报 contentMd 为空"; } ;;
  esac
  echo "$DETAIL" | grep -q '关键假设' \
    || { rm -f "$COOKIE_JAR"; fail "研报 contentMd 缺「关键假设」章节"; }
  echo "$DETAIL" | grep -qF '"assumptions":[{' \
    || { rm -f "$COOKIE_JAR"; fail "研报关键假设为空（assumptions=[]）"; }
  if echo "$DETAIL" | grep -q '"anchor":null'; then
    { rm -f "$COOKIE_JAR"; fail "存在未绑定锚点的假设条目（anchor=null）"; }
  fi
  R_TITLE=$(echo "$DETAIL" | grep -o '"title":"[^"]*"' | head -1 | cut -d'"' -f4)
  ASSUMPTION_N=$(echo "$DETAIL" | grep -o '"seq":[0-9]*' | wc -l | tr -d ' ')
  pass "研报内容契约成立（「${R_TITLE}」，关键假设 ${ASSUMPTION_N} 条且条条有锚点）"

  # 确认定稿（§3.10）：PENDING_CONFIRM→CONFIRMED
  CONFIRM_RESP=$(curl -s --max-time 15 -w '\n%{http_code}' -b "$COOKIE_JAR" \
    -X POST "$BASE/api/research/reports/$REPORT_ID/confirm" \
    -H "Content-Type: application/json" -d '{"action":"CONFIRM"}') \
    || { rm -f "$COOKIE_JAR"; fail "研报确认请求失败"; }
  CONFIRM_CODE=$(echo "$CONFIRM_RESP" | tail -n 1)
  CONFIRM_BODY=$(echo "$CONFIRM_RESP" | sed '$d')
  if [ "$CONFIRM_CODE" = "200" ] && echo "$CONFIRM_BODY" | grep -q '"status":"CONFIRMED"'; then
    pass "研报确认定稿（PENDING_CONFIRM→CONFIRMED）"
  else
    { rm -f "$COOKIE_JAR"; fail "研报确认失败（HTTP ${CONFIRM_CODE}，预期 200）: $(echo "$CONFIRM_BODY" | head -c 200)"; }
  fi

  # journal「研报定稿」事件 psql 复核（同事务写入，confirm 后必在）
  JOURNAL_N=$(psql -t -A -v ON_ERROR_STOP=1 -c \
    "SELECT count(*) FROM journal_entry WHERE project_id = ${PROJ_ID} AND type = 'RESEARCH_EVENT' AND title = '研报定稿'") \
    || { rm -f "$COOKIE_JAR"; fail "psql journal 复核查询失败"; }
  if [ "${JOURNAL_N:-0}" -ge 1 ]; then
    pass "journal「研报定稿」事件已落库（project_id=${PROJ_ID}，${JOURNAL_N} 行）"
  else
    { rm -f "$COOKIE_JAR"; fail "journal 未找到「研报定稿」事件行（project_id=${PROJ_ID}）"; }
  fi
  rm -f "$COOKIE_JAR"
fi

echo "全部通过"
