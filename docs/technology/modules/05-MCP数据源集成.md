# 05 · MCP 数据源集成

> 内置金融数据源 MCP（Model Context Protocol）接入：provider 目录、用户级启用与工具开关、按请求装配 MCP 工具。
> Agent 装配链路见 [01-Agent实现.md](01-Agent实现.md)；写工具审批见 [07-HITL人工审批.md](07-HITL人工审批.md)；数据源调研背景见 [../research/03-金融机构MCP服务参考.md](../research/03-金融机构MCP服务参考.md)。
> 对应代码：`backend/src/main/java/com/portfolio/invest/web/McpConfigController.java`、`application/mcp/`、`domain/mcp/`、`agent/McpClientPool.java`、`agent/UserToolkitFactory.java`（装配）、`infrastructure/persistence/Mcp*`（仓库实现）、`backend/src/main/resources/db/migration/V10__mcp.sql`；前端 `frontend/app/settings/mcp/page.tsx` + `frontend/components/mcp/McpSettingsPage.tsx` + `frontend/lib/mcpApi.ts`；冒烟 `backend/scripts/mcp-smoke.sh`（`application/mcp/`、`domain/mcp/`、`infrastructure/persistence/` 均相对 `backend/src/main/java/com/portfolio/invest/`）。

## 1. 概述

内置工具（行情/估值等 7 个 `@Tool`）之外，Agent 需要机构级数据源（公告、研报、宏观 EDB、港美股、期货等）。本模块以 MCP 标准协议接入三家官方服务，设计要点：

- **内置 provider 目录**：妙想（东方财富 mx-ds）/ Tushare / Wind 三家由 `V10__mcp.sql` seed，用户**不可自定义 server**——`McpConfigController` 只读目录（`GET /providers`）+ 维护个人配置（`PUT/DELETE /configs`），无新增 provider 端点。
- **用户级粒度**：用户按 provider 启用/停用，并可禁用 provider 内单个工具（`disabled_tools`）；Agent 每次对话按当前用户装配。
- **Token 治理**：provider 的 `auth_secret_enc` 在迁移 seed 中一律 **NULL 占位**——真实 token 绝不随迁移进 git，部署时 `UPDATE mcp_provider` 填充。

## 2. 架构与数据流

### 2.1 数据模型（V10__mcp.sql，三表）

| 表 | 粒度 | 关键列 |
|---|---|---|
| `mcp_provider` | 鉴权维度（全局目录） | `code`（唯一）、`auth_type`（NONE/BEARER/HEADER）、`auth_header`、`auth_secret_enc`（token，seed 为 NULL）、`enabled` |
| `mcp_endpoint` | provider 下 1..N 个服务端点 | `provider_id`、`domain`（Wind 6 域；单端点 provider 为 NULL）、`url`、`enabled` |
| `mcp_user_config` | 用户 × provider | `enabled`、`disabled_tools`（JSONB 数组）、`config_version`（每次保存 +1），`UNIQUE(user_id, provider_id)` |

Seed 内容：3 个 provider（`mx-ds` HEADER `em_api_key` / `tushare` BEARER / `wind` BEARER）+ 8 个端点（妙想、Tushare 各 1；Wind 按域拆 stock/fund/index/bond/economic/analytics 共 6）。

### 2.2 按请求装配（对话链路）

```
POST /agui/run
  → CurrentUserHolder.get() 取 userId（AgentConfig 注册 factory 时读取）
  → HarnessAgentFactory.build(userId)
  → UserToolkitFactory.build(userId)
      ├─ 注册内置 7 个 @Tool（InvestTools）
      └─ 遍历启用 provider：
          · 用户未配置/未启用 → 跳过
          · auth_type≠NONE 且 token 空白 → 跳过（防半配置 provider 拖垮装配）
          · McpClientPool.acquire(provider, endpoint, token) 取客户端
          · client.listTools() → 逐工具注册 McpTool
              （disabled_tools 命中或跨 provider 重名 → 跳过；端点失败 log.warn 后继续）
```

### 2.3 MCP 客户端（McpClientPool）

- **传输**：streamable HTTP（`McpClientBuilder.streamableHttpTransport(url)`），`initialize()` 握手后 `buildSync()`。
- **鉴权**：`HEADER` → 自定义头 `provider.authHeader(): token`；`BEARER` → `Authorization: Bearer <token>`。
- **池化**：按 `endpoint.id()` 用 `ConcurrentHashMap` 缓存客户端，进程内复用，不主动失效。
- **连接测试**（设置页「测试连接」按钮）：`AgentScopeMcpServerTester` 对每个启用端点真实 initialize + tools/list（固定 10s 超时），返回 `TestResult{success, tools, latencyMs, errorMessage}`。

### 2.4 前端设置页

`/settings/mcp`（`McpSettingsPage`）：provider 卡片列表（名称/鉴权类型/Wind 域标签）→ 测试连接（成功后拉取 live 工具清单，按工具勾选禁用）→ 保存/删除个人配置。经 Next.js 同源反代访问 `/api/mcp/*`。

## 3. 关键类与配置

| 类/文件 | 职责 |
|---|---|
| `web/McpConfigController` | 6 个端点（见 §4），个人配置以当前登录用户为归属 |
| `application/mcp/McpConfigApplicationService` | 目录/配置/工具清单/连接测试用例；保存为部分更新语义（缺省字段沿用现值） |
| `application/mcp/AgentScopeMcpServerTester`（实现 `McpServerTester` 端口） | 真实握手测试，`CONNECTION_FAILED` 域异常 |
| `domain/mcp/McpProvider` / `McpEndpoint` / `McpUserConfig` | 不可变领域对象；`McpUserConfig.update` 返回新实例（config_version 自增） |
| `domain/mcp/McpConfigRepository` | 仓库端口（含 e2e 种子专用 upsert，`HitlE2eSeedRunner` 用） |
| `agent/McpClientPool` | streamable HTTP 客户端池（connect-timeout） |
| `agent/UserToolkitFactory` | 用户工具箱装配：内置工具 + MCP 工具（readOnly 判定见 [07-HITL人工审批.md](07-HITL人工审批.md)） |

配置（`application.yml` → `InvestProperties.Mcp`）：

```yaml
invest:
  mcp:
    connect-timeout: 10s     # McpClientPool 建连超时
    tool-timeout: 30s        # 已声明（见 §7 已知限制）
    pool-max-size: 20        # 已声明（见 §7 已知限制）
    harness: ...             # HarnessAgent workspace/state/compaction/memory（见 01-Agent实现.md）
```

Token 读取：`UserToolkitFactory` 直接取 `provider.authSecretEnc()` 明文作 token 传给客户端与测试器——一期取舍，列名预留加密位但未实现加密（见 §7）。

## 4. 端点（统一前缀 `/api/mcp`，全部需登录——不在 `PublicEndpointPaths` 公开清单）

| 端点 | 说明 |
|---|---|
| `GET /api/mcp/providers` | 内置目录（id/code/name/authType/authHeader + domains 列表），只读 |
| `GET /api/mcp/configs` | 我的配置列表（providerId/enabled/disabledTools/configVersion） |
| `PUT /api/mcp/configs/{providerId}` | 保存个人配置（`{enabled?, disabledTools?}`，null 字段沿用现值；新建默认启用） |
| `DELETE /api/mcp/configs/{providerId}` | 删除个人配置 → 204（不存在 404） |
| `POST /api/mcp/providers/test` | 连接测试（`{providerId}`），成功带工具清单与耗时 |
| `GET /api/mcp/configs/{providerId}/tools` | live 工具清单（name/description/enabled），要求已有配置 |

错误码（`GlobalExceptionHandler` 映射）：`PROVIDER_NOT_FOUND`/`CONFIG_NOT_FOUND` → 404；`INVALID_INPUT` → 400；`CONNECTION_FAILED` → 502。

## 5. 测试

| 层 | 位置 | 覆盖 |
|---|---|---|
| 单测（application） | `backend/src/test/java/com/portfolio/invest/application/mcp/McpConfigApplicationServiceTest` | 保存/删除/工具清单/测试用例（stub tester + 注入时钟） |
| 单测（domain） | `backend/src/test/java/com/portfolio/invest/domain/mcp/`（McpProviderEndpointTest、McpUserConfigTest、AuthTypeTest） | 领域对象不变式与校验 |
| 集成 | `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/McpConfigRepositoryImplTest` | Testcontainers 真实 PostgreSQL 读写 |
| 集成 | `backend/src/integrationTest/java/com/portfolio/invest/agui/McpHitlIntegrationTest` | 脚本化 MCP server + 模型，覆盖工具装配、readOnly 判定与审批中断（见 07 篇） |
| 冒烟 | `backend/scripts/mcp-smoke.sh` | 对三家内置端点 initialize + tools/list；token 读 `MX_DS_TOKEN`/`TUSHARE_TOKEN`/`WIND_TOKEN` 环境变量，未设置跳过对应 provider |
| e2e | `frontend/e2e/hitl.spec.ts` 前置 | 经同源 API `PUT /api/mcp/configs/{id}` 启用 e2e provider（真实浏览器带会话 Cookie） |

## 6. 已知限制

- **Token 明文**：`auth_secret_enc` 存明文、代码直读（`UserToolkitFactory` 装配与连接测试两处）；加密/密管接入为后续项，列名已预留。
- **`tool-timeout`/`pool-max-size` 声明未接线**：两个键仅在 `InvestProperties.Mcp` 声明，主代码无消费方——工具调用超时与客户端池上限实际由 AgentScope 客户端默认值决定；池目前按端点无上限缓存。
- **Token 更新需重启**：客户端按端点缓存且不失效，部署时 UPDATE token 后已建连接仍用旧值。
- **不可自定义 server**：设计取舍（目录 = 管理面数据，用户只做启用/禁用）；接入新 provider 需 DB 加目录 + 发版。
- **失败静默降级**：装配期端点失败仅 `log.warn` 跳过，对话内无感知（工具就是不在清单里）。
