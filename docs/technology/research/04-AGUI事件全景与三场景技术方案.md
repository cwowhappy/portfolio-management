# AG-UI 事件全景与 CopilotKit × AgentScope 三场景技术方案

- 调研日期：2026-09-09 ~ 09-10
- 版本基线：前端 `@ag-ui/client`/`@ag-ui/core` **0.0.59**（npm `latest`，2026-08-27 发布）+ `@copilotkit/react-core`/`@copilotkit/runtime` **1.70.1**（**v2 API** 子路径 `/v2`，本项目所用）；后端 `io.agentscope:agentscope-agui-spring-boot-starter` **2.0.3**
- 调研方式：三路独立取证后交叉汇总——①本地 `frontend/node_modules` 类型定义（.d.ts）+ 运行时 zod schema dump + 构建产物（.mjs）反编译；②本地 2.0.3 jar `javap` 反编译（字段级）+ 仓库后端集成代码与集成测试；③官方文档（docs.ag-ui.com / docs.copilotkit.ai / AgentScope GitHub）与官方示例仓库原文。每个结论标注一手出处。
- 姊妹文档：HITL 中断机制深度调研见 [copilotkit/agui-hitl.md](copilotkit/agui-hitl.md)（2026-09-08，经本次本地源码抽查仍一致）——本文 HITL 场景只做结论引用与方案矩阵，不重复展开。

---

## 0. TL;DR

1. **协议层**：AG-UI 0.0.59 定义 **36 个 EventType**（16 经典 + 3 个 CHUNK 便利事件 + `TOOL_CALL_RESULT` + 7 REASONING + 3 SUBAGENT + 5 已废弃 THINKING_*）；AgentScope 2.0.3 实装 **28 个** = 16 经典全覆盖 + 12 官方新增提前对齐。**缺口**：SUBAGENT_* 原生事件（降级为 `CUSTOM` 的 `subagent.*` 命名空间）、draft 的 META 事件。
2. **方向性**：AG-UI 是**单向事件流**协议——所有事件都是 agent→client（HTTP SSE）；client→agent 只有一次 `RunAgentInput` POST。前端工具结果、HITL 决断都通过**下一轮 run 的输入**（tool 消息 / `resume[]`）回传；`TOOL_CALL_RESULT` 只承载**后端已执行**工具的结果，方向最容易搞错。
3. **CopilotKit 消费面**：1.70.1 v2 直接构建在 `@ag-ui/client` 的 `AbstractAgent` 之上（不自建事件循环）；`@copilotkit/runtime` 是透明 SSE 代理（zod 校验后透传，不改写事件语义）。
4. **AgentScope 死配置**：2.0.3 中 `STATE_SNAPSHOT`/`STATE_DELTA`/`STEP_*` 事件类存在但**全链路无发射方**（`emit-state-events` 配置无人消费）；`ACTIVITY_*`、`input_required` interrupt 也无内置产出。前端（1.70.1）协议能力齐全，后端要用需自定义 `AgentEventConverter`（Spring bean 自动收集）。
5. **三场景结论**：
   - **HITL**：审批（运行时发起）已落地 = `useInterrupt` × AgentScope 权限确认 interrupt（issue #25）；追问（LLM 发起）用 `useHumanInTheLoop`（前端工具）。官方分工原文：agent-initiated → `useHumanInTheLoop`；runtime-paused → `useInterrupt`。
   - **富文本**：Markdown 表格开箱即用（GFM）；图表 = `useRenderTool`/`useComponent` 注册 React 组件（协议走 TOOL_CALL 事件，**后端零改动**）；文件上传 = `useAttachments` 多模态消息，但 AgentScope 2.0.3 有 document 类型缺口 + 多轮回放丢附件；文件下发无专用事件，走工具结果带 URL。
   - **问答**：headless 模式（`agent.addMessage` + `copilotkit.runAgent`）完全对齐官方推荐；多轮上下文 = server-side-memory 自管线程（官方认可路径），风险是前端回灌精简历史与服务端完整历史口径不一致。

---

## 一、AG-UI 协议事件全景（@ag-ui/core 0.0.59）

### 1.1 传输模型与公共字段

- **单向事件流**：所有事件 agent→client，经 SSE 响应体（`text/event-stream`）以 `data: {JSON}\n\n` 帧传输（runtime 侧由 `@ag-ui/encoder` 的 `EventEncoder` 编码，`frontend/node_modules/@copilotkit/runtime/dist/v2/runtime/handlers/shared/sse-response.mjs:10-124`）。client→agent 没有事件，只有 `RunAgentInput` POST（§1.4）。
- **BaseEvent 公共字段**（`@ag-ui/core` 0.0.59 `dist/index.d.ts:4455-4470`）：

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| `type` | `EventType` 枚举 | 是 | 事件类型判别键 |
| `timestamp` | `number` | 否 | 时间戳 |
| `rawEvent` | `any` | 否 | 原始上游事件透传 |
| `metadata` | `Record<string, any>` | 否 | 开放元数据，按 key 合并进消息、后写覆盖前写 |

- 多数事件另带可选 `subagentRunId?: string`（归因到产生该事件的 subagent 调用；RUN_*/STEP_*/THINKING_START/END 除外）。

### 1.2 EventType 全集（36 个，按族分组）

出处：`@ag-ui/core` 0.0.59 `dist/index.d.ts:4402-4454` + 运行时逐 `*EventSchema` 的 zod shape dump。与官方文档 [concepts/events](https://docs.ag-ui.com/concepts/events) 当前版逐一对齐，无版本差异。

#### 生命周期（5）

| EventType | 语义 | 专有字段 |
|---|---|---|
| `RUN_STARTED` | run 开始（强制首事件） | `threadId`*、`runId`*、`parentRunId?`、`input?: RunAgentInput`（0.0.59 已实装：服务端回显本次 run 完整输入，1.0 draft "RunStarted (Extended)" 提前落位） |
| `RUN_FINISHED` | run 正常结束（与 RUN_ERROR 二选一） | `threadId`*、`runId`*、`result?`、`outcome?: {type:"success"} \| {type:"interrupt", interrupts: Interrupt[]}`（省略 = legacy 正常完成）、`usage?: TokenUsage[]` |
| `RUN_ERROR` | 不可恢复错误（此后本 run 无后续事件） | `message`*、`code?`、`usage?` |
| `STEP_STARTED` | 步骤/节点开始（可选） | `stepName`* |
| `STEP_FINISHED` | 步骤结束 | `stepName`* |

#### 文本消息（4）

| EventType | 语义 | 专有字段 |
|---|---|---|
| `TEXT_MESSAGE_START` | 文本消息开始 | `messageId`*、`role?`（default `"assistant"`）、`name?` |
| `TEXT_MESSAGE_CONTENT` | 流式增量 | `messageId`*、`delta`* |
| `TEXT_MESSAGE_END` | 定稿 | `messageId`* |
| `TEXT_MESSAGE_CHUNK` | 便利事件：**客户端流变换器自动展开为 Start→Content→End 三连**（首 chunk 须带 messageId） | `messageId?`、`delta?`、`role?`、`name?` |

#### 工具调用（5）

| EventType | 语义 | 专有字段 |
|---|---|---|
| `TOOL_CALL_START` | 工具调用开始（agent 的调用提案，含前端工具） | `toolCallId`*、`toolCallName`*、`parentMessageId?` |
| `TOOL_CALL_ARGS` | 参数 JSON 流式增量（拼接后 parse） | `toolCallId`*、`delta`* |
| `TOOL_CALL_END` | 参数传输完毕 | `toolCallId`* |
| `TOOL_RESULT`（= `TOOL_CALL_RESULT`） | **后端已执行**工具的结果回告（agent→client） | `messageId`*、`toolCallId`*、`content`*、`role?`（default `"tool"`）。⚠️ 前端工具的结果**不走此事件**（见 §2.3） |
| `TOOL_CALL_CHUNK` | 便利事件（自动展开 Start→Args→End） | `toolCallId?`、`toolCallName?`、`parentMessageId?`、`delta?` |

#### 状态（3）

| EventType | 语义 | 专有字段 |
|---|---|---|
| `STATE_SNAPSHOT` | agent state 全量替换 | `snapshot`*（整体替换而非合并） |
| `STATE_DELTA` | RFC 6902 JSON Patch 增量 | `delta: patch 操作数组`* |
| `MESSAGES_SNAPSHOT` | 消息历史全量替换 | `messages: Message[]`*（`activity`/`reasoning` 角色按 all-or-nothing 处理） |

#### Activity（2）/ 特殊（2）

| EventType | 语义 | 专有字段 |
|---|---|---|
| `ACTIVITY_SNAPSHOT` | 结构化活动消息（聊天轮之间的进行时活动，如生成式 UI 进度） | `messageId`*、`activityType`*、`content`*、`replace?`（default `true`） |
| `ACTIVITY_DELTA` | 活动内容 JSON Patch 增量 | `messageId`*、`activityType`*、`patch`* |
| `RAW` | 外部系统事件透传容器 | `event`*、`source?` |
| `CUSTOM` | 应用自定义事件（协议内合法扩展点） | `name`*、`value`* |

#### Reasoning（7，0.0.45+）/ Subagent（3，0.0.57+）/ 已废弃（5）

| EventType | 语义 / 专有字段 |
|---|---|
| `REASONING_START` / `REASONING_END` | 推理段开始/结束（`messageId`*） |
| `REASONING_MESSAGE_START` / `_CONTENT` / `_END` / `_CHUNK` | 可见推理消息（`messageId`*、role 字面量 `"reasoning"`、`delta`*）；CHUNK 同为便利事件 |
| `REASONING_ENCRYPTED_VALUE` | 加密推理项（零数据保留策略下跨轮状态携带，客户端只透传不解密）：`subtype: "tool-call"\|"message"`*、`entityId`*、`encryptedValue`* |
| `SUBAGENT_STARTED` | 子代理调用开始：`subagentRunId`*、`name`*、`description?`、`parentSubagentRunId?`、`parentToolCallId?`、`parentMessageId?` |
| `SUBAGENT_FINISHED` | 子代理调用完成：`subagentRunId`*、`result?`、`outcome?`（success \| suspended+`interruptIds`） |
| `SUBAGENT_ERROR` | 子代理失败：`subagentRunId`*、`message`*、`code?` |
| `THINKING_*`（5 个） | **已废弃**，d.ts 逐个标注 "Will be removed in 1.0.0"；`@ag-ui/client` 内置 `BackwardCompatibility_0_0_45` 中间件自动映射为 REASONING_* |

### 1.3 Interrupt / TokenUsage 结构

```typescript
// RUN_FINISHED.outcome.type === "interrupt" 时的载荷（@ag-ui/core 0.0.59 InterruptSchema）
type Interrupt = {
  id: string;                  // 跨 interrupt/resume/幂等/审计的关联键
  reason: string;              // core 值：tool_call / input_required / confirmation；开放扩展（建议 "<framework>:<name>"）
  message?: string;            // 人类可读提示，通用兜底 UI 内容
  toolCallId?: string;         // reason="tool_call" 时必须设置
  responseSchema?: JsonSchema; // resume.payload 的 JSON Schema（驱动表单）
  expiresAt?: string;          // ISO 时间；过期后 resolved 非法（仍可 cancelled）
  metadata?: Record<string, any>;  // 框架自有数据（开放）
  subagentRunId?: string;
};

// TokenUsage：{ provider?, model?, inputTokens?, outputTokens?, totalTokens?, reasoningTokens?, cachedInputTokens? }（全 optional）
```

resume 契约要点（8 条完整版见 [copilotkit/agui-hitl.md](copilotkit/agui-hitl.md) §2.2）：同 thread；`resume[].interruptId` 须指向被中断 run 的 interrupt `id`；**一次覆盖全部 open interrupts**；pending interrupt 阻塞普通新输入；幂等可重放；过期后不得 resolved；`resolved` 的拒绝表达在 payload（如 `{approved:false}`），`cancelled` = 用户放弃。

### 1.4 client→agent：RunAgentInput

出处：`RunAgentInputSchema`（0.0.59 运行时 dump）。

```typescript
type RunAgentInput = {
  threadId: string;        // 必填
  runId: string;           // 必填（客户端 uuid；resume 轮由 CopilotKit 钉住）
  parentRunId?: string;
  state?: any;             // agent 共享状态（客户端当前全量随每轮回传）
  messages: Message[];     // 必填，完整消息历史
  tools: Tool[];           // 必填（空=无）。只放【前端工具】；后端自有工具不从这里下发
  context: Context[];      // 必填（可空）：{ description, value }[]
  forwardedProps: any;     // 必填（可空对象）。自由透传通道
  resume?: ResumeEntry[];  // 0.0.54+：应答上一 run 的 open interrupts
};
type Tool = { name: string; description: string; parameters: any /* JSON Schema */ };
type ResumeEntry = { interruptId: string; status: "resolved" | "cancelled"; payload?: any; metadata?: Record<string, any> };
```

### 1.5 Message 判别联合（7 种 role）

| role | 形状要点 |
|---|---|
| `developer` / `system` | `{ id, role, content: string, name?, ... }` |
| `assistant` | `{ id, role, content?: string, toolCalls?: ToolCall[], ... }`；`ToolCall = { id, type:"function", function: { name, arguments: string /* JSON 字符串 */ } }` |
| `user` | `{ id, role, content: string \| InputPart[] }`——多模态：text / image / audio / video / document part，source 为 `data`(base64)/`url` 判别联合（[drafts/multimodal-messages](https://docs.ag-ui.com/drafts/multimodal-messages)，状态 Implemented 2025-10-16） |
| `tool` | `{ id, content: string, role:"tool", toolCallId, error? }`——**前端工具结果、HITL 决断都以此形态回传** |
| `activity` | `{ id, role:"activity", activityType, content }`——客户端持有，**不回传 agent**（`@ag-ui/client` prepareRunAgentInput 实测过滤） |
| `reasoning` | `{ id, role:"reasoning", content, encryptedValue? }` |

### 1.6 能力发现：AgentCapabilities

`@ag-ui/core` 0.0.59 已定义完整 `AgentCapabilities` schema：identity / transport / tools / output / state / multiAgent / reasoning / multimodal / execution / **humanInTheLoop**（`supported`/`approvals`/`interventions`/`feedback`/`interrupts`/`approveWithEdits`）/ custom。前端 `useCapabilities(agentId?)` 读取（1.70.1 `dist/v2/headless.d.mts:906-921`）；可据此决定渲染哪种审批 UI。

---

## 二、CopilotKit 对 AG-UI 的消费与发射（1.70.1，v2 API）

> 本项目前端走 v2 API（`frontend/app/providers.tsx`、`app/api/copilotkit/[[...slug]]/route.ts`）；v1 hooks（useCopilotAction/useCoAgent 等）保留于 `src/v1-deprecated/`，是 v2 之上的兼容桥接。新代码一律 v2。

### 2.1 发起 run：前端状态/动作如何编码进 RunAgentInput

调用链（`@copilotkit/core` 1.70.1 `dist/index.mjs:2766-2826` + `@ag-ui/client` prepareRunAgentInput 反编译验证）：

```
UI (sendMessage / useAgent.start)
  → copilotkit.runAgent({ agent, forwardedProps?, resume?, runId? })      // CopilotKit 层
      { forwardedProps: {...core.properties, ...forwardedProps},          // ★ <CopilotKit properties> 合并
        resume?, runId?,                                                  // ★ HITL 应答
        tools: buildFrontendTools(agentId),                               // ★ 前端工具 schema
        context: getContextForAgent(agentId) }                            // ★ useAgentContext 上下文
  → agent.runAgent(input, subscriber)   // @ag-ui/client 层
      { threadId, runId, state: agent.state,                              // ★ useAgent 共享状态（上行）
        messages: agent.messages（过滤 activity）, tools, context, forwardedProps, resume }
  → HTTP POST（HttpAgent 直连 / 经 runtime 代理）
```

- **tools**：`useFrontendTool`/`useHumanInTheLoop`/`useCopilotAction`/`useComponent` 注册的动作 → `{ name, description, parameters }`；`handler` 只留在前端执行，不发往后端。
- **state**：UI 侧 `agent.setState()` 写入，下一轮 run 原样回传（双向同步的上行半边）。
- **context**：`ContextStore` 按 agentId 过滤后注入。
- **messages**：用户发消息 = `agent.addMessage({ role:"user", content })` → `copilotkit.runAgent({agent})`。

### 2.2 消费事件流：事件 → UI 状态映射总表

消费框架 = `@ag-ui/client` 的 `AbstractAgent` + `defaultApplyEvents` + `AgentSubscriber`（CopilotKit 不自建事件循环）。管道：SSE 解析 → zod `EventSchemas` 校验 → `transformChunks`（CHUNK 展开为三连）→ `BackwardCompatibility_*` 中间件（按远端 maxVersion 插桩）→ 事件先分发给订阅者钩子（可 `stopPropagation` 拦截）→ 默认状态应用。

| AG-UI 事件 | 消费点 | UI 效果 |
|---|---|---|
| `TEXT_MESSAGE_*` | agent.messages → onMessagesChanged | 聊天消息流式渲染 |
| `TOOL_CALL_START/ARGS/END` | assistant 消息的 toolCalls → onMessagesChanged | tool render 组件 **InProgress** 态（参数流式可见） |
| `TOOL_CALL_RESULT` | 插入 role:"tool" 消息 → onMessagesChanged | render 组件转 **Complete**（result 可用） |
| `STATE_SNAPSHOT` / `STATE_DELTA` | agent.state 替换 / fast-json-patch `applyPatch` → onStateChanged | `useAgent().agent.state` 更新 |
| `MESSAGES_SNAPSHOT` | messages 整体替换 | 聊天历史重建 |
| `RUN_STARTED` | isRunning=true；useInterrupt 清空 pending | loading 态；旧中断卡消失 |
| `RUN_FINISHED`(success) | isRunning=false；pendingInterrupts 清空 | 结束态 |
| `RUN_FINISHED`(interrupt) | `agent.pendingInterrupts` 填充；useInterrupt 捕获 | 审批/中断卡片（resolve/cancel） |
| `RUN_ERROR` | onRunErrorEvent → onError 订阅 | 错误 UI（`code==="abort"` 静默） |
| `STEP_STARTED/FINISHED` | v1 useAgentNodeName | `useCoAgent().nodeName`（LangGraph 节点名） |
| `CUSTOM` | onCustomEvent；name==="on_interrupt" 走 legacy interrupt | legacy 中断卡片 |
| `ACTIVITY_*` | ActivityMessage | 生成式 UI 活动卡 |
| `REASONING_*` | ReasoningMessage → onMessagesChanged | 推理过程折叠渲染 |
| `SUBAGENT_*` | subagentRunId 归因 | 多代理流标签 |
| `RAW` | onRawEvent | 应用自定义处理 |

React 绑定细节：v2 `useAgent({updates})` 只订阅**白名单回调**（`SUBSCRIBE_TO_AGENT_KEYS = ["onMessagesChanged","onStateChanged","onRunInitialized","onRunFinalized","onRunFailed","onRunErrorEvent"]`，`@copilotkit/core/dist/index.d.mts:1945-1982`——细粒度事件回调被有意排除，throttle 封装无法安全中转 `stopPropagation`）→ queueMicrotask 批量 forceUpdate；UI 直读 `agent.messages` / `agent.state` / `agent.isRunning`。`useAgent({throttleMs})` 节流（leading+trailing），本项目 150ms 用法与官方语义一致。

### 2.3 前端工具执行与 follow-up：TOOL_CALL_* 的关键语义

**前端工具结果不通过事件回传**（client 无法向 SSE 流发事件），而是「补 tool 消息 + 发起新 run」（`@copilotkit/core` `RunHandler.processAgentResult`，`dist/index.mjs:2846-2905` 实测）：

1. run 结束后遍历新 assistant 消息的 `toolCalls`，按 name 匹配已注册 FrontendTool（精确 → 通配 `"*"`）；
2. 执行 handler（前后发 `onToolExecutionStart/End`），结果字符串化；
3. 向 `agent.messages` 插入 `{ id: uuid, role:"tool", toolCallId, content }`；
4. `tool.followUp !== false` 且未 abort → **递归 `runAgent()`**（MAX_FOLLOW_UP_DEPTH 限深），新 run 的 messages 携带 tool 消息 → LLM 看到结果继续推理。

**render 组件状态机**（`ToolCallRenderer`，`react-core/dist/copilotkit-DiUK2Bhq.mjs:1433-1460`）：

- `InProgress`：TOOL_CALL_END 已到、无 tool 消息、handler 未执行（等待用户，HITL 场景）；
- `Executing`：前端 handler 运行中（含 HITL「卡片等用户点按钮」的整个窗口）；
- `Complete`：messages 出现匹配 `toolCallId` 的 tool 消息（后端 TOOL_CALL_RESULT 或前端插入），`result` 可读。

**HITL 两条路径**（细节见 [agui-hitl.md](copilotkit/agui-hitl.md) §3）：`useHumanInTheLoop`（tool-based，LLM 发起，走上述 follow-up）与 `useInterrupt`（interrupt-based，后端发起：`onRunFinishedEvent(outcome==="interrupt")` 捕获 → accumulate-then-submit：全部 open interrupt 应答齐后自动组装 `resume[]` 经 `copilotkit.runAgent` 提交；`reason==="tool_call"` 且带 `toolCallId` 时同时补 tool 结果消息；`expiresAt` 过期拒绝 resume）。`@ag-ui/client` 的 `onInitialize` 在有 `pendingInterrupts` 时**硬约束**新 run 的 `resume[]` 必须全覆盖且无过期，否则抛 `AGUIError`。

### 2.4 CopilotRuntime 的角色：透明 AG-UI 代理

- 端点：`POST {basePath}/agent/{agentId}/run`、`/connect`、`/stop`、`/info`（agents 发现 + capabilities）。
- run 处理（`runtime/dist/v2/runtime/handlers/handle-run.mjs:19-53`）：`RunAgentInputSchema.parse(requestBody)`（zod 严格校验——本项目反代 `normalizeNullContent` 正为过这一关）→ `agent.setMessages/setState` → SSE 逐事件透传（`EventEncoder`）。**不改写事件语义**。
- 本项目路径：Next.js `/api/copilotkit` → `CopilotRuntime({agents:{invest: HttpAgent(url: /agui/run)}})` 注入 Cookie + 裁剪历史 → 后端。可选开启 A2UI/MCP-Apps/OpenGenerativeUI 中间件（富文本场景见 §4.2）。

---

## 三、AgentScope 2.0.3 的 AG-UI 事件体系

### 3.1 三层事件架构

```
ReActAgent/HarnessAgent 内部事件总线          AG-UI 协议事件                 SSE 线格式
io.agentscope.core.event.AgentEvent  ─转换器→  io.agentscope.core.agui.  ──→  data:{json}\n\n
（AgentEventType 枚举 31 值）                 event.AguiEvent（28 种）
                                             @JsonTypeInfo(property="type")
                                             @JsonInclude(NON_NULL)
```

- 内部事件 31 种（`AgentEventType`：AGENT_START/END/RESULT、MODEL_CALL_*、TEXT_BLOCK_*、THINKING_BLOCK_*、DATA_BLOCK_*、TOOL_CALL_*、TOOL_RESULT_*、**REQUIRE_USER_CONFIRM / USER_CONFIRM_RESULT**、REQUIRE_EXTERNAL_EXECUTION / EXTERNAL_EXECUTION_RESULT、REQUEST_STOP、SUBAGENT_EXPOSED、HINT_BLOCK、ALL_TOOLS_DENIED、CUSTOM 等）。〔jar: `io.agentscope.core.event.AgentEventType`〕
- 转换器 SPI：`AgentEventConverter.eventTypes()/convert(AgentEvent, AguiStreamContext)`，注册于 `AgentEventConverterRegistry`；未匹配事件兜底转 `RAW`。**用户自定义 converter/enricher 声明为 Spring bean 即自动收集**（`@Order` 生效）。
- ⚠️ 协议类不在 starter jar：`io.agentscope.core.agui.*` 位于聚合 jar `io.agentscope:agentscope:2.0.3`（starter 经 `agentscope-spring-boot-starter` 传递引入）。

### 3.2 事件总表（28 种，字段级）

出处：本地 jar `javap` 反编译 `io.agentscope.core.agui.event.AguiEvent$*`。所有 record 另有公共可选尾参 `timestamp: Long`、`rawEvent: Object`，且每类都带 `threadId`/`runId` 组件。与官方协议对照：**16 经典事件全覆盖 + 12 官方新增提前对齐**（2.0.0-RC5 "Align AguiEvent with the AG-UI protocol spec" #1862）；**未实现**：SUBAGENT_* 原生事件（子代理事件转 `CUSTOM`，name = `subagent.lifecycle` / `subagent.text` / `subagent.thinking` / `subagent.tool_call` / `subagent.tool_result` / `subagent.require_confirm`，payload 至少含 `source`+`type`）与 draft META。

| EventType | record（`AguiEvent$`） | 专有字段（threadId/runId 之外） |
|---|---|---|
| `RUN_STARTED` | `RunStarted` | parentRunId, input:RunAgentInput（回显完整输入） |
| `RUN_FINISHED` | `RunFinished` | result:Object, outcome:RunFinishedOutcome（success / interrupt+interrupts） |
| `RUN_ERROR` | `RunError` | message, code（TIMEOUT_ERROR / INTERRUPTED_ERROR / INVALID_INPUT_ERROR / INTERNAL_ERROR） |
| `TEXT_MESSAGE_START/CONTENT/END/CHUNK` | `TextMessage*` | messageId, role, delta, name |
| `TOOL_CALL_START/ARGS/END` | `ToolCall*` | toolCallId, toolCallName, delta |
| `TOOL_CALL_RESULT` | `ToolCallResult` | toolCallId, content, role, messageId（=`toolName:toolCallId`） |
| `TOOL_CALL_CHUNK` | `ToolCallChunk` | toolCallId, toolCallName, parentMessageId, delta |
| `STATE_SNAPSHOT` | `StateSnapshot` | snapshot:Map（**死配置，见 §3.5**） |
| `STATE_DELTA` | `StateDelta` | delta:List\<JsonPatchOperation\>（同上） |
| `MESSAGES_SNAPSHOT` | `MessagesSnapshot` | messages:List\<AguiMessage\> |
| `STEP_STARTED/FINISHED` | `Step*` | stepName（**无人发射，见 §3.5**） |
| `RAW` | `Raw` | event:Object, source |
| `CUSTOM` | `Custom` | name, value:Object |
| `ACTIVITY_SNAPSHOT/DELTA` | `Activity*` | messageId, activityType, content/patch, replace（**无内置 converter**） |
| `REASONING_START/END` | `Reasoning*` | messageId, encryptedContent(START) |
| `REASONING_MESSAGE_START/CONTENT/END/CHUNK` | `ReasoningMessage*` | messageId, role, delta |
| `REASONING_ENCRYPTED_VALUE` | `ReasoningEncryptedValue` | subtype, entityId, encryptedValue |

辅助类型：`JsonPatchOperation(op, path, value, from)`（静态工厂 add/remove/replace）；`Interrupt(id, reason, message, toolCallId, responseSchema, expiresAt, metadata)`——**不实现 AguiEvent 接口**，仅作 RUN_FINISHED outcome 的嵌套载荷（这也是 #25 线格式 bug 的根源，见 §3.5）。

### 3.3 SSE 端点、线格式与关键配置

- 端点（`AguiRestController`）：`POST ${agentscope.agui.path-prefix:/agui}/run`（body = RunAgentInput JSON；agent id 经 header `X-Agent-Id`）与 `POST /agui/run/{agentId}`（`enable-path-routing` 默认 true）。请求模型 `io.agentscope.core.agui.model.RunAgentInput`：官方 7 字段 + AgentScope 扩展 `resume:List<AguiResume>`（`AguiResume(interruptId, status, payload)`）。
- 线格式：MVC `SseEmitter.data(encoder.encodeToJson(event), APPLICATION_JSON)` → 每帧 `data:{json}\n\n`；心跳 `: keep-alive\n\n`；JSON 走全局 `JsonUtils.getJsonCodec()`（#25 修复注入点）。
- 关键配置（默认值）：`path-prefix=/agui`、`agent-id-header=X-Agent-Id`、`run-timeout=10m`、`sse-timeout=600000`、`emit-state-events=true`（**死配置**）、`emit-tool-call-args=true`、`emit-token-usage=false`、`enable-reasoning=false`、`emit-run-finished-after-error=false`、`server-side-memory=false`、`interrupt-on-disconnect=true`、`default-tool-merge-mode=merge-frontend-priority`、`cors-enabled=true`。

### 3.4 运行时发射时机（内置转换器映射 + 时序）

| 内部事件 | 产出 AG-UI 事件 | 时机 |
|---|---|---|
| `AgentStartEvent` | `RUN_STARTED`（input=整个 RunAgentInput） | run 开始 |
| 文本块 TextBlock* | `TEXT_MESSAGE_*`（role 恒 "assistant"） | 模型流式文本 |
| 思考块 ThinkingBlock* | `REASONING_MESSAGE_*`（仅 `enableReasoning=true`；messageId 加后缀 `-reasoning`） | 模型思考 |
| ToolCallStart/Delta/End | `TOOL_CALL_START`/`ARGS`（仅 emitToolCallArgs）/`END` | 模型流式工具调用 |
| ToolResult* | 缓冲至闭合 → 补 `TOOL_CALL_END` + `TOOL_CALL_RESULT` | 工具执行完毕 |
| `CustomEvent` | `CUSTOM(name, value)` | 中间件自定义 |
| `ModelCallEndEvent` | `CUSTOM(name="token_usage", value={delta, cumulative, ...})`（仅 emitTokenUsage） | 每次 LLM 调用结束 |
| `RequireUserConfirmEvent` | Interrupt 载荷（汇入 RUN_FINISHED outcome，见 §3.6） | 权限门 ASK |
| `AgentEndEvent` | 先 finishPendingEvents()（补未闭合的 END），再 `RUN_FINISHED`（pendingInterrupts 非空 → interrupt outcome；**成功结束无 outcome 且 result 恒 null**） | run 结束 |
| 出错 | `RUN_ERROR {message, code}`（与 RUN_FINISHED 互斥） | 异常 |
| 未匹配 | `RAW(event, source="agentscope")` | 兜底 |

一轮正常 run 的文字时序：

```
POST /agui/run (RunAgentInput)
  ← RUN_STARTED
  ← [REASONING_MESSAGE_START/CONTENT…/END]        (enableReasoning=true 时)
  ← TEXT_MESSAGE_START → CONTENT×N → END
  ← TOOL_CALL_START → ARGS×N → END                (模型要调工具)
  ← TOOL_CALL_RESULT                               (工具执行完)
  ← (多轮 ReAct 重复文本/工具段)
  ← RUN_FINISHED (成功结束：无 outcome、result=null)
[出错] ← RUN_ERROR {code: TIMEOUT_ERROR|INTERRUPTED_ERROR|INVALID_INPUT_ERROR|INTERNAL_ERROR}
```

SSE 断开/超时时 `interrupt-on-disconnect=true`（默认）会调 agent 的 interrupt 中止本次 run。

### 3.5 死配置、缺口与已知 bug（2.0.3）

| 项 | 状态 | 影响 |
|---|---|---|
| `STATE_SNAPSHOT` / `STATE_DELTA` | **死配置**：类 + `AguiStateConverter`（createSnapshot/createDelta/computeDelta）都存在，但全 jar 无任何调用方；`emit-state-events` 无人消费（逐类反编译验证） | 依赖前端 state 同步的方案（CopilotKit State Rendering）在 2.0.3 **不可行**，须自定义 converter（可直接复用 `AguiStateConverter` 工具类） |
| `STEP_STARTED` / `STEP_FINISHED` | 类存在，无内置转换器发射 | `useCoAgent().nodeName` 类 UI 无事件源；需自定义 converter |
| `ACTIVITY_*` | 枚举与 record 存在，Event Mapping 表无映射 | 生成式 UI「进行中活动卡」需自定义 converter |
| `input_required` interrupt | 两条内置路径（权限确认 / tool suspension）都只产 `reason:"tool_call"` | 协议级结构化表单中断需自定义 converter |
| `expiresAt: null` 线格式 bug（**#25**） | `PermissionConfirmEventConverter` 给 Interrupt.expiresAt 传 null；`Interrupt` record 不实现 AguiEvent（接口层 `@JsonInclude(NON_NULL)` 罩不住）→ 线上出现 `"expiresAt":null` → 前端 zod `InterruptSchema` 拒收整条 RUN_FINISHED | 本仓库已用 `AguiEventNonNullCodec`（`AguiWireJsonConfig` 启动期替换全局 codec，仅对 AguiEvent 走 NON_NULL 序列化）规避；上游修复后回收 |
| 成功结束的 RUN_FINISHED | 无 outcome、result 恒 null | 前端不要假设 `outcome:{type:"success"}` 一定存在（本仓库测试即断言 outcome missing） |
| 续跑轮不重放工具事件 | `AguiStreamContext#hasStartedToolCall` 抑制 TOOL_CALL_END/RESULT | 审批卡片不能靠 result 事件判断「已执行」，以 run 完成 + 最终文本为准 |
| 多模态 document 类型 | 官方 Compatibility Notes："document types are not supported yet"；且 `AguiMessageConverter.toAguiMessage()` 只回写 text 与 tool-call 字段 | 文件上传场景只稳妥支持 image/audio/video；**多轮历史回放丢附件** |
| 文档滞后 | GitHub releases 页最新渲染到 v2.0.1，Maven Central 已有 2.0.2/2.0.3 | 2.0.3 无正式 release note，研究/排错须以 jar 为准 |

### 3.6 HITL/权限事件链（概要）

细节与端到端实测见 [agui-hitl.md](copilotkit/agui-hitl.md) §4-§5 与 `AguiInterruptIntegrationTest`（4 场景全绿）。要点：

1. **判定**：`PermissionEngine` 顺序 = deny 规则 → ask 规则 → 工具自决（`ToolBase.checkPermissions` 默认 passthrough；**`McpTool`：readOnly→allow，否则→ask**）→ 兜底 ASK。
2. **中断**：acting 阶段有 pendingAsk → 发内部 `RequireUserConfirmEvent(replyId, List<ToolUseBlock>)` + `RequestStopEvent` → `PermissionConfirmEventConverter` 把每个 ToolUseBlock 转成 `Interrupt`：`id="replyId:toolCallId"`、`reason="tool_call"`、`responseSchema={approved(boolean, required), editedArgs(object, 全量替换)}`、`metadata={toolName, toolInput, toolContent, "agentscope.interruptKind":"permission_confirm", replyId}` → `AgentEndEvent` 时汇入 `RUN_FINISHED {outcome:{type:"interrupt"}}`。**没有独立的 INTERRUPT 事件类型**。
3. **应答**：前端对同 threadId 发新 run，带 `resume[]`：

```json
{ "threadId": "t-1", "runId": "r-2", "messages": [],
  "resume": [{ "interruptId": "reply-1:call-1", "status": "resolved",
               "payload": { "approved": true, "editedArgs": { "path": "/tmp/reviewed-report.txt" } } }] }
```

4. **服务端**：`AguiResumeCoordinator` 校验（同 thread、全覆盖、无重复；无 open interrupt 时 resume → `RUN_ERROR AGUI_INTERRUPT_CONTRACT_ERROR`）→ `AguiMessageConverter.toConfirmResultMsg`（editedArgs 全量重建 ToolUseBlock）→ `applyConfirmResults`（approved=false 时合成拒绝工具结果）→ 续跑。

### 3.7 本仓库接线现状

- **agent 注册**：`AgentConfig.investAgentRegistration` 经 `AguiAgentRegistryCustomizer` 注册 "invest" 工厂，从 `CurrentUserHolder` 取 userId 构建 HarnessAgent（`AgentConfig.java:39-51`）。
- **身份注入**：`InvestAguiRuntimeContextResolver` 从 session 读 Spring SecurityContext → `RuntimeContext.builder().userId(...)`（2.0.3 API：`getNativeRequest()` 无参泛型）。
- **配置**：`application.yml` 开了 `enable-reasoning: true`、`server-side-memory: true`；Java 侧无权限规则（MCP 写工具 ASK 来自 `McpTool#checkPermissions`）。
- **线格式补丁**：`AguiWireJsonConfig` + `AguiEventNonNullCodec`（#25）。

---

## 四、三场景技术方案

### 4.1 场景 A：HITL 人工审批/介入

**官方两模式**（[HITL Overview](https://docs.copilotkit.ai/human-in-the-loop)）：

| 模式 | 谁决定暂停 | 后端形态 | 本仓库状态 |
|---|---|---|---|
| `useHumanInTheLoop`（tool-based） | **LLM** 主动调用前端工具 | `RunAgentInput.tools` 注入 schema，无 handler | 未用（推荐用于「追问」） |
| `useInterrupt`（runtime-paused） | **运行时**强制暂停 | AG-UI `RUN_FINISHED {outcome:{type:"interrupt"}}` | **已落地**（#25，MCP 写工具审批） |

**方案矩阵**（完整 7 方案对比见 [agui-hitl.md](copilotkit/agui-hitl.md) §5.1，此处为决策视图）：

| # | 方案 | 暂停发起方 | 回传通道 | 适用 | 本仓库结论 |
|---|---|---|---|---|---|
| 1 | interrupt + resume[]（`useInterrupt` × AgentScope 权限确认） | 服务端 | `RunAgentInput.resume[]` | 审批：服务端权威暂停、approve-with-edits、审计轨迹完整 | ✅ 已落地，主路径 |
| 2 | 前端工具（`useHumanInTheLoop` × `ToolMergeMode=merge-frontend-priority`） | LLM | 下一轮 messages 的 tool 消息 | 追问/收集输入：协议原生、无服务端状态 | ✅ 推荐增量（见 §4.3） |
| 3 | v1 `useCopilotAction` + `renderAndWaitForResponse` | LLM | 同 2（v1 封装） | v1 存量 | 不建议新用 |
| 4 | 权限规则白名单（`PermissionRule(ALLOW)` / DONT_ASK） | 策略 | 无交互 | 免打扰治理；规则可从 `ConfirmResult.suggestedRules` 沉淀 | 与 1 配合的长期治理 |
| 5 | `input_required` interrupt（responseSchema 表单） | 服务端 | `resume[].payload` | 协议级结构化输入 | AgentScope 2.0.3 无内置产出，需自定义 converter |

**代码**（官方 reference 示例）：

```tsx
// 1) 审批卡：useInterrupt 标准流（本仓库 ThreadArea.tsx:389-409 已用；agentId 必须显式传，
//    缺省解析到 "default" 抛 Agent not found——本地已踩坑）
useInterrupt({
  renderInChat: false,   // headless：返回元素手动挂载
  render: ({ interrupt, resolve, cancel }) => (
    <div className="p-3 border rounded">
      <p>{interrupt?.message ?? "Approve this action?"}</p>
      <button onClick={() => resolve({ approved: true })}>Approve</button>
      <button onClick={() => resolve({ approved: false })}>Reject</button>
      <button onClick={() => cancel()}>Dismiss</button>
    </div>
  ),
});
// 权限确认类中断靠 metadata["agentscope.interruptKind"] === "permission_confirm" 区分
//（与 tool suspension 共用 reason:"tool_call"）。

// 2) LLM 发起的询问：useHumanInTheLoop（状态机 InProgress → Executing(respond 可用) → Complete）
useHumanInTheLoop({
  name: "confirm_trade",
  parameters: z.object({ symbol: z.string(), shares: z.number() }),
  render: ({ args, status, respond }) => {
    if (status !== "executing" || !respond) return null;
    return <TradeConfirmCard args={args}
             onConfirm={() => respond(JSON.stringify({ approved: true }))}
             onReject={() => respond(JSON.stringify({ approved: false }))} />;
  },
}, []);
```

**风险**：① `expiresAt` TTL 语义已进 CopilotKit 1.70.1（过期 interrupt 不 resume，hook 记日志清 pending）——当前分支 `fix/mcp-hitl-expiresAt` 正对应；AgentScope 2.0.3 的 interrupt 不填 expiresAt（传 null），须防 `"expiresAt":null` 线格式（#25 已修）。② 续跑轮不重放工具事件，UI 以 run 完成为准。③ 多实例部署 pending interrupts 是服务端内存态（ConcurrentMap），需 thread 串行 + 共享存储。

### 4.2 场景 B：富文本信息展示（Markdown/表格、图表、文件）

> **本场景已细化落地为独立方案**：[05-富文本场景技术方案.md](05-富文本场景技术方案.md)（2026-09-10，含 ChartSpec/TableSpec 契约、ToolEmitter 双通道、ECharts/TanStack Table 集成与实施顺序）。以下为协议层结论与决策依据。

CopilotKit v2 生成式 UI 六原语与 AG-UI 通道对照（[generative-ui-overview](https://docs.copilotkit.ai/concepts/generative-ui-overview)）：

| 原语 | 用途 | AG-UI 通道 | AgentScope 2.0.3 支持度 |
|---|---|---|---|
| Components as Tools（`useComponent`） | agent 调用前端组件（官方明说 cards/**charts**/tables） | 前端工具 TOOL_CALL_* | ✅（tools 注入 + MERGE_FRONTEND_PRIORITY） |
| Tool Call Rendering（`useRenderTool`/`useDefaultRenderTool`） | 给**后端**工具配自定义卡片 | TOOL_CALL_* | ✅（TOOL_CALL_RESULT 可用；注意续跑轮抑制） |
| State Rendering | 订阅 agent state 流 | STATE_SNAPSHOT/DELTA | ❌ 死配置（§3.5），需自定义 converter |
| Reasoning | 思考链折叠卡 | REASONING_* | ✅（enable-reasoning 已开） |
| A2UI | 声明式 UI schema（Google A2UI 规范） | A2UI 中间件（`@ag-ui/a2ui-middleware`，已在依赖树 0.0.10） | 需 runtime 开 `a2ui:{}` + 后端产 A2UI JSONL（无内置支持） |
| MCP Apps | MCP server 附带 UI，沙箱 iframe | 专用通道 | 独立体系，暂不涉及 |

#### Markdown（含表格）

- `<CopilotChat>` 体系默认渲染器是 **Streamdown**（1.70.1 依赖 streamdown@1.6.11）：remark-gfm（**GFM 表格**/任务列表/删除线）+ remark-math + KaTeX + rehype-pretty-code 开箱即用（[CopilotChatAssistantMessage reference](https://docs.copilotkit.ai/reference/v2/components/CopilotChatAssistantMessage)）。定制点：`markdownRenderer` slot（组件替换 / className / props 三形态）。
- 本仓库 headless 自绘（react-markdown + remarkGfm + highlight.js）能力面与官方默认等价（缺 KaTeX/rehype-pretty-code 级公式高亮，按需补）。slot 体系是 `<CopilotChat>` 的能力，自绘路线的等价定制点就是自己的 AssistantMessage 组件。

#### 图表（chart）

CopilotKit **没有内置图表组件或 markdown 图表语法**（全文档站无 chart/mermaid 专页）。官方做法：**注册 React 图表组件**，协议上走 TOOL_CALL 事件——后端零改动，前端加 renderer 即可：

```tsx
// 后端工具（已有 @Tool get_kline）→ 前端配渲染器：参数流式可见，结果到达后 result 可用
useRenderTool({
  name: "get_kline",
  parameters: z.object({ symbol: z.string(), period: z.string() }),
  render: ({ status, parameters, result }) =>
    status === "complete" && result
      ? <KlineChart data={JSON.parse(result)} />   // recharts（仓库已有 3.10.1）
      : <ChartSkeleton params={parameters} />,
});
useDefaultRenderTool({ render: ({ name, status, result }) => <GenericToolCard ... /> }); // 通配兜底（本仓库已用）

// agent 主动出图（display-only 组件，官方示例）：
useComponent({
  name: "show_portfolio_chart",
  parameters: portfolioChartSchema,
  render: PortfolioChart,   // props 即 typed args；LLM 决定何时调用
});
```

markdown 内嵌图表（mermaid）无官方支持，需自行替换 markdownRenderer（如挂 rehype-mermaid）；不建议，优先组件路线。

#### 文件（上传/下载/展示）

**上传**：v2 有完整 attachments 体系（1.70.1 存在，本地类型核实）——`<CopilotChat attachments={...}>` 或 headless `useAttachments()`，底层映射 AG-UI 多模态 `user.content: InputContent[]`（text/image/audio/video/document part，source= base64 data / url）：

```tsx
// headless（本仓库形态）
const { attachments, fileInputRef, handleFileUpload, consumeAttachments } =
  useAttachments({ config: { enabled: true, accept: "image/*", maxSize: 10 * 1024 * 1024 } });
// maxSize 单位是字节（默认 20MB）——官方 skill 文档 Common Mistakes
const send = async () => {
  const ready = consumeAttachments();
  const contentParts: InputContent[] = [
    { type: "text", text: input },
    ...ready.map(att => ({ type: att.type, source: att.source,
      metadata: att.filename ? { filename: att.filename } : att.metadata })),
  ];
  agent.addMessage({ id: crypto.randomUUID(), role: "user", content: contentParts });
  await copilotkit.runAgent({ agent });
};
// 大文件自定义上传后端（S3 预签名）：
// onUpload: async (file) => ({ type: "url", value: presignedUrl, mimeType: file.type })
```

⚠️ **AgentScope 2.0.3 缺口**：① 官方 Compatibility Notes 明示 "document types are not supported yet"——只稳妥支持 image/audio/video 输入；② `AguiMessageConverter.toAguiMessage()` 只回写 text 与 tool-call 字段——**多轮历史回放丢附件**；③ 本仓库 `server-side-memory: true` + 反代 `trimToLatestUserMessage` 裁剪，附件只在最新一条 user 消息时可安全送达。

**下载/展示（agent → 用户）**：协议无专门"发文件"事件。可行路径：a) 工具结果带 URL → `useRenderTool` render 里渲染下载链接/预览（推荐，后端工具/MCP 工具产 URL 即可）；b) 自定义 converter 发 STATE_SNAPSHOT/CUSTOM 带文件引用；c) A2UI（中期）。

### 4.3 场景 C：用户问答（对话式 Q&A）

**基础聊天（headless，官方推荐入口）**：

```tsx
const { agent } = useAgent({ agentId: "invest" });
const { copilotkit } = useCopilotKit();
// 官方明确 copilotkit.runAgent 是推荐入口（"the same method <CopilotChat> uses internally"）：
// 它编排前端工具执行 + follow-up run + 流式结果；agent.runAgent() 是低层方法不做前端工具执行。
const send = async () => {
  agent.addMessage({ id: crypto.randomUUID(), role: "user", content: input });
  await copilotkit.runAgent({ agent });
};
// 停止：copilotkit.stopAgent({ agent })（本仓库用 agent.abortRun()，等价低层）
```

事件流消费即 §2.2 映射表；`useAgent({updates:[...], throttleMs:150})` 节流批量渲染。

**Agent 主动追问**（信息不足时反问用户）：官方主推 tool-based HITL——`useHumanInTheLoop` 注册 `ask_user`/`confirm_trade` 类前端工具（代码见 §4.1），LLM 自主决定何时问；AgentScope 侧 `RunAgentInput.tools` 注入 + `ToolMergeMode=MERGE_FRONTEND_PRIORITY`（默认，run 级注入与清理）。备选：`input_required` interrupt（协议级 responseSchema 表单 + expiresAt，AgentScope 需自定义 converter）。要点：前端工具轮 **run 以普通 success 结束、后端不产 TOOL_CALL_RESULT**，follow-up 由 `copilotkit.runAgent` 自动完成（这正是必须用它而非 `agent.runAgent` 的原因）；悬空 toolCallId 必须全部应答，否则污染历史。

**多轮上下文**（官方两路径，[threads-self-managed](https://docs.copilotkit.ai/threads-self-managed)）：

- **自管线程（本仓库现状，官方认可）**：自 mint `threadId` → AgentScope `server-side-memory: true`（HarnessAgent stateStore 按 threadId 存会话）→ 自建会话列表 UI。官方限制原文：框架持久化只存 agent **state**，不存 AG-UI 事件流——恢复时回显消息需自行 `agent.setMessages(...)`（本仓库 RuntimeProvider + ThreadArea 正是这么做）。
- `useThreads` / Rich Threads 是 **CopilotKit Intelligence 付费平台**能力，无自带线程后端扩展点（官方原文 "there is no such extension point"）——选型时不要混引。
- 多线程并发：1.70.1 `useAgent` 新增 thread-scoped 变体 `{agentId, runtimeAgentId, threadId}`（三件套全必填，注册私有 proxied agent 避免共享单例线程互踩）；与本仓库单 agent + 运行中禁切线程的架构冲突，仅在需要并发会话时考虑。
- AgentScope 侧配套参数（官方示例 application.yml）：`server-side-memory: true` + `max-thread-sessions: 1000` + `session-timeout-minutes: 30`（本仓库可对照补后两个管理内存占用）。

**已知风险（本仓库特有）**：前端持久化仅存 user/assistant 纯文本（丢 toolCalls 与 reasoning），服务端 stateStore 历史完整——跨会话回灌后 **LLM 看到的上下文与用户看到的 UI 口径不一致**（LLM 可能引用早前工具结论而 UI 无痕）。若要一致：后端历史保留 tool 消息，或前端 ChatMessage 扩展 toolCalls 回放。

---

## 五、版本坑与风险清单（汇总）

1. **THINKING_* 已废弃**：1.0.0 将移除；`@ag-ui/client` 0.0.45 兼容中间件自动转 REASONING_*，新代码不应再发。
2. **interrupt/resume 行为规范仍是 1.0 draft**（spec 页自标 "Draft — not yet ratified"），0.x schema（≥0.0.54）已实装结构；引用协议细节须标注 draft 状态。
3. **`TOOL_CALL_RESULT` 方向性**：只表示后端执行的工具结果；前端工具结果走「tool 消息 + follow-up 新 run」。两者最终在 `agent.messages` 都呈现为 role:"tool"，但传输路径不同。
4. **AgentScope 2.0.3 死配置/缺口**：STATE_* / STEP_* 不发射、ACTIVITY_* 与 input_required 无内置 converter、SUBAGENT_* 走 CUSTOM、成功 RUN_FINISHED 无 outcome、续跑轮不重放工具事件、多模态 document 不支持 + 回放丢附件（§3.5）。
5. **CopilotKit v1/v2 双轨**：v1 hooks 在 `src/v1-deprecated/`（仍是 v2 之上的桥接）；新代码一律 v2。
6. **本仓库反代三个已知适配点**：`normalizeNullContent`（assistant `content:null` → `""`，过 runtime zod 校验）、`trimToLatestUserMessage`（resume 轮 `messages:[]` 直接透传、resume 字段不经裁剪——测试已钉住）、#25 的 `AguiEventNonNullCodec`（`"expiresAt":null` 线格式）。升级任一侧（CopilotKit runtime / agentscope starter）都需回归这三处 + `/info` 发现 + resume 透传。
7. **CopilotKit ↔ AgentScope 无官方联合示例**：CopilotKit examples 无 Java 后端；AgentScope 官方示例是裸 JS SSE 客户端（其 HITL 演示走 frontend tool 路线）。本仓库 CopilotRuntime 反代路径属自研集成。
8. **多实例部署**：AgentScope pending interrupts 与 server-side-memory 会话均为服务端内存态（ConcurrentMap / stateStore），多实例需 thread 串行 + 共享存储。

---

## 六、参考资料

### AG-UI 协议

- 事件全集：https://docs.ag-ui.com/concepts/events （2026-09-09 抓取，与 0.0.59 一致）
- Interrupts（HITL 核心）：https://docs.ag-ui.com/concepts/interrupts
- Tools（frontend tools 往返）：https://docs.ag-ui.com/concepts/tools
- State / Capabilities / Metadata：https://docs.ag-ui.com/concepts/state · https://docs.ag-ui.com/concepts/capabilities · https://docs.ag-ui.com/concepts/metadata
- 多模态消息（attachments 底层）：https://docs.ag-ui.com/drafts/multimodal-messages
- 仓库与 npm：https://github.com/ag-ui-protocol/ag-ui · https://registry.npmjs.org/@ag-ui%2Fcore （0.0.59 = latest，2026-08-27）
- 本地实测源码：`frontend/node_modules/.pnpm/@ag-ui+core@0.0.59`（EventType 枚举 d.ts:4402-4454；RunAgentInput/Message/Interrupt/AgentCapabilities schema dump）、`@ag-ui+client@0.0.59`（AbstractAgent/defaultApplyEvents/prepareRunAgentInput/RunAgentParameters.resume）

### CopilotKit

- HITL Overview（两模式归纳）：https://docs.copilotkit.ai/human-in-the-loop
- v2 hooks reference：https://docs.copilotkit.ai/reference/v2/hooks/useInterrupt · https://docs.copilotkit.ai/reference/v2/hooks/useHumanInTheLoop · https://docs.copilotkit.ai/reference/v2/hooks/useAgent · https://docs.copilotkit.ai/reference/v2/hooks/useCopilotKit
- 生成式 UI：https://docs.copilotkit.ai/concepts/generative-ui-overview · https://docs.copilotkit.ai/generative-ui/tool-rendering · https://docs.copilotkit.ai/generative-ui/your-components/display-only · https://docs.copilotkit.ai/generative-ui/your-components/interactive
- 组件与 slots：https://docs.copilotkit.ai/reference/v2/components/CopilotChatAssistantMessage · https://docs.copilotkit.ai/custom-look-and-feel/slots
- headless / 线程 / 迁移：https://docs.copilotkit.ai/custom-look-and-feel/headless-ui · https://docs.copilotkit.ai/programmatic-control · https://docs.copilotkit.ai/threads-self-managed · https://docs.copilotkit.ai/migrate/v2
- attachments 权威参考（官方 skill 文档）：https://github.com/CopilotKit/CopilotKit `packages/react-core/skills/react-core/references/attachments.md`
- 官方示例：https://github.com/CopilotKit/CopilotKit/tree/main/examples/v2/interrupts-langgraph
- 本地实测源码：`frontend/node_modules/@copilotkit/{react-core,runtime,core}@1.70.1`（v2 `dist/v2/index.d.mts`；`dist/index.mjs:2766-3300` RunHandler/follow-up；`copilotkit-DiUK2Bhq.mjs:1433-1500` ToolCallRenderer 状态机；`runtime/dist/v2/runtime/handlers/handle-run.mjs`）

### AgentScope Java

- AG-UI 集成文档（官方 main）：https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/integration/protocol/agui.md （本地快照 `docs/technology/research/agentscope/agui.md`，经 2.0.3 jar 核对一致）
- 权限系统：https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/building-blocks/permission-system.md （本地快照 `docs/technology/research/agentscope/permission-system.md`）
- Releases（2.0.3 = 2026-09-07；注意 releases 页滞后）：https://github.com/agentscope-ai/agentscope-java/releases · https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-agui/2.0.3
- PR #2495（权限确认 → AG-UI interrupt）：https://github.com/agentscope-ai/agentscope-java/pull/2495 · Issue #2437：https://github.com/agentscope-ai/agentscope-java/issues/2437
- 官方示例（裸 JS 客户端 + frontend tool HITL）：https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui
- 本地实测：`.gradle-home/caches/modules-2/files-2.1/io.agentscope/{agentscope,agentscope-core,agentscope-agui-spring-boot-starter}/2.0.3` jar `javap` 反编译（`io.agentscope.core.agui.event.AguiEvent$*`、`PermissionConfirmEventConverter`、`AguiStreamContext`、`AguiResumeCoordinator`、starter 配置元数据）

### 本项目内部

- `docs/technology/research/copilotkit/agui-hitl.md`（HITL 深度调研，2026-09-08）
- `docs/technology/research/agentscope/{agui.md, message-and-event.md, permission-system.md}`
- `frontend/components/chat/{ThreadArea,RuntimeProvider}.tsx`、`frontend/app/api/copilotkit/[[...slug]]/route.ts`、`frontend/tests/agui-stream.test.ts`
- `backend/src/main/java/com/portfolio/invest/{web/InvestAguiRuntimeContextResolver, agent/{AgentConfig,HarnessAgentFactory,AguiWireJsonConfig,AguiEventNonNullCodec}}.java`、`backend/src/integrationTest/java/com/portfolio/invest/agui/AguiInterruptIntegrationTest.java`
- `docs/technology/decisions/0006-frontend-copilotkit.md`（ADR-0006 前端 CopilotKit v2 选型）
