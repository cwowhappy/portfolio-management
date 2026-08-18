# ADR-0001 Agent 框架选型：AgentScope Java

- 状态：已接受（2026-08-18）
- 决策者：项目负责人 + 技术选型评审

## 背景

一期需要构建对话式投研 Agent：ReAct 推理-行动循环、6 个数据工具、流式 UI 事件、
多轮会话。候选：Spring AI 1.x（含 Alibaba 分支）、LangChain4j、AgentScope Java。

## 决策

选用 **AgentScope Java 2.0.1**（io.agentscope，阿里开源，Apache 2.0）。

理由：
- ReActAgent 原生内置推理-行动循环、工具编排、会话状态持久化、中断/恢复、HITL 权限系统
- DeepSeek 一等公民支持（deepseek: 模型 id + DEEPSEEK_API_KEY，专用 formatter 处理思考块）
- 全生命周期流式事件（thinking/tool_call/tool_result），前端工具进度卡片零成本
- 官方 Spring Boot 4.0.3 starter（agui / chat-completions-web 等）齐备，Maven Central 可获取
- 中文文档齐全，版本活跃（v2.0.1 GA 于 2026-08-06）

对比 Spring AI：通用性强、模型可移植，但 Agent 循环/记忆/多智能体/HITL 需自行组装，
样板代码量明显更大；本项目锁定 DeepSeek，不需要其模型可移植性优势。

## 后果

正面：开发量最小；二期多智能体流水线、交易人工确认可直接用框架能力。
风险：v2.0 刚 GA，API 仍在演进 → 固定 2.0.1 版本，仅使用本会话已验证过的 API 面。
