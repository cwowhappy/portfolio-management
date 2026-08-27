# ADR-0002 交互协议选型：AG-UI 标准协议

- 状态：已接受（2026-08-18）
- 决策者：项目负责人

## 背景

前后端流式交互有两种方案：自定 SSE 事件协议（6 个事件）或 AG-UI 开放协议
（ag-ui-protocol，CopilotKit 社区发起，2025 年成为多框架事实标准）。

## 决策

选用 **AG-UI 协议**，通过 AgentScope 官方 agentscope-agui-spring-boot-starter 落地。

理由：
- 后端零协议代码：starter 自动注册端点，AgentEvent → AG-UI 事件官方映射
- 标准事件族覆盖全部需求：RUN_* / TEXT_MESSAGE_* / REASONING_* / TOOL_CALL_* / CUSTOM
- 为二期预留标准语义：HITL 中断-恢复（交易确认）、子智能体事件命名空间、前端工具注入
- 未来可对接 CopilotKit / AI SDK 等现成前端生态

## 后果

正面：协议标准化，后端代码量最小，二期无需改协议。
风险：协议较新，官方实现有少量限制（多媒体块不回传等），对本项目文本+工具场景无影响；
前端需自研轻量 AG-UI 事件渲染层（不引第三方 UI 框架，保持可控）。
