# ADR-0012 AG-UI threadId 绑定会话表 id：刷新续用同线程

- 状态：已接受（2026-09-26；issue #26 用户裁决方案 a）
- 决策者：项目负责人
- 相关：[ADR-0008 会话持久化](0008-conversation-persistence.md)、[ADR-0011 服务端 Agent 会话状态](0011-server-side-agent-state.md)、[ADR-0010 MCP 工具权限审批](0010-mcp-hitl-permission.md)、issue #26 / #27

## 背景

CopilotKit v2 的 unscoped `useAgent`（`agentId` 单参变体）把 threadId 留给 chat 配置缺省——
每次页面加载自铸新 AG-UI UUID（issue #26 实测 `85a0dab6…`/`d1682db8…`，均不等于会话表 id），
threadId 生命周期 = 单次页面加载。而后端键控全部以 AG-UI threadId 为会话半边：

- `JsonFileAgentStateStore` 按 `(userId, threadId)` 落盘 `.agentscope/state/<userId>/<threadId>`
  （ADR-0011）——刷新即换键，多轮上下文/compaction/长期记忆被静默截断为孤儿目录；
- `AguiResumeCoordinator` 按 threadId 持有 pending interrupt（ADR-0010）——刷新前的挂起审批
  成孤儿；设计规格 FR-8 场景A「刷新后同线程发消息 → 契约错误横幅」因此**结构性不可达**
  （实测为 LLM 依回灌历史重发工具调用弹新卡的自愈路径）。

连带 issue #27：登录后立即发消息时，`isReady` 恰在历史回灌**开始**而非完成时刻放行发送，
回灌完成即 `abortRun()` 掐断在途运行（消息清空、服务端留未决 interrupt），dev 首次编译
（>1.5s）放大该竞态窗口。

## 决策

1. **threadId = 会话表 id**：`RuntimeProvider` 以
   `CopilotChatConfigurationProvider threadId={currentThreadId}`（从 `@copilotkit/react-core/v2/headless`
   导入——v2 index 入口带 index.css 副作用，未 mock 该模块的测试文件会崩）钉住 chat 配置线程。
   `useAgent`/`useInterrupt`/`runAgent` 代码零改动，unscoped 变体自动取 chat 配置 threadId。
2. **发送前置回灌闸门**（issue #27）：`send` 与 Composer 的 `ready` 增加条件
   `hydratedThreadId === currentThreadId`——当前线程历史回灌完成前禁发。闸门开启前不可能有
   本线程在途运行，「回灌完成即 abortRun」只剩跨线程停流的正当路径。回灌**失败**不开启闸门
   （agent.messages 仍属旧线程，放行会跨线程串写）；切走再切回即重试回灌。

## 后果

**收益**：刷新/跨标签页续用同一线程——服务端会话记忆连续（长对话不再被静默截断）、HITL
恢复键与会话一致、FR-8 场景A 恢复设计可达（契约错误兜底文案重新有真实触发路径）。

**代价（接受）**：同一会话残留未决 interrupt 时（如刷新后不再应答），后续消息将按 FR-8
契约错误引导**开新会话**——原「自愈重发工具卡」路径不再出现；多标签页同开会话会并发写同一
state 目录（单用户应用，接受）；回灌失败期间输入框禁用（此前可发但会串写，属以可用性换正确性）。

## 验证

- 单测（`tests/ThreadArea.test.tsx`）：红→绿钉住「回灌前发送被拦、回灌后放行、全程无
  abortRun」（#27）与「chat 配置 threadId = 会话表 id、切线程同步」（#26）；
- 真机 e2e：chat/conversation/hitl 9 用例全绿（正常发送、审批续跑在稳定 threadId 下无回归）；
  新增回归探针 `e2e/threadid-binding.spec.ts`——真实运行后断言
  `.agentscope/state/<userId>/<会话id>` 目录存在（修复前为自铸 UUID，必不存在）。
