# CopilotKit 与 AG-UI 协议的 HITL（Human-in-the-Loop）支持调研

- 调研日期：2026-09-08
- 调研方式：全部结论追至一手来源（AG-UI 官方文档/spec draft、npm 包源码、GitHub PR/issue/release notes、CopilotKit 官方文档、项目本地 node_modules 反编译类型定义）；未核实处显式标注
- 项目背景：AgentScope Java 2.0.1 后端（agentscope-agui-spring-boot-starter，`/agui/run` SSE）+ CopilotKit v2 前端（`@copilotkit/react-core`/`@copilotkit/runtime` 1.70.1 + `@ag-ui/client` 0.0.59，ADR-0006），经 Next.js `/api/copilotkit` → CopilotRuntime → HttpAgent 代理后端

---

## 1. TL;DR

1. **AG-UI 协议没有 `RUN_INTERRUPTED` 事件**。HITL 中断的官方机制是 `RUN_FINISHED` 携带可选 `outcome: { type: "interrupt", interrupts: [...] }`，前端用下一轮 `RunAgentInput.resume[]`（`{interruptId, status: "resolved"|"cancelled", payload}`）应答。该机制于 **@ag-ui/core 0.0.54（2026-05-29 发布）进入 0.x schema**（PR #1569，2026-04-30 merged），行为规范在 1.0 draft spec 中成文（**draft 尚未批准**）。来源：[concepts/interrupts](https://docs.ag-ui.com/concepts/interrupts)、[PR #1569](https://github.com/ag-ui-protocol/ag-ui/pull/1569)、npm 0.0.53/0.0.54 tarball 实测比对。

2. **AgentScope Java 2.0.3（2026-09-07 发布）已修复本项目 P0 发现的核心缺口**："Emit AG-UI interrupt for permission-type HITL tool confirmation"（PR #2495 / issue #2437）。`RequireUserConfirmEvent` 现在映射为 `reason: "tool_call"` interrupt（metadata 含 `agentscope.interruptKind: "permission_confirm"`、`toolName`/`toolInput`/`toolContent`/`replyId`），`resume[]` payload 直接转换为 `ConfirmResult`——不再需要 `AguiMessage` metadata 承载（P0 报告的 blocker 被绕开）。**agui.md 第 264-266 行的「2.0.1 未实现」版本警告针对 2.0.1 成立，升级 2.0.3 后可解除**。来源：[v2.0.3 release notes](https://github.com/agentscope-ai/agentscope-java/releases)、[PR #2495](https://github.com/agentscope-ai/agentscope-java/pull/2495)、main 分支源码 `AguiInterruptConstants.java`。

3. **项目现有前端依赖（CopilotKit 1.70.1）已内置全部 v2 HITL hooks**，无需升级：`useInterrupt`（消费标准 AG-UI interrupt 流，`resolve()`/`cancel()` 自动组装 `resume[]` 并经 `copilotkit.runAgent` 提交）、`useHumanInTheLoop`（tool-based HITL）、`useAgent` headless 原语。实测本地 `node_modules/@copilotkit/react-core@1.70.1/dist/copilotkit-*.d.cts` 均有导出。来源：本地源码 + [useInterrupt 文档](https://docs.copilotkit.ai/human-in-the-loop/useInterrupt)。

4. **CopilotKit 官方把 HITL 归纳为两种模式**：`useHumanInTheLoop`（LLM 主动调用前端工具发起询问，即 tool-based）与 `useInterrupt`（agent 运行时强制暂停，即 interrupt-based）。v1 的 `useCopilotAction` + `renderAndWaitForResponse` 仍在（render 已标注 deprecated 指向 renderAndWaitForResponse），但 v2 应改用 `useHumanInTheLoop`。来源：[HITL Overview](https://docs.copilotkit.ai/human-in-the-loop)、本地 1.70.1 类型源码。

5. **`useInterrupt` 的标准流是协议级的、不依赖 LangGraph**：它检测的是 `RUN_FINISHED` + `outcome.type === "interrupt"`（任意 AG-UI agent 均可触发）；LangGraph `on_interrupt` custom event 只是 legacy 兼容路径（经 `forwardedProps.command` resume）。本项目自定义 AgentScope agent 走标准流即可。来源：[useInterrupt 文档「AG-UI standard interrupt flow vs. legacy」节](https://docs.copilotkit.ai/human-in-the-loop/useInterrupt)。

---

## 2. AG-UI 协议的 HITL 机制

### 2.1 中断机制：interrupt-aware run lifecycle（无 RUN_INTERRUPTED 事件）

官方事件家族共八个：Lifecycle（`RUN_STARTED`/`RUN_FINISHED`/`RUN_ERROR`/`STEP_STARTED`/`STEP_FINISHED`）、Text Message、Tool Call、State、Activity、Special（`RAW`/`CUSTOM`）、Reasoning、Subagent——**不存在 `RUN_INTERRUPTED` 事件类型**（全量事件清单见 [concepts/events](https://docs.ag-ui.com/concepts/events)，spec 见 [spec/draft/events](https://docs.ag-ui.com/spec/draft/events/index.md)）。

协议的设计立场（[spec/draft/basic/patterns/interrupt-resume](https://docs.ag-ui.com/spec/draft/basic/patterns/interrupt-resume.md) 原文）：

> The protocol has no mid-run channel from the consumer, so the run does not wait: it ends, saying what it is waiting for, and the run that continues from it carries the answers.

即：**run 不挂起等待，而是正常结束并声明等待什么；续跑是同 thread 上的一个新 run**。

`RUN_FINISHED` 携带可选 `outcome`（discriminated union，[concepts/interrupts](https://docs.ag-ui.com/concepts/interrupts)）：

```typescript
type RunFinishedOutcome =
  | { type: "success" }
  | { type: "interrupt"; interrupts: Interrupt[] }  // interrupts 非空

type Interrupt = {
  id: string              // 跨 interrupt/resume/幂等/审计的关联键
  reason: string          // 分类路由提示（开放字符串，见 2.3）
  message?: string        // 人类可读提示，通用兜底 UI 内容
  toolCallId?: string     // 绑定到先前的 TOOL_CALL_* 序列
  responseSchema?: JsonSchema  // resume.payload 的 JSON Schema
  expiresAt?: string      // 可选 TTL，过期 resume 产生 RunError
  metadata?: Record<string, any>  // 框架自有数据（开放）
  subagentRunId?: string  // 子代理归因
}
```

`outcome` 省略 = legacy 生产者 = 视为正常完成（向后兼容；旧 schema 校验仍通过）。`RUN_ERROR` 是唯一错误事件，`outcome` 枚举不含 `"error"`。

Agent 中断时的状态契约（[concepts/interrupts §State at the interrupt boundary](https://docs.ag-ui.com/concepts/interrupts)）：**必须在携带 interrupt 的 `RUN_FINISHED` 之前** emit `STATE_SNAPSHOT` 与 `MESSAGES_SNAPSHOT`，使 replay 式与 checkpoint 式续跑产生一致的 observable 行为。

### 2.2 resume[] 契约

下一轮同 `threadId` 的 `RunAgentInput` 携带 `resume` 数组（[concepts/interrupts §Resuming a run](https://docs.ag-ui.com/concepts/interrupts)、[spec/draft/basic/run-input §resume](https://docs.ag-ui.com/spec/draft/basic/run-input.md)）：

```typescript
type RunAgentInput = {
  // ... threadId, runId, messages, tools, context, state, forwardedProps
  resume?: Array<{
    interruptId: string
    status: "resolved" | "cancelled"
    payload?: any          // 用户应答，可按 responseSchema 校验
    metadata?: Record<string, any>  // 信封数据（如签名）；"ag-ui" key 保留
  }>
}
```

官方契约规则（8 条，摘要）：

1. **同 thread**：resume 必须用被中断 run 的同一 `threadId`；
2. **关联**：`resume[].interruptId` 必须指向被中断 run `interrupts[]` 的某个 `id`；
3. **全覆盖**：一次 resume 必须应答**所有** open interrupts，部分应答不支持（omission is not abandonment）；
4. **pending 阻塞新输入**：线程上有未决 interrupt 时，任何 `RunAgentInput` 必须带 `resume`，否则 agent 发 `RUN_ERROR`；
5. **幂等**：相同 `(threadId, interruptId, status, payload)` 可安全重放；
6. **payload 校验**：声明了 `responseSchema` 的 interrupt，agent 可校验 payload 并在失配时 `RUN_ERROR`；
7. **过期**：客户端不得在 `expiresAt` 后提交 resolved（仍可 cancelled）；
8. **优雅处理**：缺失/非法 resume 一律 `RUN_ERROR`，不得静默失败。

`resolved` 与 `cancelled` 的语义：`resolved` = 用户给出了应答（**拒绝表达在 payload 里**，如 `{approved: false}`，没有单独的 "denied" 状态）；`cancelled` = 用户放弃、未提供有意义输入（payload 应省略）。

**Tool-bound interrupt 的审计轨迹**（`reason: "tool_call"` + `toolCallId` 时，[concepts/interrupts §Tool-bound interrupts](https://docs.ag-ui.com/concepts/interrupts)）：

1. 被中断 run 的 `TOOL_CALL_ARGS`（agent 的提案）；
2. 续跑 run 的 `RunAgentInput.resume` payload（用户决定与修改）；
3. 续跑 run 的 `TOOL_CALL_RESULT`（实际执行结果，对**原始** `toolCallId`）。

续跑时 agent **不得**重发 `TOOL_CALL_START`/`TOOL_CALL_ARGS`/`TOOL_CALL_END`。

官方推荐的 approve-with-edits `responseSchema` 模式（`editedArgs` 是**全量替换**而非合并；schema 中出现 `editedArgs` 即客户端可提供编辑 UI 的能力信号）：

```json
{
  "type": "RUN_FINISHED",
  "threadId": "thread-1",
  "runId": "run-1",
  "outcome": {
    "type": "interrupt",
    "interrupts": [
      {
        "id": "int-abc123",
        "reason": "tool_call",
        "message": "Send email to a@b.com with subject 'Hi'?",
        "toolCallId": "tc-001",
        "responseSchema": {
          "type": "object",
          "properties": {
            "approved": { "type": "boolean" },
            "editedArgs": { "type": "object", "description": "Full replacement of the tool args. Not merged." }
          },
          "required": ["approved"]
        }
      }
    ]
  }
}
```

应答（多 interrupt 时逐条覆盖，可混用 resolved/cancelled）：

```json
{
  "threadId": "thread-1",
  "runId": "run-2",
  "messages": [],
  "resume": [
    { "interruptId": "int-abc123", "status": "resolved", "payload": { "approved": true } }
  ]
}
```

### 2.3 interrupt reason 官方取值

`reason` 是**必填开放字符串**；spec 定义三个 core 值，其余任何字符串均为合法扩展（[concepts/interrupts §Reason taxonomy](https://docs.ag-ui.com/concepts/interrupts)）：

| Core 值 | 语义 | 伴随字段 |
| --- | --- | --- |
| `tool_call` | 中断绑定到某个待决断的 tool call | `toolCallId` **必须**设置 |
| `input_required` | agent 需要结构化输入才能继续 | `responseSchema` 应设置 |
| `confirmation` | 不绑定 tool 的独立是非决断 | `responseSchema` 可选；默认 boolean |

自定义 reason **建议**（should，非 must）按 `<framework>:<name>` 命名空间（如 `langgraph:database_modification`）；`core:` 前缀保留给 spec。客户端对未知 reason 不得报错，应从 `message`/`responseSchema`/`metadata` 渲染。

> 实测注：AgentScope Java 2.0.3 的权限确认 interrupt 用 core 值 `tool_call` + `metadata["agentscope.interruptKind"]="permission_confirm"` 区分种类（源码 `AguiInterruptConstants.java`），未用命名空间自定义 reason。PR #2495 描述文本中曾写 reason 为 `tool_confirmation`，与最终 main 源码不符，以源码为准。

### 2.4 Tool-based HITL：前端工具（client-side tools）

协议把 frontend tool 称为 "the protocol's human-in-the-loop core: the agent proposes, the application disposes"（[spec/draft/events/tool-calls §Frontend tools](https://docs.ag-ui.com/spec/draft/events/tool-calls.md)）。机制：

1. 前端在 `RunAgentInput.tools` 注入工具 schema（name/description/parameters）；`tools` 省略与空数组等价（[spec/draft/basic/run-input §tools](https://docs.ag-ui.com/spec/draft/basic/run-input.md)）。`RunAgentInput.tools` **只**放前端工具，后端自有工具不应从这里下发（[concepts/tools](https://docs.ag-ui.com/concepts/tools)）。
2. agent 发 `TOOL_CALL_START` → `TOOL_CALL_ARGS`（参数流式 delta）→ `TOOL_CALL_END`。
3. **producer 不得回答 frontend tool**（无 `TOOL_CALL_RESULT`、不得伪造 tool message）；run 以**普通 success outcome** 尽快结束。
4. run 结束后前端自行处置每个未应答调用（执行或拒绝）；**线程要继续就必须全部应答**：下一轮 `RunAgentInput.messages` 中每个调用对应一条 tool message（keyed `toolCallId`）。失败也是应答（`error` 字段），用户拒绝同样以 tool message 说明。悬空调用会导致很多模型直接拒绝该历史。
5. 与 interrupt 的官方区分（原文）："an interrupt is the producer explicitly stopping to ask, answered by resume entries; a frontend tool call rides the ordinary message loop, answered by conversation history."

结果消息形态（[concepts/tools §Tool Results](https://docs.ag-ui.com/concepts/tools)）：

```json
{ "id": "result-789", "role": "tool", "content": "true", "toolCallId": "tool-123" }
// 失败：加 "error": "the production environment is locked"
```

安全要求（spec Security Considerations）：副作用工具执行前应用**应**取得用户同意，且不得将未批准的调用谎报为已批准。

### 2.5 State-based HITL：STATE_SNAPSHOT / STATE_DELTA

共享状态是 HITL 的协同通道而非阻塞通道（[concepts/state §Human-in-the-Loop Collaboration](https://docs.ag-ui.com/concepts/state)）：`STATE_SNAPSHOT` 全量替换、`STATE_DELTA` 以 JSON Patch 增量；用户可实时观察 agent 状态、也可修改 state 后随下一轮 `RunAgentInput.state` 回传，形成反馈环。它适合「协同决策/纠偏」，**不是**需要 agent 暂停等待的确认机制；中断边界处的状态一致性由 2.1 的「interrupt 前必须先 emit snapshot」规则保障。

### 2.6 能力发现（capabilities）

`AgentCapabilities.humanInTheLoop` 声明 agent 的 HITL 能力（[concepts/capabilities](https://docs.ag-ui.com/concepts/capabilities)，`@ag-ui/core` 0.0.59 实测）：`supported` / `approvals` / `interventions` / `feedback` / `interrupts`（参与 AG-UI interrupt 协议：emit RUN_FINISHED interrupt outcome 且接受 resume[]）/ `approveWithEdits`（tool_call interrupt 的 resume payload 接受 editedArgs）。前端可据此决定渲染哪种审批 UI。

### 2.7 协议演进时间线

| 时间 | 事件 | 来源 |
| --- | --- | --- |
| 2025-04-09 | AG-UI 仓库公开（updates 页仅此一条记录） | [development/updates](https://docs.ag-ui.com/development/updates) |
| 2025 年中起 | legacy HITL：frontend tools + LangGraph `on_interrupt` CustomEvent + `forwardedProps.command.resume` | [concepts/interrupts §Framework integrations](https://docs.ag-ui.com/concepts/interrupts) |
| 2026-04-22 | PR #1555 merged：interrupt-aware run lifecycle 提案文档（即曾经的 `drafts/interrupts` 页，现移至 `concepts/interrupts`） | [PR #1555](https://github.com/ag-ui-protocol/ag-ui/pull/1555) |
| 2026-04-30 | PR #1569 merged："feat: interrupt-aware run lifecycle in TS + Python core SDKs" | [PR #1569](https://github.com/ag-ui-protocol/ag-ui/pull/1569) |
| 2026-05-29 | **@ag-ui/core 0.0.54 发布**：`resume`/`RunFinishedOutcome`/`Interrupt`/`ResumeEntry` 首次进入 0.x schema（实测 0.0.53 无、0.0.54 有） | npm registry + tarball 比对 |
| 2026-06-12 | PR #1945：LangGraph 结构化 interrupt/resume（opt-in `emitInterruptOutcome`，默认 off；legacy `on_interrupt` 默认仍开） | [PR #1945](https://github.com/ag-ui-protocol/ag-ui/pull/1945) |
| 2026-06~07 | mastra（#2059/#2060）、crewai（#2284）、claude-agent-sdk（#2181）、adk（#2102，open）等集成跟进标准 outcome | GitHub PR 检索 |
| 进行中 | **1.0 draft spec** 将 outcome/resume 行为规范化（Major change #2 "Runs report how they ended"），页面自标 "Draft — not yet ratified… Do not cite it as a stable reference" | [spec/draft/changelog](https://docs.ag-ui.com/spec/draft/changelog.md) |

注意：当前 live 文档（`concepts/*`）已按 1.0 draft 口径书写；`spec/draft/*` 页面均带未批准警告。0.x schema（≥0.0.54）已实装 outcome/resume 的结构，行为规范以 draft 为准——**引用协议细节时建议同时标注 draft 状态**。

---

## 3. CopilotKit 的 HITL 支持矩阵

### 3.1 官方归纳的 HITL 模式

[HITL Overview](https://docs.copilotkit.ai/human-in-the-loop) 官方归纳为两种互补模式（另有 headless 变体与 governed-actions 应用模式）：

| 模式 | 谁决定暂停 | 后端形态 |
| --- | --- | --- |
| `useHumanInTheLoop`（tool-based） | **LLM**：模型调用注册的前端工具 | 前端工具（Zod schema + render），无 handler |
| `useInterrupt`（graph/runtime-paused） | **运行时**：LangGraph `interrupt()` 或任意 AG-UI agent 的标准 interrupt outcome | 服务端中断 + resume |

v2 hooks 全景（[Which Hook for Which Job](https://docs.copilotkit.ai/concepts/which-hook)）：`useFrontendTool`（执行）、`useRenderTool`/`useDefaultRenderTool`/`useComponent`（渲染）、`useHumanInTheLoop`（暂停等审批）、`useInterrupt`（中断处理）、`useRenderToolCall`（headless 渲染）。

### 3.2 v1：`useCopilotAction` 的 `renderAndWaitForResponse`

版本名核实（本地 `@copilotkit/react-core` 1.70.1 类型源码实测）：

- 正式名 **`renderAndWaitForResponse`**，同时存在**别名 `renderAndWait`**（两者类型签名完全一致，均为 `(props: ActionRenderPropsWait<T>) => ReactElement`）；`render` 已标注 `@deprecated use renderAndWaitForResponse instead`。
- 状态机是 **`"inProgress" | "executing" | "complete"`**（`InProgressStateWait` / `ExecutingStateWait` / `CompleteStateWait`）——**不是** CHECK/LATEST（本地源码与 [v1 useCopilotAction reference](https://docs.copilotkit.ai/reference/v1/hooks/useCopilotAction) 均无此命名；如别处见过该说法，属其他产品/旧文，未在 CopilotKit 官方来源核实到）。
- `executing` 态提供 `respond: (result: any) => void`（旧名 `handler` 已 deprecated）；组件保持渲染直到 `respond` 被调用，response 作为该 action 的 tool 结果回传给 agent；`complete` 态 `result` 可用。
- 本质：`renderAndWaitForResponse` 就是「前端工具 + 阻塞等待用户应答」的 v1 封装；v2 对应物是 `useHumanInTheLoop`（[v2 reference](https://docs.copilotkit.ai/reference/v2/hooks/useHumanInTheLoop)：基于 `useFrontendTool`，内部 handler 在 `respond` 时 resolve tool call promise，状态机同为 InProgress → Executing → Complete）。

### 3.3 v2：`useInterrupt`（标准 AG-UI interrupt 流，不依赖 LangGraph）

[useInterrupt 深度页](https://docs.copilotkit.ai/human-in-the-loop/useInterrupt) + 本地 1.70.1 源码 docstring 实测，支持两种传输：

**Standard flow（本项目适用）**——agent 后端符合 AG-UI 协议，emit `RUN_FINISHED` 且 `outcome.type === "interrupt"`：

- hook 在 `onRunFinishedEvent` 检测、`onRunFinalized` 后暴露给 render props；
- render props：`interrupt`（主 interrupt，`interrupts[0]`）、`interrupts`（全部 open set）、`resolve(payload?, interruptId?)`、`cancel(interruptId?)`；
- `resolve` 记录 `{status: "resolved", payload}`，`cancel` 记录 `{status: "cancelled"}`；**accumulate-then-submit**：全部 open interrupt 被应答后，hook 自动把累积的应答组装成**单一 spec `resume` 数组**经 `copilotkit.runAgent` 提交（本地源码 docstring 原文确认）；
- `responseSchema` 仅透出给 UI（可驱动表单），**不做客户端校验**，校验是 agent resume 侧责任；
- 多 interrupt：可按 `interruptId` 逐个 resolve/cancel。

**Legacy flow**——老 agent emit `on_interrupt` custom event：payload 在 `event.value`，`resolve(payload)` 经 `forwardedProps.command` resume（LangGraph legacy 机制）；`cancel()` 仅本地消失不 resume。两信号并存时 standard 优先。

其它能力：`enabled` 谓词按 payload `type` 分流多类中断；`handler` 可在渲染前预处理（可提前 `resolve`，如已有权限时免打扰）；`renderInChat: true`（默认，element 发布进 `<CopilotChat>`）/ `false`（hook 返回 element，任意位置放置——**本项目 headless 自绘的关键**）。

**底层原语**（[Headless Interrupts](https://docs.copilotkit.ai/human-in-the-loop/headless)）：

1. `agent.subscribe({ onCustomEvent, onRunStartedEvent, onRunFinishedEvent, onRunFinalized, onRunFailed })` —— 每个 `AbstractAgent`（含 `HttpAgent`）都有的 AG-UI 事件订阅；
2. `copilotkit.runAgent({ agent, resume })`（standard）或 `copilotkit.runAgent({ agent, forwardedProps: { command: { resume, interruptEvent } } })`（legacy）。

`@ag-ui/client` 0.0.59 的 `RunAgentParameters` 已含 `resume?: ResumeEntry[]`（本地类型源码实测），即官方 client SDK 层可直接携带 resume。

### 3.4 useCoAgent / AgenticChat / 自定义 AG-UI agent

- **`useCoAgent` / `useCoAgentStateRender` / `useLangGraphInterrupt` 是 v1 hooks**（仍在 [v1 reference](https://docs.copilotkit.ai/reference/v1/hooks/useCoAgent) 维护，1.70.1 包内仍有 `LangGraphInterruptRender` 等类型导出）；v2 由 `useAgent` + `useInterrupt` 取代。LangGraph `interrupt()` 的渲染在 v2 就走 `useInterrupt`（其 legacy 分支专门兼容 LangGraph 的 `on_interrupt` 事件）。
- **对自定义 AG-UI agent（非 LangGraph）**：`useInterrupt` standard flow 只认 `RUN_FINISHED.outcome`，与后端框架无关——AgentScope 后端只要按协议 emit interrupt outcome 即可被渲染与 resume。不依赖 LangGraph 事件格式（LangGraph 特有部分仅 legacy 分支）。
- **AgenticChat / CopilotChat**：`useInterrupt({renderInChat: true})` 的 element 会发布进聊天流，位于暂停的 assistant 轮与未完成的续轮之间；组件本身不绑定某框架（[Chat Components](https://docs.copilotkit.ai/agentic-chat-ui)）。本项目 headless 自绘，用 `renderInChat: false` 或直接用原语。
- **CopilotRuntime 的角色**（[Self-managed agents](https://docs.copilotkit.ai/backend/self-managed-agents)）：标准路径是 前端 → `runtimeUrl` → runtime（`/info` 发现 agents，服务端代理每次 run，提供 auth/middleware/routing）。也可绕过 runtime 直连：`selfManagedAgents`（企业付费特性）或 `agents__unsafe_dev_only`（仅本地开发）。**本项目走 runtime 代理路径**（`app/api/copilotkit/[[...slug]]/route.ts`：`CopilotRuntime({agents:{invest: new HttpAgent(url: /agui/run)}})`，注入 Cookie、裁剪 messages）。runtime 1.70.1 源码已实现标准 interrupt 协议感知（`InterruptSignal`、`interrupt: (interrupts) => Promise<ResumeEntry[]>`、`interrupt` 工具标志等，用于 BuiltInAgent 工厂模式），对 HttpAgent 代理路径 resume 随 `RunAgentParameters` 透传。

### 3.5 支持矩阵总表

| CopilotKit API | 对应 AG-UI 机制 | 暂停发起方 | 结果回传 | 版本可用性 |
| --- | --- | --- | --- | --- |
| v1 `useCopilotAction` + `renderAndWaitForResponse` | frontend tools（`tools` 注入 + 下一轮 tool message） | LLM | 下一轮 messages 的 tool 消息 | 1.70.1 有（v1 路径） |
| v2 `useHumanInTheLoop` | 同上（`useFrontendTool` 封装） | LLM | 同上 | 1.70.1 有 |
| v2 `useInterrupt`（standard） | `RUN_FINISHED.outcome.interrupt` + `resume[]` | agent 运行时 | `resume[]`（自动组装提交） | 1.70.1 有 |
| v2 `useInterrupt`（legacy） | `on_interrupt` CustomEvent + `forwardedProps.command` | LangGraph | `forwardedProps.command.resume` | 1.70.1 有 |
| headless 原语（`agent.subscribe` + `copilotkit.runAgent({resume})`） | 同 standard | — | 同 standard | 1.70.1 有 |
| governed-actions（应用模式） | 用 `useInterrupt` 建模审批信封（allow/deny/require_approval） | 策略引擎 | `resolve({approved, actionId, reference})` | 文档模式，非独立 API |

（governed-actions 见 [Governed Action Approval UI](https://docs.copilotkit.ai/human-in-the-loop/governed-actions)：审批卡片模式，本质是 useInterrupt 的用法定式。）

---

## 4. AgentScope Java 后端现状（C 部分）

### 4.1 版本与修复

最新版本 **2.0.3**（GitHub API `published_at: 2026-09-07T13:51:18Z`；2.0.2 为 2026-09-03，与 AG-UI 无关，全是 agent-protocol/harness/channel 改动）。[v2.0.3 release notes](https://github.com/agentscope-ai/agentscope-java/releases) 的 AG-UI Fixed 段直接命中本项目 P0 报告的缺口：

| 2.0.3 修复 | 对本项目的意义 |
| --- | --- |
| **Emit AG-UI interrupt for permission-type HITL tool confirmation**（[#2495](https://github.com/agentscope-ai/agentscope-java/pull/2495)，修复 [#2437](https://github.com/agentscope-ai/agentscope-java/issues/2437)） | P0 报告核心问题：2.0.1 权限确认流走 RAW + `REQUEST_STOP`、无 interrupt outcome、`resume[]` 被拒（`AGUI_INTERRUPT_CONTRACT_ERROR`）——**已修复** |
| Emit frontend tool args from fragment deltas（#2874） | 前端工具参数流式 |
| Isolate HITL sessions by user（#2856） | 多用户隔离 |
| Assign per-tool result message ids（#2908） | TOOL_CALL_RESULT 消息 id |
| Stop emitting RUN_FINISHED after RUN_ERROR by default（#2646） | 与 agui.md「RUN_ERROR/RUN_FINISHED 互斥、emitRunFinishedAfterError 默认 false」描述对齐（2.0.1 文档已如此描述，2.0.3 代码默认对齐） |
| Parse request bodies with Jackson 2 codec for Boot 4 / multimodal MessageContent（#2638） | Boot 4 兼容 |
| Core：Return suspended results for external tools; emit `RequireExternalExecutionEvent`（#1668）；Emit `ExternalExecutionResultEvent` when external tool results resume（#2605）；Reconcile dangling `tool_use` blocks on interrupt（#2410） | tool suspension / 外部执行路径强化 |

背景：issue #2437 报告的根因是 2.0.0 的 AG-UI 适配器还在消费废弃的 v1 `Event` 流，`RequireUserConfirmEvent` 结构上到不了适配器；2.0.1 的 PR #2306（"Upgrade AG-UI module event mechanism"）解决了事件流问题，但**没有**补权限确认 converter（与 P0 实测一致）；PR #2495（2026-08-08 merged）在 converter-registry 架构上补齐，随 2.0.3 发布。

### 4.2 2.0.3 权限确认 interrupt 的实现形态（main 分支源码核实）

`PermissionConfirmEventConverter.java` + `AguiInterruptConstants.java`（GitHub main 分支，raw 源码读取）：

- `RequireUserConfirmEvent` → 每个 pending `ToolUseBlock` 一个 `AguiEvent.Interrupt`，由 `AgentLifecycleEventConverter` 在 `AgentEndEvent` 时汇入 `RUN_FINISHED` interrupt outcome；
- interrupt id 格式 **`replyId:toolCallId`**（resume 侧可还原身份）；
- `reason = "tool_call"`（`TOOL_CALL_INTERRUPT_REASON` 常量）；`responseSchema` 固定为 `{approved: boolean(required), editedArgs: object(full replacement)}`；
- metadata：`toolName` / `toolInput` / `toolContent`（参数的 JSON-object 字符串，用于重建非空 content 的 `ToolUseBlock`）/ `replyId` / **`agentscope.interruptKind: "permission_confirm"`**；
- `AguiResumeCoordinator` 按官方 resume 契约校验（同 thread、覆盖全部 open interrupts、无重复 id、同 thread 串行 run），确认类 interrupt 与 tool-suspension 类都纳入 pending 追踪；
- resume 转换：确认类 interrupt 的 resume payload → `ConfirmResult`（approved/denied），并用 `editedArgs` 全量重建 `ToolUseBlock` 的 input 与 content；非确认类 resume 保持 `ToolResultBlock` 路径（tool suspension）。

**即：`docs/technology/research/agentscope/agui.md` §HITL Interrupts 描述的行为（该文档是 AgentScope 官方文档快照）与 2.0.3 实现一致**——`agentscope.interruptKind`、`replyId:toolCallId`、`resume[] → ConfirmResult`、`editedArgs` 全量替换在 main 源码全部落位。P0 报告 grep 不到 `interruptKind`/`permission_confirm` 是因为查的是 2.0.1 源码。

### 4.4 端到端实测（2026-09-08，本项目升级 2.0.3 后）

升级已落地（`backend/build.gradle` 2.0.1 → 2.0.3），并以 `AguiInterruptIntegrationTest`（4 场景，MockMvc + 假 Model + 测试写工具，不打真实 LLM）实测验证：

| 场景 | 实测结果 |
| --- | --- |
| 写工具触发权限 ASK | ✅ `RUN_FINISHED.outcome.interrupts[]`：`reason:"tool_call"`、`metadata.agentscope.interruptKind:"permission_confirm"`、`toolName/toolInput`、`id=replyId:toolCallId`、`responseSchema{approved,editedArgs}`；工具未执行 |
| `resume[] approved:true` | ✅ 工具执行（服务端 state 落 `tool_result success`）+ Agent 续跑到最终文本 |
| `resume[] approved:false` | ✅ 工具被拒（DENIED 结果）+ Agent 续跑 |
| 无 open interrupt 时 `resume[]` | ✅ 仍返回 `AGUI_INTERRUPT_CONTRACT_ERROR`（P0 契约行为保持） |

**实测发现的补充行为（源码调研未覆盖）**：

1. **权限 ASK 的触发条件**：权限上下文为 trivial（`PermissionMode.DEFAULT` 且无任何规则，`PermissionContextState#isTrivial`）时走「pre-2.0 轻量路径」，只有工具**自身** `checkPermissions` 返回 ASK 才拦截。`@Tool(readOnly=false)` 注解生成的工具默认 passthrough → **直接放行，不 ASK**。生产 MCP 写工具不受影响：`McpTool#checkPermissions` 对非只读工具固定返回 ASK（与 Python 版一致）。若未来用 `@Tool` 注册写工具需注意，或给 agent 配置非 trivial 权限上下文走完整 `PermissionEngine`。
2. **resume 续跑轮不重放工具事件**：续跑流的 `startedToolCalls` 为空（`TOOL_CALL_START` 已在中断轮发过），`AguiStreamContext#hasStartedToolCall` 会抑制 `TOOL_CALL_RESULT`——前端审批卡片不能依赖 result 事件判断「已执行」，依据是 run 续跑完成 + 最终文本。
3. **2.0.3 starter 破坏性变更（升级适配点）**：`AguiRuntimeContextResolver`/`AguiRuntimeContextRequest` 从 starter 的 `io.agentscope.spring.boot.agui.common` 移到 core 的 `io.agentscope.core.agui.runtime`，`getNativeRequest()` 改为无参泛型返回（本项目 `InvestAguiRuntimeContextResolver` 已适配）；非法 JSON 请求不再走 MVC 绑定异常（`GlobalExceptionHandler` 不介入），改为 **HTTP 400 + SSE 流内 RAW 解析错误事件**（`AguiRequestBodyParser`，threadId/runId 回退 unknown）。
4. **`#2646`（RUN_ERROR 后不再补发 RUN_FINISHED）对本项目无影响**：幽灵 agent 用例实测仍通过（该路径的错误事件本就不含 RUN_FINISHED 断言冲突）。
5. 前端层：`@ag-ui/client 0.0.59` 实测 `RUN_FINISHED+outcome` → `agent.pendingInterrupts` 填充、`runAgent({resume})` 原样携带 `resume[]`；反代 `trimToLatestUserMessage` 对 resume 轮（`messages:[]`）与多消息轮均不破坏 resume 字段（`tests/agui-stream.test.ts`、`tests/lib/copilotkit-route.test.ts` 已钉住）。

### 4.3 P0 报告 blocker 的解决情况

| P0 发现（2.0.1） | 2.0.3 状态 |
| --- | --- |
| `RequireUserConfirmEvent` 无 converter → RAW 兜底 | 已修复：`PermissionConfirmEventConverter` |
| `RUN_FINISHED` 无 outcome（权限流） | 已修复：interrupt outcome 汇入 RUN_FINISHED |
| `resume[]` 对权限流返回 `AGUI_INTERRUPT_CONTRACT_ERROR` | 已修复：`AguiResumeCoordinator` 追踪确认类 interrupt |
| 真实续跑需 message metadata `agentscope_confirm_results`，但 `AguiMessage` 无 metadata 字段 | **被架构绕开**：resume payload 直接在 converter 层转 `ConfirmResult`，无需前端传 message metadata |
| `AguiMessageConverter` 丢弃 metadata | 同上，不再依赖该路径（metadata 通道问题本身未见专门修复，但 HITL 不再需要它） |

---

## 5. Agent 支持 HITL 的方案整理（D 部分）

### 5.1 方案矩阵

| # | 方案 | 原理 | 协议/框架依赖 | 优点 | 缺点 | 本项目可行性 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **前端工具**（client-side execution） | 前端把工具 schema 注入 `RunAgentInput.tools`；agent 发 `TOOL_CALL_*` 后 run 正常结束；前端执行（可先问用户）后，下一轮 messages 回传 tool message | AG-UI 协议原生；后端需支持 tools 注入与 run 结束语义 | 协议原生、无中断状态、审计走消息历史；天然适合「问用户要输入/确认」 | run 边界多一轮往返；悬空 tool call 会污染历史（必须应答）；后端需把前端工具临时并入 toolkit | **2.0.1 即可用**（agui.md §Frontend Tools：`ToolMergeMode` 默认 `MERGE_FRONTEND_PRIORITY`，run 级注入与清理）；2.0.3 修复 args fragment deltas。前端用 `useFrontendTool`/`useHumanInTheLoop` |
| 2 | **前端 action 暂停**（`renderAndWaitForResponse`） | CopilotKit 对方案 1 的封装：注册前端 action，`render` 组件调 `respond()` 前挂起；respond 结果即 tool 结果 | CopilotKit 特有（v1 `useCopilotAction` / v2 `useHumanInTheLoop`），底层仍是方案 1 | 声明式 UI、状态机与生命周期托管、inline chat 渲染 | 与方案 1 同构，多了框架绑定；v1→v2 API 迁移成本 | **1.70.1 直接可用**；v2 推荐 `useHumanInTheLoop`（render props：`args/status/respond/result`） |
| 3 | **服务端中断 + resume**（interrupt outcome + `resume[]`） | 后端在权限 ASK/工具决断点 emit `RUN_FINISHED.outcome.interrupt`；前端渲染审批 UI，下一轮带 `resume[]` | AG-UI 协议原生（schema ≥0.0.54；行为 1.0 draft）；**依赖后端实现完整度** | 语义最强（服务端权威暂停）、支持 approve-with-edits、responseSchema 驱动表单、多 interrupt；capabilities 可声明 | 后端实现门槛高；pending interrupt 状态需服务端跟踪（单实例内存即可，多实例需共享）；draft 未批准存在措辞变动风险 | **后端升级 2.0.3 后可用**（4.2 已核实实现）；前端 `useInterrupt`（1.70.1 已有，`resolve({approved:true})`/`cancel()`）。**这是权限确认场景的推荐路径** |
| 4 | **工具挂起/外部执行**（tool suspension） | 工具抛 `ToolSuspendException`/外部执行挂起 → `GenerateReason.TOOL_SUSPENDED` → tool_call interrupt；resume `payload` 转 `ToolResultBlock`（外部执行结果） | AgentScope 特有路径 + AG-UI tool_call interrupt | 适合「工具由外部系统/人执行」场景（人就是执行器）；2.0.1 已可用（P0 实测 resume[] 仅此流有效）；2.0.3 强化（#1668/#2605） | 与方案 3 共用 interrupt 通道但语义不同（结果回传 vs 批准）；前端需区分（靠 metadata `agentscope.interruptKind` 或无 kind） | 可行；适合把「写库/下单」类操作改造成外部执行（前端执行后回传结果） |
| 5 | **绕过：readOnly=true 强制放行** | MCP 工具注册为 `readOnly=true` → 权限引擎判 ALLOW，不触发 ASK | AgentScope 权限系统（`ToolBase.checkPermissions` 之上，`readOnly` 元数据） | 零改造（现状）；无交互摩擦 | **无 HITL**：写操作被谎标只读，安全语义破坏；权限规则对危险路径的非绕过检查仍可能触发 ASK | 仅作为 2.0.3 升级前的过渡（现状，见 `UserToolkitFactory`） |
| 6 | （补充）**State-based 协同** | `STATE_SNAPSHOT`/`STATE_DELTA` 共享状态，用户改 state 随下一轮回传 | AG-UI 协议原生；后端需启用 state events（`emit-state-events: true`，项目已开） | 弱 HITL：纠偏/协同编辑，不阻塞 run | 非确认机制；需自行设计状态协议 | 可作辅助（如展示 agent 进度/计划并允许用户改参数），非审批替代 |
| 7 | （补充）**权限规则白名单 / DONT_ASK** | `PermissionRule(ALLOW)` 静态放行安全工具；`DONT_ASK` 把 ASK 降级 DENY | AgentScope 权限系统 | 免交互治理；规则可从 `ConfirmResult` 建议规则沉淀（session 级） | 不是交互式 HITL；DENY 后 agent 只看到拒绝 | 与方案 3 配合：常用安全操作白名单化，敏感操作走 interrupt |

另有 CopilotKit 的 governed-actions（3.5）属方案 3 的 UI 定式，不单列。

### 5.2 本项目推荐路径

**主路径：升级 AgentScope 2.0.1 → 2.0.3，启用方案 3（权限确认 interrupt）替代方案 5。**

1. **后端**：`backend/build.gradle` 将 `agentscope-*` 2.0.1 → 2.0.3；移除 `UserToolkitFactory` 的 `readOnly=true` 强制（恢复 MCP 工具真实读写语义）；确认 Spring 配置（starter 注册端点不变，`/agui/run` 契约不变）。
2. **前端**（零依赖升级，1.70.1 已备齐）：
   - 在 ThreadArea（headless）用 `useInterrupt({renderInChat: false, render: ({interrupt, resolve, cancel}) => ...})` 渲染审批卡片：读 `interrupt.metadata.toolName` / `toolInput`（或 `message`），按钮 `resolve({approved: true})` / `resolve({approved: false})` / `cancel()`；如 `metadata["agentscope.interruptKind"] === "permission_confirm"` 走权限确认 UI，否则按 tool suspension（外部执行）UI 处理；
   - `responseSchema` 已固定 `{approved, editedArgs?}`，可选支持编辑参数（`editedArgs` 全量替换）；
   - resume 提交、全覆盖校验由 hook 自动完成。
3. **反代检查**（`app/api/copilotkit/[[...slug]]/route.ts`）：`trimToLatestUserMessage` 对 resume 轮的影响——AgentScope resume 请求 `messages: []`（agui.md 示例），裁剪逻辑 `messages.length <= 1` 直接原样透传，无需改动；`resume` 字段位于 `RunAgentParameters`，不经 messages 裁剪路径。升级后应用 P0 的 curl 场景回归验证。
4. **配套**：方案 7（权限规则）做长期治理——首次审批时可考虑让后端带 suggested rules（AgentScope `ConfirmResult.rules`），沉淀为 ALLOW 规则减少打扰（前端 resume payload 只需 approved/editedArgs，规则由后端侧处理）。
5. **方案 1/2**：用于「agent 主动问用户要结构化输入」的增量场景（如确认调仓参数），`useHumanInTheLoop` 注册 `confirm_trade` 类前端工具即可，与方案 3 互补（Overview 的官方分工：LLM 发起 → useHumanInTheLoop；运行时强制 → useInterrupt）。

**风险与验证点**（✅ 2026-09-08 已随 2.0.3 升级实测，见 §4.4 与 `AguiInterruptIntegrationTest`）：

- ~~2.0.3 的 `PermissionConfirmEventConverter` 行为需 end-to-end 确认~~ ✅ 已验证：4 场景集成测试全绿（假 Model + 写工具，覆盖 `/agui/run` SSE → interrupt outcome → resume → ConfirmResult → 续跑）；前端 `useInterrupt` 渲染层留待 HITL UI 立项时验证；
- `AguiResumeCoordinator` 的 pending interrupts 为服务端内存态（`ConcurrentMap`），单实例部署无问题；未来多实例需注意（源码已按 thread 串行 run 设计）；
- ~~CopilotRuntime 对 `RUN_FINISHED.outcome` 的代理透传未单测~~ ✅ 已夹逼覆盖：客户端层（`pendingInterrupts`/`runAgent({resume})` 请求体）+ 反代层（resume 不被 `trimToLatestUserMessage` 破坏）均已钉住测试；中间 CopilotRuntime 为上游代码不做单测。

---

## 6. 参考来源

### AG-UI 协议（官方文档与 spec）

- Interrupts 概念页（HITL 核心）：https://docs.ag-ui.com/concepts/interrupts
- spec draft：Interrupts and Resume pattern：https://docs.ag-ui.com/spec/draft/basic/patterns/interrupt-resume.md
- spec draft：Run Input（resume 字段契约）：https://docs.ag-ui.com/spec/draft/basic/run-input.md
- spec draft：Runs and Steps（RUN_FINISHED/outcome/RUN_ERROR）：https://docs.ag-ui.com/spec/draft/events/lifecycle.md
- spec draft：Tool Calls（frontend tools 往返）：https://docs.ag-ui.com/spec/draft/events/tool-calls.md
- spec draft：Key Changes（1.0 vs 0.x，含 draft 未批准警告）：https://docs.ag-ui.com/spec/draft/changelog.md
- Events 概念页（事件全集，无 RUN_INTERRUPTED）：https://docs.ag-ui.com/concepts/events
- Tools 概念页（HITL Workflows、tool message 回传）：https://docs.ag-ui.com/concepts/tools
- State 概念页（Human-in-the-Loop Collaboration）：https://docs.ag-ui.com/concepts/state
- Capabilities 概念页（humanInTheLoop.* 标志）：https://docs.ag-ui.com/concepts/capabilities
- Metadata 概念页：https://docs.ag-ui.com/concepts/metadata
- What's New：https://docs.ag-ui.com/development/updates
- 文档索引：https://docs.ag-ui.com/llms.txt

### AG-UI 协议（GitHub / npm）

- 仓库：https://github.com/ag-ui-protocol/ag-ui
- PR #1555 interrupt-aware run lifecycle 提案：https://github.com/ag-ui-protocol/ag-ui/pull/1555
- PR #1569 TS+Python core SDK 实现：https://github.com/ag-ui-protocol/ag-ui/pull/1569
- PR #1945 LangGraph 结构化 interrupt/resume（opt-in）：https://github.com/ag-ui-protocol/ag-ui/pull/1945
- npm @ag-ui/core 版本时间线与 0.0.53/0.0.54 tarball 比对：https://registry.npmjs.org/@ag-ui%2Fcore
- 本地实测源码：`frontend/node_modules/.pnpm/@ag-ui+core@0.0.59`（`RunFinishedOutcomeSchema`/`ResumeEntrySchema`/`HumanInTheLoopCapabilities`）、`@ag-ui+client@0.0.59`（`RunAgentParameters.resume`）

### CopilotKit（官方文档）

- HITL Overview（两种模式归纳）：https://docs.copilotkit.ai/human-in-the-loop
- useInterrupt 深度页（standard vs legacy flow、resolve/cancel、multi-interrupt）：https://docs.copilotkit.ai/human-in-the-loop/useInterrupt
- Headless Interrupts（agent.subscribe + copilotkit.runAgent 原语）：https://docs.copilotkit.ai/human-in-the-loop/headless
- Governed Action Approval UI：https://docs.copilotkit.ai/human-in-the-loop/governed-actions
- v2 useHumanInTheLoop reference：https://docs.copilotkit.ai/reference/v2/hooks/useHumanInTheLoop
- v1 useCopilotAction reference（renderAndWaitForResponse）：https://docs.copilotkit.ai/reference/v1/hooks/useCopilotAction
- v1 useCoAgent reference：https://docs.copilotkit.ai/reference/v1/hooks/useCoAgent
- Which Hook for Which Job（v2 hooks 全景）：https://docs.copilotkit.ai/concepts/which-hook
- Self-managed agents（runtime 角色与绕过）：https://docs.copilotkit.ai/backend/self-managed-agents
- Copilot Runtime：https://docs.copilotkit.ai/backend/copilot-runtime
- Chat Components：https://docs.copilotkit.ai/agentic-chat-ui
- 文档索引：https://docs.copilotkit.ai/llms.txt
- 本地实测源码：`frontend/node_modules/.pnpm/@copilotkit+react-core@1.70.1`（`ActionRenderPropsWait`、`useInterrupt`/`useHumanInTheLoop` v2 导出）、`@copilotkit+runtime@1.70.1`（`InterruptSignal`、resume 匹配类型）

### AgentScope Java（GitHub）

- Releases（v2.0.3 2026-09-07，AG-UI Fixed 段）：https://github.com/agentscope-ai/agentscope-java/releases
- PR #2495 fix(agui): emit AG-UI interrupt for permission-type HITL：https://github.com/agentscope-ai/agentscope-java/pull/2495
- Issue #2437（2.0.0 根因报告）：https://github.com/agentscope-ai/agentscope-java/issues/2437
- main 源码：`PermissionConfirmEventConverter.java`、`AguiInterruptConstants.java`、`AguiResumeCoordinator.java`（经 GitHub contents API 读取）

### 本项目内部文档

- `docs/technology/research/agentscope/agui.md`（AgentScope AG-UI 文档快照 + 2.0.1 版本警告，第 264-266 行）
- `docs/technology/research/agentscope/permission-system.md`（权限系统：ALLOW/DENY/ASK、ConfirmResult、规则沉淀）
- `docs/technology/decisions/0006-frontend-copilotkit.md`（前端 CopilotKit v2 选型）
- `.superpowers/sdd/mcp-hitl/P0-report.md`（2.0.1 实测：RAW interrupt、resume 契约错误、AguiMessage metadata blocker）
- `frontend/tests/agui-stream.test.ts`（前端事件消费基线）、`frontend/app/api/copilotkit/[[...slug]]/route.ts`（runtime 反代）
