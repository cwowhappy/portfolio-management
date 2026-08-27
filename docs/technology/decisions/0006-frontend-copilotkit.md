# ADR-0006 前端 AG-UI 框架切换：assistant-ui → CopilotKit

- 状态：已接受（2026-08-19）
- 决策者：项目负责人
- 取代：ADR-0005（前端 AG-UI 框架选型）

## 背景

ADR-0005 选择 assistant-ui 作为前端 AG-UI 渲染层。实测后发现问题：
- assistant-ui 0.x API 变动频繁（v0.15 Aui 架构与 0.14 差异大），升级成本高；
- 其 AG-UI 适配（@assistant-ui/react-ag-ui）0.x 与核心包耦合紧，锁版本后仍难跟上游；
- 项目自绘的"研报终端"设计体系与 headless 原语贴合，但多套原语（Thread/Message/Composer/ActionBar）
  带来的心智与维护成本偏高。

CopilotKit 1.68.1（v2）是 AG-UI 协议的缔造者与主维护方，v2 API 已稳定：
- 官方 CopilotRuntime 服务端运行时 + HttpAgent(@ag-ui/client) 直接对接任意 AG-UI 端点；
- headless useAgent/useCopilotKit 可完整自绘 UI，保留既有视觉；
- 与后端 AgentScope 的 AG-UI 端点零耦合（后端零改动）。

## 决策

前端 AG-UI 渲染层整体切换为 **CopilotKit v2（@copilotkit/react-core + @copilotkit/runtime，1.68.1）**：
运行时路由 /api/copilotkit → HttpAgent → 后端 POST /agui/run；前端 useAgent headless 自绘。
会话模型（ADR-0004 前端持有历史）与交互协议（ADR-0002 AG-UI）不变。

## 后果

正面：跟随 AG-UI 官方生态主线；headless 能力完整保留设计体系；移除 @assistant-ui 三件套与手写 SSE 解析。
风险：CopilotKit v1 多次 breaking、v2 是新面 → 全部走 /v2 子路径并锁 1.68.1；
reasoning/toolCalls 消息字段形态需实测确认（Task 7 设验证步）。
