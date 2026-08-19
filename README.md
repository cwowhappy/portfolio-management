# portfolio-management · 证券投资与分析系统

> 一期：**AI Agent Web 服务** —— A股投研对话助手（2026-08）

架构设计：[docs/plans/2026-08-18-投资分析AI-Agent一期设计.md](docs/plans/2026-08-18-投资分析AI-Agent一期设计.md) ·
决策记录：[docs/adr/](docs/adr/) ·
实施计划：[docs/plans/2026-08-18-一期实施计划.md](docs/plans/2026-08-18-一期实施计划.md) ·
后端分包规范：[docs/backend-package-conventions.md](docs/backend-package-conventions.md)（ArchUnit 强制）

## 能力一览（一期）

- **对话式投研问答**：自然语言问行情、走势、财务、新闻，Agent 自动调用 6 个数据工具并流式输出分析（AG-UI 协议，思考过程/工具进度可视化）
- **行情数据台**：指数速览、股票搜索、实时报价、K线（日/周/月 + MA5/MA20）、财务指标、新闻
- **技术栈**：Spring Boot 4.0.3 + AgentScope Java 2.0.1 + DeepSeek · Next.js 15 + React 19 + CopilotKit（AG-UI 前端）· 东方财富公开接口（新浪兜底）

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
cp .env.example .env          # 填入 DEEPSEEK_API_KEY
docker compose up -d --build
# 打开 http://localhost:3000
```

### 方式二：本地开发

依赖：JDK 21+（可用 sdkman）、Node 20+、pnpm

```bash
cp .env.example .env          # 填入 DEEPSEEK_API_KEY（不填则仅行情可用）
make dev                      # 后端 :8080 + 前端 :3000（.env 自动加载）
```

### 测试与冒烟

```bash
make test                     # 后端 17 个单测 + 前端 3 个单测
make smoke                    # 端到端冒烟（含真实行情接口）
```

## 接口

**AG-UI 对话**（前端经 CopilotKit 运行时 /api/copilotkit 反代）：`POST /agui/run`（RunAgentInput → SSE 事件流）

**行情 REST**（前端经 /api/market/** 反代）：

| 端点 | 说明 |
|---|---|
| GET /api/market/search?q=茅台 | 股票搜索 |
| GET /api/market/quote/600519 | 实时行情 |
| GET /api/market/kline/600519?period=day&limit=120 | K线（day/week/month） |
| GET /api/market/financials/600519 | 财务指标 + PE/PB |
| GET /api/market/news/600519?limit=10 | 个股新闻 |
| GET /api/market/overview | 指数速览 |
| GET /api/agent/health | 健康检查（LLM 配置 + 行情源） |

## 目录结构

```
backend/    Spring Boot 4 + AgentScope Java（agent 工具 / market 数据服务 / web 层）
frontend/   Next.js 15（聊天 UI / 行情台 / API 反代）
docs/       设计文档、ADR、AgentScope 官方资料
scripts/    smoke.sh 冒烟脚本
```

## 配置

| 环境变量 | 默认 | 说明 |
|---|---|---|
| DEEPSEEK_API_KEY | - | 必填（AI 对话）；不填仅行情可用 |
| DEEPSEEK_MODEL | deepseek-chat | 模型名（deepseek-reasoner 亦可） |
| DEEPSEEK_BASE_URL | https://api.deepseek.com | 兼容代理可覆盖 |
| BACKEND_URL | http://localhost:8080 | 前端反代目标 |
| PORT | 8080 | 后端端口 |

## 免责声明

行情与财务数据来自公开接口，可能有延迟或误差；所有分析与回答由 AI 生成，仅供参考，不构成投资建议。
