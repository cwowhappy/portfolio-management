# 技术文档目录（docs/technology）

> 本目录从**技术实现**视角梳理「砚台 · A股投研助手」的系统架构、Agent 实现、数据服务与工程运维。
> 产品功能视角见 [docs/function/](../function/)；架构决策见 [docs/adr/](../adr/)；
> 一期设计见 [docs/plans/2026-08-18-投资分析AI-Agent一期设计.md](../plans/2026-08-18-投资分析AI-Agent一期设计.md)。

## 文档索引

| 文档 | 定位说明 |
|---|---|
| [01-系统架构与技术栈.md](01-系统架构与技术栈.md) | 分层架构、数据流、技术栈版本、目录结构、请求链路、会话模型 |
| [02-Agent-实现.md](02-Agent-实现.md) | ReActAgent 装配、模型配置、系统提示词、6 个 `@Tool`、AG-UI 端点 |
| [03-行情数据服务.md](03-行情数据服务.md) | 数据源降级、代码规范化、缓存/限流、数据格式 |
| [04-接口.md](04-接口.md) | 行情 REST、AG-UI 对话、健康检查接口 |
| [05-工程与运维.md](05-工程与运维.md) | 配置、部署、错误处理、可观测性、测试、代码规范、已知限制 |

## 按需求快速定位

- 想知道**整体架构与选型** → [01-系统架构与技术栈.md](01-系统架构与技术栈.md)
- 想知道**AI 怎么实现** → [02-Agent-实现.md](02-Agent-实现.md)
- 想知道**数据怎么来、怎么保护** → [03-行情数据服务.md](03-行情数据服务.md)
- 想知道**对外有哪些接口** → [04-接口.md](04-接口.md)
- 想知道**怎么部署、测试、排错** → [05-工程与运维.md](05-工程与运维.md)
- 想看**产品功能** → [../function/](../function/)

## 技术栈一览

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 4.0.3 · JDK 21 · Gradle 9 · AgentScope Java 2.0.1 |
| LLM | DeepSeek（`deepseek-v4-flash` 默认，可配 `deepseek-v4-pro`） |
| 前端 | Next.js 15 · React 19 · TypeScript · Tailwind 4 · CopilotKit v2 |
| 数据源 | 东方财富公开接口（主）+ 新浪 / 腾讯（兜底） |
| 部署 | Docker Compose（backend + frontend） |
