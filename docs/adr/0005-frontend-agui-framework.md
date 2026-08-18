# ADR-0005 前端 AG-UI 框架选型：assistant-ui（渐进式迁移）

- 状态：已接受（2026-08-19）
- 决策者：项目负责人

## 背景

一期前端为自研 AG-UI 渲染层（手写 SSE 解析 + 自研组件）。评估接入支持 AG-UI 的前端框架。

候选（2026-08 实测版本）：
- 官方协议客户端 @ag-ui/client 0.0.58（ag-ui-protocol 组织，活跃）
- assistant-ui：@assistant-ui/react 0.15.15 + @assistant-ui/react-ag-ui 0.0.54
  （AG-UI Runtime 直接构建于官方 @ag-ui/client 之上，headless 可定制）
- CopilotKit @copilotkit/react-core 1.68.1（协议缔造者，功能最全但产品化设计、v1 多次 breaking）
- Vercel AI SDK v7（ai 7.0.66）——已排除：v5 引入的 AG-UI transport 在 v7 被移除
  （迁移指南无 UIAgent 提及，transport 文档无 AG-UI 内容）

## 决策

采用 **assistant-ui（@assistant-ui/react + @assistant-ui/react-ag-ui + @ag-ui/client）**，渐进式迁移：

1. **协议层（已完成）**：lib/agui.ts 的 runAgent 改为官方 HttpAgent 实现
   （threadId + initialMessages 种子历史，subscribe 订阅事件，abortRun 取消），
   UI 层签名不变，ChatPage 零改动，端到端验证通过
2. **UI 层（下一步）**：按官方 with-ag-ui 模式引入 AssistantRuntimeProvider +
   useAgUiRuntime（adapters.threadList 对接 localStorage 会话），
   ThreadPrimitive/MessagePrimitive.Parts 逐组件替换自研渲染，
   ToolCallCard/思考折叠/会话列表逐一迁到官方原语，保留"研报终端"设计体系

理由：协议层最正统（官方 client 打底，后端零改动）；headless 架构可完整保留现有视觉设计；
原生支持流式文本、REASONING、工具调用、STATE 事件、HITL 与线程历史；
维护活跃（当日版本更新）。风险控制：0.x 版本锁定 + 渐进替换（每步可独立验证）。

## 后果

正面：移除手写协议代码，二期可直接使用 HITL/多线程/前端工具等标准能力。
风险：assistant-ui 0.x API 变动频繁（v0.15 引入 Aui 架构，与 0.14 差异大）→
锁版本、按官方示例 API 实现、渐进式替换而非一次性重写。
