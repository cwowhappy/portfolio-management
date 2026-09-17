# 07 · HITL 人工审批

> MCP 写工具的对话内权限审批（Human-in-the-Loop）：readOnlyHint 判定、AG-UI interrupt 审批卡片、批准/拒绝后续跑。
> 决策记录见 [ADR-0010](../decisions/0010-mcp-hitl-permission.md)；MCP 工具装配见 [05-MCP数据源集成.md](05-MCP数据源集成.md)；AG-UI 事件全景见 [../research/04-AGUI事件全景与三场景技术方案.md](../research/04-AGUI事件全景与三场景技术方案.md)。
> 对应代码：`backend/src/main/java/com/portfolio/invest/agent/UserToolkitFactory.java`（readOnly 判定）、`frontend/components/chat/InterruptApprovalCard.tsx`（审批卡片）、`frontend/components/chat/ThreadArea.tsx`（useInterrupt 挂载）、`backend/src/main/java/com/portfolio/invest/infrastructure/seed/HitlE2eSeedRunner.java`（e2e 种子）、`frontend/e2e/hitl.spec.ts`；协议勘误见 [mcp-hitl 验证记录](../../../features/mcp-hitl/03-实施计划/验证记录.md)。

## 1. 概述

MCP 数据源工具可能包含写性质操作（写文件、下单、改配置等）。本模块在**对话内**对写工具逐次审批：Agent 调用写工具时暂停（interrupt），弹出批准/拒绝卡片，用户应答后继续执行或跳过。核心取舍（ADR-0010）：

- **readOnlyHint 缺省视为写**：仅当工具显式声明 `annotations.readOnlyHint=true` 才视为只读放行；缺省或 `false` 一律触发审批——宁多问不漏问（未标注的 MCP 数据源工具上线即全部弹审批，属预期）。
- **无回退开关**：不做 `FORCE_READONLY` 类绕过配置，回滚路径为 git revert 整个特性提交，避免静默绕过审批。

## 2. 架构与数据流

### 2.1 判定（装配期，UserToolkitFactory）

每个 MCP 工具注册时手动构造 `McpTool`，readOnly 判定式与上游 `McpClientManager`（agentscope 2.0.3）逐字一致（上游为包私有，只能复制）：

```java
boolean readOnly = t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint());
```

内置 7 个 `@Tool` 均 `readOnly=true` 直通，不受审批影响。

### 2.2 端到端链路

```
Agent 运行到写性质 MCP 工具
  → AgentScope 权限确认（RequireUserConfirmEvent）→ AG-UI interrupt 事件 → SSE
前端 ThreadArea：useInterrupt({ agentId: AGENT_ID, renderInChat: false, render })
  → interrupts.map → InterruptApprovalCard（工具名 + 可折叠调用参数 + 兜底说明）
用户点 批准/拒绝
  → resolve({ approved: true|false }, interruptId)
  → resume 累积应答全部 open interrupt 后提交
后端：approved=true → 真实调用 MCP 工具；false → 写 DENIED 结果
  → Agent 续跑（拒绝后提示词规约：不原样重试，改走替代方案或说明）
```

### 2.3 前端关键实现（含两轮真机修复）

- **useInterrupt 挂载**（`ThreadArea.tsx`）：`renderInChat: false` 时 hook 返回元素，须手动渲染（挂载点在消息流尾部）；`agentId` 必须显式绑定——hook 内部经 `useAgent` 解析 `config.agentId ?? "default"`，本应用只注册 `invest`，缺省会在运行时抛「Agent 'default' not found」导致对话页崩溃。
- **多卡已处理态放卡片内部 state**（PR #30 修复）：`resolve` 是 accumulate-then-submit——多张审批卡期间全部应答前卡片不消失，需单卡点击即显「已批准/已拒绝，等待其余确认…」。v1 把已处理态放父组件 state，但 **useInterrupt 对 render 产物做元素 memo 且依赖不含宿主组件重渲染**，宿主 setState 后 memo 命中仍返回旧元素、prop 传不进去（真机失效根因）；v2 改由 `InterruptApprovalCard` 内部 state 维护（组件卸载即重置，interrupts 清空/新一轮新 key 重挂）。
- **契约错误翻译**：上游 resume 与 open interrupt 不匹配时返回 `RUN_ERROR / AGUI_INTERRUPT_CONTRACT_ERROR`，`ThreadArea` 按 code（辅以两条稳定文案子串）翻译为友好提示。

### 2.4 线格式配套（AguiEventNonNullCodec）

agentscope 权限确认中断的 `expiresAt` 序列化为 `null`，而前端 `@ag-ui/core` 的 zod schema 只认缺省/字符串——null 会让整条事件被浏览器端拒收（卡片不渲染）。`AguiWireJsonConfig` 启动期把全局 JsonCodec 换为 `AguiEventNonNullCodec`：仅对 `AguiEvent` 的 toJson 剥离 null 字段（null→缺省，前端语义等价），其余序列化全部委托默认实现。该 codec 同时保障图表双通道线格式（见 [08-聊天图表双通道.md](08-聊天图表双通道.md)）。

## 3. 关键类与配置

| 类/文件 | 职责 |
|---|---|
| `agent/UserToolkitFactory` | MCP 工具注册 + readOnly 判定（§2.1） |
| `frontend/components/chat/InterruptApprovalCard.tsx` | 审批卡片：批准/拒绝按钮、`toolInput` 参数折叠详情、内部 state 已处理态（色系对齐涨跌色） |
| `frontend/components/chat/ThreadArea.tsx` | `useInterrupt` 挂载与手动渲染、契约错误翻译 |
| `agent/AguiEventNonNullCodec` + `AguiWireJsonConfig` | AG-UI 事件 NON_NULL 线格式（§2.4） |
| `infrastructure/seed/HitlE2eSeedRunner` | e2e 种子：`E2E_HITL_MCP_URL` 已配置时幂等写入 `hitl-e2e` provider（NONE 鉴权）+ 指向该 URL 的 endpoint；未配置零副作用 |

无独立配置键（e2e 门控用环境变量 `E2E_HITL_MCP_URL`）。

## 4. 测试

| 层 | 位置 | 覆盖 |
|---|---|---|
| 集成 | `backend/src/integrationTest/java/com/portfolio/invest/agui/McpHitlIntegrationTest` | 脚本化 MCP server（真实 MCP 协议）+ 脚本化模型，4 用例：写工具弹中断 / 批准→工具执行且续跑 / 拒绝→跳过且续跑 / 只读工具直通不弹 |
| 前端单测 | `frontend/tests/InterruptApprovalCard.test.tsx` | 卡片渲染与已处理态（**mock 直调 config.render 绕过 useInterrupt memo，抓不到 v1 失效**） |
| e2e | `frontend/e2e/hitl.spec.ts` | 真实浏览器→真实后端→真实 MCP server 全链路（#25 回归钉），`E2E_HITL_MCP_URL` 未配置整组跳过：①批准落盘 + 拒绝不落盘；②两卡并列→逐张批准即时反馈→全部应答后续跑落盘两行（v2 钉子：断言「已批准，等待其余确认…」可见） |
| 种子 | `HitlE2eSeedRunner` | 见 §3；e2e 前置经 `PUT /api/mcp/configs/{id}` 为用户启用 `hitl-e2e` provider |

协议勘误（AgentScope 2.0.1 时代 interrupt 以 RAW 透传、resume 契约错误等四条实测结论，2.0.3 升级后全部消解）正式沉淀于 [features/mcp-hitl/03-实施计划/验证记录.md](../../../features/mcp-hitl/03-实施计划/验证记录.md)。

## 5. 已知限制

- **中断不持久化**：刷新/重启丢未决中断（审批卡消失，本轮运行作废）。
- **卡片无 server 来源标识**：多 provider 工具并列时不显示工具来自哪个数据源。
- **无规则沉淀**：不支持「不再询问」（`ConfirmResult.rules`）、provider/endpoint 级只读信任配置——均列入 ADR-0010 后续治理。
- **多卡必须全部应答**：accumulate-then-submit 语义下，部分应答不会提交 resume（无整体放弃按钮）。
