# 技术决策（ADR 索引）

> 架构决策记录（Architecture Decision Records）：每项重大技术选型/架构变更的背景、选项与结论。
> 新决策按编号续接，已取代的决策保留原文并标注取代关系。

## 决策索引

| 编号 | 决策 | 状态 |
|------|------|------|
| [ADR-0001](0001-agent-framework.md) | Agent 框架选型：AgentScope Java | 有效 |
| [ADR-0002](0002-interaction-protocol.md) | 交互协议选型：AG-UI 标准协议 | 有效 |
| [ADR-0003](0003-market-data-source.md) | 行情数据源：东方财富公开接口 + 新浪兜底 | 有效 |
| [ADR-0004](0004-session-model.md) | 会话模型：前端持有历史 + threadId | 已被 [ADR-0008](0008-conversation-persistence.md) 取代 |
| [ADR-0005](0005-frontend-agui-framework.md) | 前端 AG-UI 框架选型：assistant-ui（渐进式迁移） | 已被 [ADR-0006](0006-frontend-copilotkit.md) 取代 |
| [ADR-0006](0006-frontend-copilotkit.md) | 前端 AG-UI 框架切换：assistant-ui → CopilotKit | 有效 |
| [ADR-0007](0007-user-auth.md) | 用户管理与认证方案 | 有效 |
| [ADR-0008](0008-conversation-persistence.md) | 会话持久化：前端工作内存 + 服务端存储 | 有效 |
| [ADR-0009](0009-backend-ddd-layering.md) | 后端分层演进：DDD 洋葱分层 + 独立能力域 | 有效 |

## 主题速查

- **AI 对话链路**：0001（Agent 框架）→ 0002（AG-UI 协议）→ 0005/0006（前端框架）
- **数据**：0003（行情源）
- **会话**：0004 → 0008（持久化演进）
- **认证与分层**：0007（用户认证）· 0009（DDD 分层，落地规范见 [../conventions/01-后端DDD分包规范.md](../conventions/01-后端DDD分包规范.md)）
