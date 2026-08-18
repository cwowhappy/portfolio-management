# 证券投资分析一期 · 开发/测试/部署入口
# 本机 sdkman JDK 21（若存在）；Gradle 用户目录置于工作区内（沙箱环境需要）
JAVA_HOME ?= $(shell [ -d "$$HOME/.sdkman/candidates/java/21.0.6-amzn" ] && echo "$$HOME/.sdkman/candidates/java/21.0.6-amzn")
export JAVA_HOME
export GRADLE_USER_HOME := $(PWD)/.gradle-home
export GRADLE_OPTS := -Dorg.gradle.native.dir=$(PWD)/.gradle-native
# 读取 .env（若存在）
-include .env
export

.PHONY: dev dev-backend dev-frontend test test-backend test-frontend build up down smoke

## 本地开发：同时启动后端(8080)与前端(3000)
dev:
	@$(MAKE) -j2 dev-backend dev-frontend

dev-backend:
	cd backend && ./gradlew bootRun --console=plain

dev-frontend:
	cd frontend && pnpm install && pnpm dev

## 测试
test: test-backend test-frontend

test-backend:
	cd backend && ./gradlew test --console=plain

test-frontend:
	cd frontend && pnpm test

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
