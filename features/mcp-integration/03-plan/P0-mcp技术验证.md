# P0 mcp 技术验证 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 验证 AgentScope 2.0.1 的四个关键集成点，产出「验证记录」，确认或修正设计规格，为 P1 后端实现解锁。

**Architecture:** 只读验证（读 jar 源码 / 反编译 / 冒烟），不落业务代码；产物为 `03-plan/验证记录.md` 与对 `02-design/设计规格说明.md` 的修正。四个开放问题见设计规格 §十一。

**Tech Stack:** javap / `./gradlew dependencies` / curl（或一次性 shell 脚本）冒烟 4 台 MCP 端点。

**Spec:** `features/mcp-integration/02-design/设计规格说明.md`（§十一 开放问题）

## Global Constraints

- 不改业务代码，仅验证与记录。
- 验证结论须可复现（贴命令与输出/结论）。
- 涉及第三方 MCP 端点时，真实 Token 用占位符，**不落真实密钥到仓库**。
- 每个 Task 以「验证记录」中的一节为交付物，逐节 commit。

---

### Task 1: 确认 DefaultAgentResolver / AguiRuntimeContextResolver 覆盖点

**Files:**
- 只读：`~/.gradle/caches/.../agentscope-agui-spring-boot-starter-2.0.1.jar`
- Create: `features/mcp-integration/03-plan/验证记录.md`（§1）

**Interfaces:**
- Produces: 验证记录 §1——starter 是否用 `@ConditionalOnMissingBean` 暴露 `AguiRuntimeContextResolver` / `DefaultAgentResolver`；覆盖自定义 bean 的正确写法（bean 名 / 类型）。

- [ ] **Step 1: 反编译 starter 自动装配类**

Run:
```bash
cd backend
# 定位 jar
find ~/.gradle/caches -name 'agentscope-agui-spring-boot-starter-2.0.1.jar' | head -1
# 列出自动装配类并反编译（用 cfr 或 javap -c）
javap -p -c -classpath <jar路径> io.agentscope.spring.boot.agui.common.AguiAgentRegistryAutoConfiguration
javap -p -c -classpath <jar路径> io.agentscope.spring.boot.agui.common.AgentscopeAguiMvcAutoConfiguration
```

要确认的三件事：
1. `AguiRuntimeContextResolver` bean 是否 `@ConditionalOnMissingBean`（还是 `ObjectProvider` 可选注入）。
2. `DefaultAgentResolver` 是否 `@ConditionalOnMissingBean`，以及它被 `AguiMvcController`/处理器如何消费。
3. 是否还有别的自动装配（`AguiAgentAutoRegistration` 把 `@Bean` 的 Agent 按名注册）会与 `registerFactory` 冲突。

- [ ] **Step 2: 写验证记录 §1**

记录结论 + 覆盖写法（例：`@Bean public AguiRuntimeContextResolver ...`；是否需要同 bean 名覆盖 `DefaultAgentResolver`）。

- [ ] **Step 3: Commit**

```bash
git add features/mcp-integration/03-plan/验证记录.md
git commit -m "docs(mcp): P0 验证记录——AgentResolver/ContextResolver 覆盖点"
```

---

### Task 2: 确认 MCP client 工具逐个注册 API（McpTool）

**Files:**
- 只读：`agentscope-core-2.0.1.jar`（`io.agentscope.core.tool.mcp`）
- Modify: `验证记录.md`（§2）

**Interfaces:**
- Produces: 验证记录 §2——`McpClientWrapper` 如何拿到工具清单、`McpTool` 如何逐个注册到 `Toolkit`（用于 `disabled_tools` 过滤）。

- [ ] **Step 1: 反编译 McpClientWrapper / Toolkit / McpTool**

Run:
```bash
javap -p -classpath <core jar> io.agentscope.core.tool.mcp.McpClientWrapper
javap -p -classpath <core jar> io.agentscope.core.tool.mcp.McpTool
javap -p -classpath <core jar> io.agentscope.core.tool.Toolkit
```

确认：`McpClientWrapper` 暴露工具清单的方法（如 `getToolKits()`/`listTools()`）；`Toolkit.registerTool(...)` 能否收单个 `McpTool`；`registerMcpClient` 注册后是否可 `removeMcpClient(String)` 再按需重注册。

- [ ] **Step 2: 写验证记录 §2**

记录结论：`disabled_tools` 过滤的可行写法（逐个 `registerTool(mcpTool)` 或「注册全部→移除禁用」），附方法签名。

- [ ] **Step 3: Commit**

```bash
git add features/mcp-integration/03-plan/验证记录.md
git commit -m "docs(mcp): P0 验证记录——McpTool 逐个注册与过滤"
```

---

### Task 3: 验证共享 Agent 实例的并发模型

**Files:**
- 只读：`agentscope-core-2.0.1.jar`（`ReActAgent`、会话/状态相关类）
- Modify: `验证记录.md`（§3）

**Interfaces:**
- Produces: 验证记录 §3——`server-side-memory=false` 下 `ReActAgent` 单实例被多会话并发调用是否安全；是否需要 `(userId, threadId)` 缓存键。

- [ ] **Step 1: 读源码确认状态存放位置**

Run:
```bash
javap -p -classpath <core jar> io.agentscope.core.agent.ReActAgent
javap -p -classpath <core jar> io.agentscope.core.agent.AgentBase
```

确认：会话消息是否存在于请求上下文（而非 Agent 实例字段）；`run()` 是否有共享可变字段（如迭代计数器、内存）。

- [ ] **Step 2: 写一个最小并发冒烟（可选，若源码无法定性）**

在 `backend/src/integrationTest` 下起一个临时测试：单 `investAgent` bean 并发跑 2 个线程的 `run`，断言无状态串扰。若定性清楚可跳过。

- [ ] **Step 3: 写验证记录 §3**

记录结论 + 缓存键建议（userId 单例，或 (userId, threadId)）。

- [ ] **Step 4: Commit**

```bash
git add features/mcp-integration/03-plan/验证记录.md
git commit -m "docs(mcp): P0 验证记录——共享 Agent 并发模型"
```

---

### Task 4: 冒烟 4 台 MCP 端点

**Files:**
- Modify: `验证记录.md`（§4）

**Interfaces:**
- Produces: 验证记录 §4——4 台端点（URL/鉴权头名/Tushare 是否支持 header）、实际工具清单、错误返回格式。**这些值回填 V10 seed 与 §八提示词措辞。**

- [ ] **Step 1: 对妙想 / Tushare 发起 MCP 握手**

用一次性脚本/curl 对每台端点发 `initialize` → `tools/list`（MCP Streamable HTTP，JSON-RPC）。已知：
- 妙想：`https://mxapi.eastmoney.com/mxds/mcp`，头 `em_api_key`
- Tushare：`https://api.tushare.pro/mcp/token=<TOKEN>`

记录：工具清单（名称+描述）、鉴权头是否生效、错误返回格式。

- [ ] **Step 2: 确认 iFinD / Wind 的端点与头名**

调研文档只给了密钥入口（`mcp.51ifind.com` / `aifinmarket.wind.com.cn`），未给 MCP 端点。查官方文档/注册后抓取，确认：
- iFinD 的 MCP endpoint URL 与鉴权头名
- Wind AIFin 的 MCP endpoint URL 与鉴权头名（`WIND_API_KEY`？）

- [ ] **Step 3: 验证 Tushare 是否支持 header 鉴权**（已确认支持 `Authorization: Bearer`，URL_TOKEN 已弃用）

试以 header（如 `Authorization` 或自定义头）替代 URL token 访问 Tushare MCP，记录结果。

- [ ] **Step 4: 写验证记录 §4** + 回填设计规格 §五 的鉴权建模（如需）

- [ ] **Step 5: Commit**

```bash
git add features/mcp-integration/03-plan/验证记录.md
git commit -m "docs(mcp): P0 验证记录——4 台 MCP 端点冒烟"
```

---

### Task 5: 汇总验证记录，确认/修正设计规格

**Files:**
- Modify: `验证记录.md`（汇总 + 结论）
- Modify: `features/mcp-integration/02-design/设计规格说明.md`（§十一 开放问题 → 结论）

**Interfaces:**
- Consumes: Task 1–4 结论。
- Produces: `验证记录.md` 终稿；设计规格 §十一 的开放问题逐条标注「已确认 + 结论」。

- [ ] **Step 1: 在验证记录顶部写「结论速览」表**

四问 + 结论 + 对 P1 的影响。

- [ ] **Step 2: 更新设计规格 §十一**

把开放问题替换为「已确认结论」，若端点/头名/过滤方式变化则同步修正 §四/§五。

- [ ] **Step 3: Commit**

```bash
git add features/mcp-integration/03-plan/验证记录.md features/mcp-integration/02-design/设计规格说明.md
git commit -m "docs(mcp): P0 验证记录定稿，设计规格开放问题落地"
```

---

## P0 完成验证

```bash
# 确认验证记录四节齐全、结论可复现、无真实密钥
grep -n "TOKEN\|api_key" features/mcp-integration/03-plan/验证记录.md   # 应只有占位符
```

确认：设计规格 §十一 无遗留开放问题；V10 seed 的端点/头名已具备回填依据。
