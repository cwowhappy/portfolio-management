# ADR-0011 服务端 Agent 会话状态：HarnessAgent stateStore 接管会话内存

- 状态：已接受（2026-09-12 补记；机制 2026-09-06 落地 feature/mcp-integration 分支，2026-09-07 随 PR #19 合入 main 生效）
- 决策者：项目负责人
- 取代：[ADR-0008 会话持久化：前端工作内存 + 服务端存储](0008-conversation-persistence.md) 的**内存模型部分**（表结构与消息 REST 仍有效，见 0008 头部标注）
- 相关：[mcp-integration P3 实施计划](../../../features/mcp-integration/03-plan/P3-mcp前端设置页与收尾.md)、[ADR-0010 MCP 工具权限审批](0010-mcp-hitl-permission.md)

## 背景

ADR-0008 的模型是「前端工作内存 + 服务端存储」：前端持全量消息历史，每轮把全量历史
发往 `/agui/run`，回复完成后再 PUT 全量到服务端。mcp-integration 引入 agentscope-harness
（2.0.3 四构件之一，`backend/build.gradle:73-79`）后，Agent 侧已具备服务端会话状态、
上下文压缩与长期记忆能力，前端重发全量历史会导致后端重复追加——两套内存模型冲突，
机制已在代码落地但缺正式决策承接，本 ADR 补记。

## 决策

会话内存切换为**服务端持有**，前端只发最新一条 user 消息：

- 引入 `agentscope-harness:2.0.3`，Agent 装配由 ReActAgent 换为 `HarnessAgent`
  （`HarnessAgentFactory.java:38-60`）：workspace `.agentscope/workspace`、
  `JsonFileAgentStateStore`（state-root `.agentscope/state`）持久化会话历史、
  compaction 30 条触发保留 10 条（flush-before-compact）、memory flush 节流 30 分钟；
- `agentscope.agui.server-side-memory: true`（`application.yml:45-46`），
  服务端按 stateStore 识别与续接会话；
- 前端只发最新一条：CopilotKit 无单消息发送路径（`useAgent`/`runAgent` 的
  RunAgentParameters 仅 runId/tools/context/forwardedProps/resume，`HttpAgent` 总是携带
  `agent.messages` 全量历史），故在前端 `/api/copilotkit` 路由边界以
  `trimToLatestUserMessage` 拦截（`frontend/app/api/copilotkit/[[...slug]]/route.ts:45,84`）：
  裁剪只影响发往 `/agui/run` 的 `input.messages`，浏览器侧展示历史不变；后端
  `extractLatestUserMessage` 替代方案因 HarnessAgent 非 ReActAgent 而失效，故前端
  裁剪是必需的（边界结论源自 mcp-integration P3 实施台账
  `.superpowers/sdd/P3-mcp前端设置页与收尾/progress.md`）；
- 会话 REST（`GET/PUT /api/conversations/{id}/messages`）与 0008 表结构保留，作跨设备
  历史回灌与展示存储：切换会话时 GET 回灌渲染，消息变化后防抖整体 PUT 全量——Agent
  运行上下文由 stateStore 承担，conversation/chat_message 只服务 UI 展示。

## 后果

正面：消除每轮全量历史重发与后端重复追加；服务端获得 compaction（长会话上下文可控）
与长期记忆（throttled flush）；AG-UI 协议语义（ADR-0002）不变。
代价与限制：路由边界裁剪是 CopilotKit 缺单消息路径下的 hacky 拦截，升级 CopilotKit
需回归验证；stateStore 为本地文件，单机部署前提，水平扩展需更换实现；运行内存
（stateStore）与展示存储（conversation/chat_message）双轨并存，两者不做事务一致，
内容以后端 SSE 返回为准。
取代边界：0008 的「前端工作内存」交互模型废止；其持久化表结构与 REST 仍有效。
