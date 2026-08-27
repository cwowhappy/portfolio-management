# 技术文档目录（docs/technology）

> 本目录从**技术实现**视角组织「砚台 · 价值投资与资产配置系统」的全部技术文档，按五大板块划分：
> **系统架构设计 → 技术模块设计 → 技术规范 → 技术决策 → 技术储备与规划**。
> 产品功能视角见 [docs/function/](../function/)；实施计划见 [docs/plans/](../plans/) 与 [features/plans/](../../features/plans/)。

## 目录结构

```
docs/technology/
├── README.md                ← 本文件：总导航
├── architecture/            ← 一、系统架构设计
│   ├── 01-系统架构.md        │   总体架构图、分层职责、请求链路、会话模型
│   └── 02-技术栈与工程结构.md │   技术栈版本定版、目录结构、部署拓扑
├── modules/                 ← 二、技术视角的模块划分与设计
│   ├── 01-Agent实现.md       │   ReActAgent 装配、模型配置、提示词、6 个 @Tool
│   ├── 02-行情数据服务.md     │   数据源降级、代码规范化、缓存/限流
│   ├── 03-接口设计.md        │   认证/管理员/会话/行情/AG-UI/健康检查接口
│   └── 04-工程与运维.md      │   配置、部署、错误处理、测试、已知限制
├── conventions/             ← 三、技术规范（有约束力的执行标准）
│   └── 01-后端DDD分包规范.md  │   后端包划分与依赖规则（ArchUnit 强制）
├── decisions/               ← 四、技术决策（ADR 0001–0009 + 索引）
└── research/                ← 五、技术储备与规划
    ├── 00-技术储备与规划.md    │   储备盘点 + MVP/二期/三期技术规划
    └── agentscope/           │   AgentScope Java 调研笔记（离线参考）
```

## 一、系统架构设计（architecture/）

| 文档 | 内容 |
|---|---|
| [01-系统架构.md](architecture/01-系统架构.md) | 总体架构图（浏览器 → Next.js 反代 → Spring Boot DDD 分层 → 外部依赖）、对话/行情两条请求链路、会话模型 |
| [02-技术栈与工程结构.md](architecture/02-技术栈与工程结构.md) | 技术栈版本定版表、前后端目录结构、Docker Compose 部署拓扑 |

## 二、技术视角的模块划分与设计（modules/）

后端按「DDD 洋葱分层 + 独立能力域」划分（ArchUnit 强制）；前端按页面 + 同源反代组织。各技术模块的设计文档：

| 模块 | 文档 | 内容 |
|------|------|------|
| Agent 能力域 | [01-Agent实现.md](modules/01-Agent实现.md) | AgentConfig 装配、InvestSystemPrompt、InvestTools（6 个 `@Tool`）、AG-UI 端点 |
| 行情数据服务 | [02-行情数据服务.md](modules/02-行情数据服务.md) | 东方财富/新浪/腾讯客户端、降级策略、缓存与限流装饰器 |
| 对外接口 | [03-接口设计.md](modules/03-接口设计.md) | 全部 REST 端点与 AG-UI 对话端点 |
| 工程与运维（横切） | [04-工程与运维.md](modules/04-工程与运维.md) | 配置管理、部署、错误处理、可观测性、测试策略、已知限制 |

## 三、技术规范（conventions/）

具有约束力的执行标准（区别于 modules 的"实现描述"与 decisions 的"选型缘由"），见 [conventions/README.md](conventions/README.md)：

| 规范 | 约束方式 |
|------|---------|
| [01-后端DDD分包规范.md](conventions/01-后端DDD分包规范.md) | ArchUnit 单测强制，违反即构建失败 |

## 四、技术决策（decisions/）

架构决策记录（ADR），见 [decisions/README.md](decisions/README.md) 索引。当前有效决策要点：

- **ADR-0001** Agent 框架：AgentScope Java 2.0.1
- **ADR-0002** 交互协议：AG-UI 标准协议（SSE）
- **ADR-0003** 行情源：东方财富公开接口 + 新浪/腾讯兜底
- **ADR-0006** 前端框架：CopilotKit（取代 0005 的 assistant-ui）
- **ADR-0007** 用户认证：同源 Cookie 会话 + 管理员审核
- **ADR-0008** 会话持久化：前端工作内存 + 服务端存储（取代 0004）
- **ADR-0009** 后端分层：DDD 洋葱分层 + 独立能力域

## 五、技术储备与规划（research/）

| 文档 | 内容 |
|---|---|
| [00-技术储备与规划.md](research/00-技术储备与规划.md) | 已有储备盘点 + MVP/二期/三期新增技术能力规划 + 技术债清单 |
| [agentscope/](research/agentscope/) | AgentScope Java 框架调研笔记（`scripts/fetch_docs.py` 离线抓取） |

## 按需求快速定位

- 想知道**整体架构与选型** → [architecture/01-系统架构.md](architecture/01-系统架构.md)
- 想知道**后端怎么分包、新代码放哪** → [conventions/01-后端DDD分包规范.md](conventions/01-后端DDD分包规范.md)
- 想知道**AI 怎么实现** → [modules/01-Agent实现.md](modules/01-Agent实现.md)
- 想知道**数据怎么来、怎么保护** → [modules/02-行情数据服务.md](modules/02-行情数据服务.md)
- 想知道**对外有哪些接口** → [modules/03-接口设计.md](modules/03-接口设计.md)
- 想知道**怎么部署、测试、排错** → [modules/04-工程与运维.md](modules/04-工程与运维.md)
- 想知道**某个技术选型为什么** → [decisions/](decisions/)
- 想知道**后续版本要储备什么技术** → [research/00-技术储备与规划.md](research/00-技术储备与规划.md)
- 想看**产品功能** → [../function/](../function/)

## 维护约定

- 新增技术模块文档：归入 `modules/` 并登记本索引；新增 ADR：归入 `decisions/` 并更新其 README 索引。
- 新增有约束力的规范：归入 `conventions/` 并更新其 README 索引；规范落地优先用自动化强制（如 ArchUnit、覆盖率门槛）。
- 技术栈版本升级后同步 [architecture/02-技术栈与工程结构.md](architecture/02-技术栈与工程结构.md)。
- 新版本（MVP/二期/三期）启动前，先在 [research/00-技术储备与规划.md](research/00-技术储备与规划.md) 评估并登记技术方案。
