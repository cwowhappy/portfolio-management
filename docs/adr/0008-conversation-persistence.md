# ADR-0008 会话持久化：前端工作内存 + 服务端存储

- 状态：已接受（2026-08-21）
- 决策者：项目负责人
- 取代：[ADR-0004 会话模型：前端持有历史 + threadId](0004-session-model.md)

## 背景

ADR-0004 采用 `server-side-memory=false`：前端以 threadId 标识会话，在 localStorage
持久化完整消息历史，每轮携带全量历史到 `/agui/run`。引入用户体系后，会话需**归属账号、
服务端持久化、换设备可见**，localStorage 无法满足。

## 决策

领域实体命名为 **Conversation**（表 `conversation`），`id` 复用 AG-UI 协议层 `threadId`；
协议与前端继续使用 `threadId`，后端领域模型用 `Conversation`（避免与登录态 session 撞词）。

持久化模型采用**前端工作内存 + 服务端存储**：

- 交互流程不变：前端仍持有当前对话消息作为工作内存，每轮请求携带全量历史到 `/agui/run`；
- 每轮 assistant 回复完成后，前端 **PUT 全量消息**到服务端（`PUT /api/conversations/{id}/messages`）；
- 打开会话时 **GET 加载**历史并继续；
- 删除会话同时删除其消息。

## 后果

正面：保留"前端持历史、后端无状态"的 AG-UI 交互（ADR-0002/0004 的协议语义不变）；服务端
只做纯存储，实现简单、可恢复；换设备可见。
代价：每轮重传历史（一期会话规模小，可接受）；多端同时编辑同一会话为**后写覆盖**，不做
并发合并；`chat_message.payload` 以 JSONB 存 AG-UI 扩展字段（工具调用等），跨版本兼容需
在读取侧做兜底。
