#!/usr/bin/env bash
# MCP 数据源冒烟（占位脚本）：对三个内置 provider 端点做 initialize + tools/list 握手。
# Token 从环境变量读取，不硬编码任何真实密钥；未设置则跳过对应 provider。
#   MX_DS_TOKEN   —— 东方财富妙想（HEADER: em_api_key）
#   TUSHARE_TOKEN —— Tushare 官方（BEARER）
#   WIND_TOKEN    —— Wind AIFin（BEARER，此处以「股票」域端点为代表）
set -u

pass() { printf '  OK   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; exit 1; }
skip() { printf '  SKIP %s\n' "$1"; }

# 单端点 MCP 握手：initialize 后接 tools/list。
#   $1 = 显示名；$2 = URL；$3 = 鉴权类型（HEADER|BEARER）；$4 = 头名（HEADER 时用）；$5 = token
mcp_smoke() {
  local name="$1" url="$2" auth="$3" header="$4" token="$5"
  local auth_args=()
  if [ "$auth" = "HEADER" ]; then
    auth_args=(-H "$header: $token")
  else
    auth_args=(-H "Authorization: Bearer $token")
  fi

  echo "== $name =="
  local init_resp tools_resp

  init_resp=$(curl -sS --max-time 20 \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    "${auth_args[@]}" \
    -X POST "$url" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"mcp-smoke","version":"0.0.1"}}}') \
    || fail "$name initialize 请求失败"
  printf '%s' "$init_resp" | grep -qE '"serverInfo"|"protocolVersion"|"result"' \
    && pass "$name initialize" || fail "$name initialize 响应异常: $init_resp"

  tools_resp=$(curl -sS --max-time 20 \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    "${auth_args[@]}" \
    -X POST "$url" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}') \
    || fail "$name tools/list 请求失败"
  printf '%s' "$tools_resp" | grep -qE '"tools"|"result"' \
    && pass "$name tools/list" || fail "$name tools/list 响应异常: $tools_resp"
}

echo "== MCP 数据源冒烟（initialize + tools/list）=="

if [ -z "${MX_DS_TOKEN:-}" ]; then
  skip "未设置 MX_DS_TOKEN，跳过东方财富妙想（mx-ds）"
else
  mcp_smoke "东方财富妙想 mx-ds" "https://mxapi.eastmoney.com/mxds/mcp" "HEADER" "em_api_key" "$MX_DS_TOKEN"
fi

if [ -z "${TUSHARE_TOKEN:-}" ]; then
  skip "未设置 TUSHARE_TOKEN，跳过 Tushare"
else
  mcp_smoke "Tushare" "https://api.tushare.pro/mcp/" "BEARER" "" "$TUSHARE_TOKEN"
fi

if [ -z "${WIND_TOKEN:-}" ]; then
  skip "未设置 WIND_TOKEN，跳过 Wind"
else
  mcp_smoke "Wind 股票" "https://mcp.wind.com.cn/vserver_stock_data/mcp/" "BEARER" "" "$WIND_TOKEN"
fi

echo "MCP 冒烟结束"
