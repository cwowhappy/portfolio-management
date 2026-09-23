#!/usr/bin/env bash
# 端到端冒烟：健康检查 → fixture 新鲜度（上游字段漂移探测）→ 行情接口 → （有 key 时）真实对话
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

echo "== 2. fixture 新鲜度检查（东财上游字段漂移探测）=="
# 直连东财实时行情接口（URL 构造与 EastmoneyClient.quote 保持一致），
# 校验返回 JSON 仍包含 MarketDataParser.parseQuote 依赖的全部字段；
# 字段缺失说明上游接口漂移，后端解析与测试 fixture 已失真，冒烟应先红
EM_QUOTE_URL="https://push2.eastmoney.com/api/qt/stock/get?secid=1.600519&fields=f43,f44,f45,f46,f47,f48,f57,f58,f60,f86,f162,f167,f169,f170&fltt=2&invt=2"
# 东财会拒绝非浏览器 UA（Empty reply），UA/Referer 与后端 RestClientFactory 保持一致
EM_UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
RAW_QUOTE=$(curl -s --max-time 15 -H "User-Agent: $EM_UA" -H "Referer: https://quote.eastmoney.com/" "$EM_QUOTE_URL") || fail "东财行情接口不可达"
[ -n "$RAW_QUOTE" ] || fail "东财行情接口返回为空"
echo "$RAW_QUOTE" | grep -q '"data"' || fail "东财行情响应缺少 data 节点：上游接口漂移，需更新 fixture 与解析器"
MISSING_FIELDS=""
for f in f43 f44 f45 f46 f47 f48 f57 f58 f60 f86 f162 f167 f169 f170; do
  echo "$RAW_QUOTE" | grep -q "\"$f\"" || MISSING_FIELDS="$MISSING_FIELDS $f"
done
[ -z "$MISSING_FIELDS" ] && pass "fixture 字段与上游一致" \
  || fail "上游接口漂移，需更新 fixture 与解析器（缺失字段:$MISSING_FIELDS）"

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
  COOKIE_JAR=$(mktemp)
  # fail() 即 exit 1：EXIT trap 保证管理员会话 cookie 不因任何断言失败路径残留在磁盘
  trap 'rm -f "$COOKIE_JAR"' EXIT
  curl -s --max-time 15 -c "$COOKIE_JAR" -X POST "$BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | grep -q '"username"' || { rm -f "$COOKIE_JAR"; fail "管理员登录失败"; }
  RESP=$(curl -s --max-time 120 -b "$COOKIE_JAR" -X POST "$BASE/agui/run" \
    -H "Content-Type: application/json" \
    -d "{\"threadId\":\"smoke\",\"runId\":\"smoke-1\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"用一句话介绍你自己\"}],\"state\":{},\"tools\":[]}")
  echo "$RESP" | grep -q "TEXT_MESSAGE" && pass "Agent 流式回答" || fail "Agent 回答异常: $RESP"

  # MS-12：5 个新工具链路（明确指令降低模型不调工具的偶发；「只用内置工具」防 LLM 顺手
  # 调 MCP 工具触发 HITL 审批挂起（sw_daily 实测）；断言 AG-UI 工具调用事件）
  tool_smoke() {
    local run_id="$1" question="$2" label="$3"
    local resp
    resp=$(curl -s --max-time 120 -b "$COOKIE_JAR" -X POST "$BASE/agui/run" \
      -H "Content-Type: application/json" \
      -d "{\"threadId\":\"smoke-ms12\",\"runId\":\"$run_id\",\"messages\":[{\"id\":\"m-$run_id\",\"role\":\"user\",\"content\":\"$question\"}],\"state\":{},\"tools\":[]}")
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

echo "全部通过"
