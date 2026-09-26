# 证券投资分析一期 · 开发/测试/部署入口
# 本机 sdkman JDK 21（若存在）；Gradle 用户目录置于工作区内（沙箱环境需要）
JAVA_HOME ?= $(shell [ -d "$$HOME/.sdkman/candidates/java/21.0.6-amzn" ] && echo "$$HOME/.sdkman/candidates/java/21.0.6-amzn")
export JAVA_HOME
export GRADLE_USER_HOME := $(PWD)/.gradle-home
export GRADLE_OPTS := -Dorg.gradle.native.dir=$(PWD)/.gradle-native
# 捕获调用 shell 的 DEEPSEEK_* 覆盖（须在 -include .env 之前：.env 的同名变量会以文件变量身份
# 压掉 shell 环境值，eval-agent 的模型临时切换依赖这里留存原始值）
EVAL_DEEPSEEK_MODEL_FROM_SHELL := $(DEEPSEEK_MODEL)
EVAL_DEEPSEEK_BASE_URL_FROM_SHELL := $(DEEPSEEK_BASE_URL)

# 读取 .env（若存在）
-include .env
export

.PHONY: dev dev-backend dev-frontend test test-backend test-backend-unit test-backend-integration test-backend-bdd test-backend-mutation test-backend-mutation-descartes eval-agent test-frontend test-e2e build up down smoke

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

# PIT 变异测试：核心域三类的手动诊断任务（不挂 check、无门槛），报告在 backend/build/reports/pitest
test-backend-mutation:
	cd backend && ./gradlew pitest --console=plain

# Descartes 方法级粗筛：extreme mutation 定位 pseudo-tested 方法（手动诊断，无门槛），报告在 backend/build/reports/pitest-descartes
test-backend-mutation-descartes:
	cd backend && ./gradlew pitestDescartes --console=plain

# Agent 效果评估：真实 LLM（DeepSeek）周期性诊断（题库 backend/src/eval/resources，报告
# backend/build/reports/eval-agent）。不挂 CI 门禁、无通过率阈值，退出码恒 0。
# 可选：DEEPSEEK_MODEL=deepseek-v4-pro 临时换被评模型（传 shell 环境变量，gradle 任务会盖写 daemon 旧值）；
#       EVAL_ARGS="--compare=<上次报告>" 透传 runner 参数
eval-agent:
	cd backend && ./gradlew evalAgent --console=plain$(if $(EVAL_DEEPSEEK_MODEL_FROM_SHELL), -PevalDeepseekModel=$(EVAL_DEEPSEEK_MODEL_FROM_SHELL),)$(if $(EVAL_DEEPSEEK_BASE_URL_FROM_SHELL), -PevalDeepseekBaseUrl=$(EVAL_DEEPSEEK_BASE_URL_FROM_SHELL),)$(if $(EVAL_ARGS), -PevalArgs=$(EVAL_ARGS),)

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
.PHONY: collect collect-test collect-run collect-backfill industry-stock-backfill

## 列任务
collect:
	cd collector && python -m collector.cli list

## 手动触发一次采集（TASK=<task_code>，如 TASK=all_a_valuation）
collect-run:
	cd collector && python -m collector.cli run $(TASK)

## 按区间回填历史（TASK=<task_code> START=YYYY-MM-DD END=YYYY-MM-DD）
collect-backfill:
	cd collector && python -m collector.cli backfill $(TASK) --start $(START) --end $(END)

## MS-09：5 年个股估值分段回填（半年一段 × 10 次 collect-backfill，规避单事务全量写；逐段容错，失败段结尾汇总可单独重跑——issue #39）
industry-stock-backfill:
	python3 scripts/industry_stock_backfill.py

## 运行 collector 静态检查 + 测试（覆盖率 >= 80%）
collect-test:
	cd collector && .venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/lint-imports
	cd collector && .venv/bin/pytest -q --cov=collector --cov-fail-under=80
