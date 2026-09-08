#!/usr/bin/env bash
# Playwright e2e 后端启动：bootRun（JDK 21 + .env）。由 playwright.config.ts 的 webServer 调用。
set -e
cd "$(dirname "$0")/../backend"

# 优先使用 sdkman 的 JDK 21（与 Dockerfile / build.gradle 目标一致）
if [ -d "$HOME/.sdkman/candidates/java/21.0.6-amzn" ]; then
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.6-amzn"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# 载入 DEEPSEEK_API_KEY 等（若存在），使 AI 对话冒烟可用
if [ -f ../.env ]; then
  set -a
  # shellcheck disable=SC1091
  . ../.env
  set +a
fi

# e2e MCP server 种子透传（bootRun 继承进程 env；本地由 webServer env 提供，CI 由 workflow env 提供）
export E2E_HITL_MCP_URL="${E2E_HITL_MCP_URL:-http://127.0.0.1:8765/mcp}"

exec ./gradlew bootRun --console=plain
