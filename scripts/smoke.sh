#!/usr/bin/env bash
# 端到端冒烟：健康检查 → 行情接口 → （有 key 时）真实对话
set -u
BASE=${BACKEND_URL:-http://localhost:8080}
FE=${FRONTEND_URL:-http://localhost:3000}

pass() { printf "  OK %s
" "$1"; }
fail() { printf "  FAIL %s
" "$1"; exit 1; }

echo "== 1. 健康检查 =="
HEALTH=$(curl -s --max-time 15 $BASE/api/agent/health) || fail "后端不可达"
echo "$HEALTH" | grep -q '"market":{"ok":true' && pass "行情源连通" || fail "行情源异常"
echo "$HEALTH"

echo "== 2. 行情接口 =="
curl -s --max-time 15 "$BASE/api/market/overview" | grep -q "上证指数" && pass "大盘速览" || fail "大盘速览"
curl -s --max-time 15 "$BASE/api/market/quote/600519" | grep -q "贵州茅台" && pass "实时行情" || fail "实时行情"
curl -s --max-time 15 "$BASE/api/market/kline/600519?limit=5" | grep -q '"date"' && pass "K线" || fail "K线"
curl -s --max-time 15 "$BASE/api/market/financials/600519" | grep -q '"pe"' && pass "财务指标" || fail "财务指标"
curl -s --max-time 15 "$BASE/api/market/news/600519?limit=3" | grep -q '"title"' && pass "新闻" || fail "新闻"

echo "== 3. 前端反代 =="
curl -s --max-time 10 "$FE/api/market/overview" | grep -q "上证指数" && pass "前端行情反代" || fail "前端行情反代"

echo "== 4. AI 对话 =="
if [ -n "${DEEPSEEK_API_KEY:-}" ]; then
  RESP=$(curl -s --max-time 120 -X POST "$BASE/agui/run" \
    -H "Content-Type: application/json" \
    -d "{\"threadId\":\"smoke\",\"runId\":\"smoke-1\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"用一句话介绍你自己\"}],\"state\":{},\"tools\":[]}")
  echo "$RESP" | grep -q "TEXT_MESSAGE" && pass "Agent 流式回答" || fail "Agent 回答异常: $RESP"
else
  echo "  - 未设置 DEEPSEEK_API_KEY，跳过对话冒烟（在 .env 配置后重跑）"
fi

echo "全部通过"
