# portfolio-management · 证券投资与分析系统

> **AI Agent Web 服务** —— A股投研对话助手（2026-09）

架构设计：[docs/plans/2026-08-18-投资分析AI-Agent一期设计.md](docs/plans/2026-08-18-投资分析AI-Agent一期设计.md) ·
用户管理设计：[features/specs/2026-08-21-用户管理-design.md](features/specs/2026-08-21-用户管理-design.md) ·
决策记录：[docs/technology/decisions/](docs/technology/decisions/) ·
后端分包规范：[docs/technology/conventions/01-后端DDD分包规范.md](docs/technology/conventions/01-后端DDD分包规范.md)（ArchUnit 强制，DDD 分层）·
产品功能：[docs/function/](docs/function/) ·
技术文档：[docs/technology/](docs/technology/)

## 能力一览

- **对话式投研问答**：自然语言问行情、走势、财务、新闻，Agent 自动调用 6 个数据工具并流式输出分析（AG-UI 协议，思考过程/工具进度可视化）——**需登录后使用**
- **行情数据台**：指数速览、股票搜索、实时报价、K线（日/周/月 + MA5/MA20）、财务指标、新闻——**公开访问**
- **用户管理**：注册/登录（用户名 + 密码，密码 ≥8 位含字母数字）；注册需**管理员审核通过**后方可使用 AI；管理员可审核、停用/启用、重置密码；内置管理员（env 种子）
- **会话持久化**：AI 对话历史存服务端 PostgreSQL、归属账号，换设备可见（标题取首条消息前 24 字）
- **市场估值仪表盘**：全 A PE/PB 中位数及分位、股债利差(ERP)、主要指数估值、破净占比、市场情绪温度计、历史走势——**公开访问**（`/valuation`）
- **持仓组合管理**：持仓/交易/分红/分组，成本与盈亏计算、组合总览与集中度分析——**需登录**（`/portfolio`）
- **资产配置**：经典模板一键套用 + 自定义方案 + 与持仓的偏离度对比——**需登录**（`/allocation`）
- **价值筛选器**：估值/盈利/财务健康/成长/市值流动性五维 AND 组合筛选 + 结果排序——**公开访问**（`/screener`）
- **行业估值**：申万 31 行业 PE/PB/ROE/股息率对比 + 估值热力图，点击跳转筛选器——**公开访问**（`/industry`）
- **技术栈**：Spring Boot 4 + Spring Security 7 + PostgreSQL/Flyway + AgentScope Java · Next.js 15 + React 19 + CopilotKit（AG-UI 前端）· 东方财富公开接口（新浪兜底）· Python 采集服务（akshare/tushare）

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
cp .env.example .env          # 填入 DEEPSEEK_API_KEY、ADMIN_USERNAME、ADMIN_PASSWORD
docker compose up -d --build
# 打开 http://localhost:3000（首次需用内置管理员审核注册用户后方可对话）
```

### 方式二：本地开发

依赖：JDK 21+（可用 sdkman）、Node 20+、pnpm、Docker（集成测试用 Testcontainers 拉取 postgres:16）

```bash
cp .env.example .env          # 填入 DEEPSEEK_API_KEY / ADMIN_USERNAME / ADMIN_PASSWORD
make dev                      # 后端 :8080 + 前端 :3000（.env 自动加载）
```

本地开发需先启动数据库（PostgreSQL 16，Flyway 启动时建表）：

```bash
docker compose up -d db
```

### 测试与冒烟

```bash
make test                     # 后端单元/架构/集成测试（含 ArchUnit 分包规范 + Testcontainers）+ 前端单元测试（vitest）
                              # 覆盖率强制门槛：后端 JaCoCo 指令/分支 ≥ 80%，前端 V8 语句/分支 ≥ 80%
make smoke                    # 端到端冒烟（含真实行情接口 + AI 对话）
cd frontend && pnpm test:e2e  # 浏览器端到端（Playwright）：导航 / 行情台 / AI 对话 / 用户管理 / 会话持久化
                              #   自动拉起后端+前端（已运行时复用）；AI 用例需配置 DEEPSEEK_API_KEY
```

Playwright e2e 位于 `frontend/e2e/`，配置见 `frontend/playwright.config.ts`；行情用例走真实公开接口，偶发波动由重试吸收；用户管理用例需 `ADMIN_USERNAME`/`ADMIN_PASSWORD`（缺失则自动跳过）。

## 接口

**认证**（前端经 /api/auth/** 反代，同源 Cookie 会话）：

| 端点 | 权限 | 说明 |
|---|---|---|
| POST /api/auth/register | 公开 | 注册 → 待审核 |
| POST /api/auth/login | 公开 | 登录，`rememberMe` 可选（勾选 30 天 / 否则关浏览器失效） |
| POST /api/auth/logout | 登录 | 登出 |
| GET /api/auth/me | 登录 | 当前用户 |

**管理员**（前端经 /api/admin/** 反代，需 ADMIN 角色）：

| 端点 | 说明 |
|---|---|
| GET /api/admin/users | 用户列表 |
| POST /api/admin/users/{id}/approve | 审核通过 |
| POST /api/admin/users/{id}/reject | 拒绝 |
| POST /api/admin/users/{id}/enable · /disable | 启用 / 停用 |
| POST /api/admin/users/{id}/reset-password | 重置密码 |

**会话**（前端经 /api/conversations/** 反代，需登录，按归属隔离、非本人 404）：

| 端点 | 说明 |
|---|---|
| GET /api/conversations | 我的会话列表 |
| POST /api/conversations | 新建会话（`{id}` = threadId） |
| GET /api/conversations/{id}/messages | 加载消息 |
| PUT /api/conversations/{id}/messages | 全量替换保存消息 |
| DELETE /api/conversations/{id} | 删除会话 |

**AG-UI 对话**（前端经 CopilotKit 运行时 /api/copilotkit 反代，需登录）：`POST /agui/run`（RunAgentInput → SSE 事件流）

**行情 REST**（前端经 /api/market/** 反代，公开）：

| 端点 | 说明 |
|---|---|
| GET /api/market/search?q=茅台 | 股票搜索 |
| GET /api/market/quote/600519 | 实时行情 |
| GET /api/market/kline/600519?period=day&limit=120 | K线（day/week/month） |
| GET /api/market/financials/600519 | 财务指标 + PE/PB |
| GET /api/market/news/600519?limit=10 | 个股新闻 |
| GET /api/market/overview | 指数速览 |
| GET /api/agent/health | 存活探针（liveness，仅 LLM 配置状态，零外呼） |
| GET /api/agent/status | 服务状态（LLM 配置 + 行情源连通性，探活结果 30s 缓存） |

**估值 / 筛选**（前端经 /api/valuation/**、/api/screening/** 反代，公开只读）：

| 端点 | 说明 |
|---|---|
| GET /api/valuation/overview | 估值总览（全 A 中位数/分位/ERP/温度计/指数估值） |
| GET /api/valuation/industries?sort=pe | 行业估值对比（PE/PB/ROE/股息率，sort 可 pe/pb/roe/dividendYield） |
| GET /api/valuation/history | 估值历史序列 |
| GET /api/screening/stocks?peTtmMax=20&roeMin=15&industryCode=&sortBy=&limit= | 五维组合筛选（12 条件 + 行业 + 排序，limit≤200，至少一个条件） |

**持仓**（前端经 /api/portfolio/** 反代，需登录）：

| 端点 | 说明 |
|---|---|
| GET /api/portfolio/overview | 组合总览 |
| GET /api/portfolio/positions | 持仓列表 |
| POST /api/portfolio/positions/buy · /sell | 买入 / 卖出 |
| POST /api/portfolio/positions/cash-dividend · /stock-dividend | 现金 / 股票分红 |
| GET /api/portfolio/groups · POST /api/portfolio/groups | 持仓分组查询 / 新建 |
| GET /api/portfolio/allocation · /industry-distribution · /concentration | 配置结构 / 行业分布 / 集中度 |

**配置**（前端经 /api/allocation/** 反代，需登录）：

| 端点 | 说明 |
|---|---|
| GET /api/allocation/templates | 配置模板库 |
| GET /api/allocation/plans · POST /api/allocation/plans | 配置方案列表 / 新建 |
| PUT /api/allocation/plans/{planId} · DELETE /api/allocation/plans/{planId} | 修改 / 删除方案 |
| POST /api/allocation/plans/{planId}/activate | 激活方案 |
| GET /api/allocation/deviation | 目标配置 vs 持仓偏离度 |

## 目录结构

```
backend/    Spring Boot 4 + Spring Security 7 + Spring Data JPA（DDD 洋葱分层）
  src/main/java/com/portfolio/invest/
    web/              接入层：Auth / UserAdmin / Conversation / Market / Valuation / Portfolio / Allocation / Screening / Health 控制器
    application/      应用层：auth / useradmin / conversation / market / valuation / portfolio / allocation / screening 用例编排
    domain/           领域层：user / conversation / market / valuation / portfolio / allocation / screening（纯 POJO，零 Spring/JPA）
    infrastructure/   基础设施：persistence(JPA+Flyway) / security / seed / market(客户端+缓存)
    agent/            独立能力域：AgentConfig / InvestTools / InvestSystemPrompt
    config/           配置属性：InvestProperties
  src/main/resources/ application.yml / application-prod.yml / db/migration/(V1~V7)

collector/  Python 3.12 采集服务（akshare/tushare → PostgreSQL，APScheduler 调度）：市场估值快照 / 指数估值 / 国债曲线 / 申万映射 / 指数成分股 / 个股基本面
frontend/   Next.js 15（聊天 UI / 行情台 / 估值 / 持仓 / 配置 / 筛选 / 行业 / 登录注册 / 管理页 / API 反代）
docs/       function/（产品功能）· technology/（技术文档）· 设计文档、ADR
features/   specs/（设计规格）· plans/（实施计划）
scripts/    smoke.sh 冒烟脚本
```

## 配置

| 环境变量 | 默认 | 说明 |
|---|---|---|
| DEEPSEEK_API_KEY | - | 必填（AI 对话）；不填则仅行情可用 |
| DEEPSEEK_MODEL | deepseek-v4-flash | 模型名（deepseek-v4-pro 亦可） |
| DEEPSEEK_BASE_URL | https://api.deepseek.com | 兼容代理可覆盖 |
| POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_DB | invest / invest / invest | 数据库连接 |
| ADMIN_USERNAME / ADMIN_PASSWORD | - | 内置管理员（启动幂等种子，未填则不创建） |
| BACKEND_URL | http://localhost:8080 | 前端反代目标 |
| PORT | 8080 | 后端端口 |
| TUSHARE_TOKEN | - | 采集服务（collector）tushare 数据源 token（个股基本面 / 指数估值 / 申万映射；未填则仅 akshare 数据可用） |

## 免责声明

行情与财务数据来自公开接口，可能有延迟或误差；所有分析与回答由 AI 生成，仅供参考，不构成投资建议。
