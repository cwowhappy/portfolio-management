# ADR-0010 MCP 工具权限审批：readOnlyHint 缺省视为写

- 状态：已接受（2026-09-08）
- 决策者：项目负责人
- 相关：[mcp-hitl 需求规格](../../../features/mcp-hitl/01-requirement/需求规格说明.md)、[AGUI HITL 调研](../research/copilotkit/agui-hitl.md)

## 背景

MCP 接入一期落地时 AgentScope 2.0.1 尚无「权限确认 → AG-UI interrupt」映射，
`UserToolkitFactory` 将 MCP 工具强制 `readOnly=true` 绕过审批——写操作被谎标只读，安全语义缺失。
AgentScope 2.0.3 补齐映射并经本项目集成测试验证（PR #22）后，绕过理由消失。

## 决策

MCP 工具 readOnly 按 MCP 规范判定：`readOnlyHint=true` → 只读放行；缺省或 `false` → 视为写，
触发对话内权限审批（批准/拒绝）。判定式与上游 `McpClientManager` 逐字一致（上游为包私有，只能复制）。
不做 `FORCE_READONLY` 类回退开关。

## 影响

- 未标 `readOnlyHint` 的 MCP 数据源工具上线即全部弹审批（宁多问不漏问，知情选择）
- 回滚路径：git revert 本特性提交（不留开关后门，避免静默绕过审批）
- 已知限制（刷新/重启丢中断、卡片无 server 来源等）见设计规格 §五

## 后续治理

另行立项：`ConfirmResult.rules` 规则沉淀（「不再询问」）、provider/endpoint 级只读信任配置、
中断恢复机制。
