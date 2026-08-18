# ADR-0004 会话模型：前端持有历史 + threadId

- 状态：已接受（2026-08-18）
- 决策者：项目负责人

## 背景

多轮对话的上下文归属有两种：后端状态库（AgentStateStore）或前端持有历史（AG-UI 默认
server-side-memory=false）。

## 决策

采用 **server-side-memory=false**：前端以 threadId（UUID）标识会话，在 localStorage
持久化完整消息历史，每轮请求携带全部历史；后端不维护会话文件状态。

## 后果

正面：与 AG-UI 标准语义及 OpenAI 用法一致；后端无状态、重启即恢复、水平扩展简单；
一期单机部署下省略状态库依赖（二期多用户再评估 Redis/MySQL AgentStateStore）。
代价：每轮重传历史（一期会话规模小，token 开销可接受；二期引入记忆压缩时再评估）。
