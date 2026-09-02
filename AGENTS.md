# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

A 股投研对话助手（证券投资与分析系统）。AI Agent Web 服务：对话式投研问答（AG-UI 协议，Agent 调用 6 个行情数据工具并流式输出）+ 行情数据台 + 用户管理 + 会话持久化。

- **后端**：Spring Boot 4 + Spring Security 7 + Spring Data JPA + PostgreSQL 16（Flyway 管 schema）+ AgentScope Java 2.0.1 + DeepSeek
- **前端**：Next.js 15 + React 19 + CopilotKit（AG-UI 前端）+ Tailwind 4
- **行情源**：东方财富公开接口（新浪/腾讯兜底）

## 常用命令

从仓库根目录用 `make`（Makefile 自动加载 `.env`、设置 JDK 21 与 Gradle 用户目录）：

| 命令 | 作用 |
|---|---|
| `make dev` | 同时启动后端(:8080) + 前端(:3000)；本地需先 `docker compose up -d db` |
| `make dev-backend` / `make dev-frontend` | 单独启动一端 |
| `make test` | 后端 + 前端 + collector 全量测试（ArchUnit 架构测试 + Testcontainers 集成测试 + vitest + pytest） |
| `make test-backend` / `make test-frontend` / `make collect-test` | 单独跑一端 |
| `make test-backend-unit` / `make test-backend-integration` / `make test-backend-bdd` | 后端分层跑：单元+切片 / 集成（Testcontainers 真实 PG）/ BDD（Cucumber） |
| `make build` | 后端 `bootJar` + 前端 `next build` |
| `make up` / `make down` | Docker Compose 部署 / 停止 |
| `make smoke` | 端到端冒烟（真实行情 + AI 对话） |
| `cd frontend && pnpm test:e2e` | Playwright 浏览器 e2e |

单个测试：

```bash
# 后端（单个测试类）
cd backend && ./gradlew test --tests "com.portfolio.invest.web.MarketControllerTest" --console=plain
# 前端（单个文件，vitest）
cd frontend && pnpm vitest run tests/lib/api.test.ts
```

前置依赖：JDK 21+、Node 20+、pnpm、Docker（Testcontainers 拉取 postgres:16）。首次 `make dev` 会自动 `pnpm install`。

## 架构

### 后端：DDD 洋葱分层（ArchUnit 强制）

根包 `com.portfolio.invest`，依赖方向**只能向下，禁止反向、禁止成环**：

```
web ──→ application ──→ domain
  │   ──→ agent ──→ application/domain ──→ config
infrastructure ──→ {domain, application, config}
```

| 包 | 职责 |
|---|---|
| `web/` | HTTP 接入层：`@RestController`（Auth/UserAdmin/Conversation/Market/Health）+ `GlobalExceptionHandler` 异常→HTTP 状态映射。只做路由/参数校验/异常翻译 |
| `application/` | 用例编排与事务边界，`*ApplicationService` 结尾；持有对外 DTO |
| `domain/` | **纯 POJO，零 Spring/JPA 注解**；实体/值对象/仓库接口（由 infrastructure 实现） |
| `infrastructure/` | `persistence`（JPA 实体+仓库实现+Flyway）、`security`、`seed`、`market`（东方财富/新浪客户端 + 缓存/限流装饰器） |
| `agent/` | 独立能力域：`InvestSystemPrompt`（提示词）、`InvestTools`（`@Tool` 数据工具）、`AgentConfig`（装配）。**不并入 DDD 分层**，消费 `application.market` 与 `domain.market` |
| `config/` | 仅配置属性（`InvestProperties`，`@ConfigurationProperties(prefix="invest")`），不得依赖任何业务包 |

完整规则见 `docs/technology/conventions/01-后端DDD分包规范.md`（**新增后端代码前必读**）。规则由 `backend/src/test/java/com/portfolio/invest/architecture/PackageConventionsTest.java` 断言，违反即构建失败；新增能力域/域时需在该测试中登记依赖白名单。

### 前端：Next.js 同源反代

前端没有独立后端直连，`app/api/**` 下的 Route Handler 是到后端（`BACKEND_URL`，默认 `http://localhost:8080`）的**同源反代**，会话用 Cookie（`JSESSIONID` + 可选 remember-me）。

- `lib/proxy.ts` 的 `relay()` 是反代核心：透传状态码、`Content-Type`，以及**所有 `Set-Cookie`**（登录/登出可能同时下发多个 cookie，必须逐个 `append`）。
- `app/api/copilotkit/[[...slug]]/route.ts`：CopilotKit 运行时 → `HttpAgent` 打后端 `POST /agui/run`（AG-UI SSE）。后端按 `anyRequest().authenticated()` 保护、靠 `JSESSIONID` 识别会话，而 HttpAgent 的服务端 fetch 不会自动透传浏览器 Cookie，所以**必须按请求构建运行时并把入站 Cookie 注入 HttpAgent**（模块级构建拿不到每个请求的 Cookie）。

关键路径：浏览器 → `/api/copilotkit` → `HttpAgent` → 后端 `POST /agui/run` → AgentScope SSE 事件流。Agent id 为 `invest`，与后端 `agentscope.agui.default-agent-id` 一致。

### 认证与用户管理

注册 → 状态 `PENDING` → 管理员审核通过（`APPROVED`）后方可对话；管理员可停用/启用/重置密码。内置管理员由 `AdminSeedRunner` 幂等种子（`ADMIN_USERNAME`/`ADMIN_PASSWORD`）。角色：`USER` / `ADMIN`。会话按归属隔离，非本人访问返回 404。

## 关键约束

- **覆盖门槛 ≥80%**（`make test` 失败即不过）：后端 JaCoCo 聚合 `test`/`integrationTest`/`bdd` 三层 exec 后统一卡指令/分支双门槛（挂 `check`，聚焦跑单个 suite 不触发），前端 V8 语句/分支，collector pytest `--cov-fail-under=80`。改代码需补测试。
- **后端测试四层**：`test`（单元+切片）/ `integrationTest`（Testcontainers 真实 PG）/ `bdd`（Cucumber 中文场景）/ `testFixtures`（共享 PG 容器基座 `PostgresTestSupport`），详见 `docs/technology/architecture/03-后端测试架构.md`。
- **schema 由 Flyway 管**（`ddl-auto: none`），迁移在 `backend/src/main/resources/db/migration/`（V1–V5）。
- **Jackson 2 而非 Jackson 3**：`spring-boot-starter-webmvc` 已排除 `starter-jackson` 改引 `spring-boot-jackson2`，因为 AgentScope AG-UI 模型基于 Jackson 2 注解。
- **Testcontainers 禁用 Ryuk**（`TESTCONTAINERS_RYUK_DISABLED=true`）：兼容 Colima 等本地 Docker socket 无法挂载的场景，由 JUnit 扩展启停容器。
- **同源 Cookie 会话，无 CORS**：后端 `same-site: lax`，前端同源反代透传 cookie，这是关闭 CSRF 的安全前提（ADR-0007）。
- 前端 lint 已启用：`pnpm lint`（eslint flat config，含 react-hooks/no-explicit-any/组件禁直接 fetch 等规则），纳入 `make test` 与 CI。

## 参考文档

- `README.md`：功能全览 + API 端点表 + 环境变量表 + 目录结构
- `docs/technology/conventions/`（01 后端 DDD 分包 / 02 后端 / 03 前端 / 04 采集服务，改代码前必读对应规范）
- `docs/technology/decisions/`（0001–0009）：Agent 框架、AG-UI 协议、行情源、会话模型、用户认证、后端分层等架构决策
- `features/<feature>/`（特性需求/设计/计划，索引与「特性↔里程碑↔模块」映射见 `features/README.md`）
- `docs/technology/`（技术文档）、`docs/function/`（产品功能）
- `docs/plans/2026-08-27-产品落地计划.md`：里程碑级落地计划与进度跟踪（MS-00~MS-15）
- `docs/README.md`：文档中心总导航
- `docs/code-review-lessons.md`：代码审查经验沉淀（问题模式清单，评审/开发前参考）
