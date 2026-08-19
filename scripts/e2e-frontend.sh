#!/usr/bin/env bash
# Playwright e2e 前端启动：next start（缺构建产物时先 build）。由 playwright.config.ts 的 webServer 调用。
set -e
cd "$(dirname "$0")/../frontend"

if [ ! -d .next ]; then
  CI=true ./node_modules/.bin/next build
fi

exec ./node_modules/.bin/next start -p 3000
