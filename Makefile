# 证券投资分析一期 · 开发/测试/部署入口
# 本机 sdkman JDK 21（若存在）；Gradle 用户目录置于工作区内（沙箱环境需要）
JAVA_HOME ?= $(shell [ -d "$$HOME/.sdkman/candidates/java/21.0.6-amzn" ] && echo "$$HOME/.sdkman/candidates/java/21.0.6-amzn")
export JAVA_HOME
export GRADLE_USER_HOME := $(PWD)/.gradle-home
export GRADLE_OPTS := -Dorg.gradle.native.dir=$(PWD)/.gradle-native
# 读取 .env（若存在）
-include .env
export

.PHONY: dev dev-backend dev-frontend test test-backend test-backend-unit test-backend-integration test-backend-bdd test-frontend test-e2e build up down smoke

## 本地开发：同时启动后端(8080)与前端(3000)
dev:
	@$(MAKE) -j2 dev-backend dev-frontend

dev-backend:
	cd backend && ./gradlew bootRun --console=plain

dev-frontend:
	# 显式固定前端端口：.env 的 PORT 是后端 server.port，经 export 泄漏给 next dev 会抢占后端端口
	cd frontend && pnpm install && PORT=3000 pnpm dev

## 测试
test: test-backend test-frontend collect-test

test-backend:
	cd backend && ./gradlew check --console=plain

# 后端分层测试：单元+切片 / 集成（Testcontainers 真实 PG）/ BDD（Cucumber）
test-backend-unit:
	cd backend && ./gradlew test --console=plain

test-backend-integration:
	cd backend && ./gradlew integrationTest --console=plain

test-backend-bdd:
	cd backend && ./gradlew bdd --console=plain

test-frontend:
	cd frontend && pnpm lint && pnpm test

test-e2e:
	cd frontend && CI=true pnpm test:e2e

## 构建
build:
	cd backend && ./gradlew bootJar --console=plain
	cd frontend && pnpm install && pnpm build

## Docker Compose 部署
up:
	docker compose up -d --build

down:
	docker compose down

## 端到端冒烟
smoke:
	bash scripts/smoke.sh

## 估值数据采集（Python collector）
.PHONY: collect collect-test collect-run collect-backfill

## 列任务
collect:
	cd collector && python -m collector.cli list

## 手动触发一次采集（TASK=<task_code>，如 TASK=all_a_valuation）
collect-run:
	cd collector && python -m collector.cli run $(TASK)

## 按区间回填历史（TASK=<task_code> START=YYYY-MM-DD END=YYYY-MM-DD）
collect-backfill:
	cd collector && python -m collector.cli backfill $(TASK) --start $(START) --end $(END)

## 运行 collector 静态检查 + 测试（覆盖率 >= 80%）
collect-test:
	cd collector && .venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/lint-imports
	cd collector && .venv/bin/pytest -q --cov=collector --cov-fail-under=80
