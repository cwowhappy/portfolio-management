#!/usr/bin/env bash
# 端到端冒烟：健康检查 → fixture 新鲜度（腾讯上游字段漂移探测）→ 行情接口 → （有 key 时）真实对话
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

echo "全部通过"
