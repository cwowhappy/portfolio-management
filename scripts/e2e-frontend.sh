#!/usr/bin/env bash
# e2e 前端服务：默认复用 .next 产物；E2E_FRESH_BUILD=1 强制清产物重建。
# 既有教训（mcp-hitl）：复用陈旧 .next 会用旧代码跑新断言 → 图表/渲染器类改动后必须带 E2E_FRESH_BUILD=1。
set -euo pipefail
cd "$(dirname "$0")/../frontend"

if [ "${E2E_FRESH_BUILD:-0}" = "1" ] || [ ! -f .next/BUILD_ID ]; then
  rm -rf .next
  CI=true ./node_modules/.bin/next build
fi
exec ./node_modules/.bin/next start -p 3000
