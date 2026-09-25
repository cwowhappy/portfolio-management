# 技术文档目录（docs/technology）

> 本目录从**技术实现**视角组织「九和 · 价值投资与资产配置系统」的全部技术文档，按五大板块划分：
> **系统架构设计 → 技术模块设计 → 技术规范 → 技术决策 → 技术储备与规划**。
> 产品功能视角见 [docs/function/](../function/)；实施计划见 [docs/plans/](../plans/) 与 [features/plans/](../../features/plans/)。

## 目录结构

```
docs/technology/
├── README.md                ← 本文件：总导航
├── architecture/            ← 一、系统架构设计
│   ├── 01-系统架构.md        │   总体架构图、分层职责、请求链路、会话模型
│   ├── 02-技术栈与工程结构.md │   技术栈版本定版、目录结构、部署拓扑
│   ├── 03-后端测试架构.md     │   四层测试模型、source set、覆盖率门禁、ArchUnit 守护、BDD 设计
│   ├── 04-数据采集服务架构.md │   collector 数据采集服务架构（数据源、任务调度、写入链路）
│   └── 05-数据类型与来源.md   │   证券数据类型全景 + 各来源接口明细（东财/新浪/腾讯/Tushare/中债）
├── modules/                 ← 二、技术视角的模块划分与设计（01–08 通用机制篇 + 09–15 业务域篇）
│   ├── 01-Agent实现.md       │   HarnessAgent 装配、模型配置、提示词、7 个 @Tool、AG-UI 服务端状态
│   ├── 02-行情数据服务.md     │   数据源降级、代码规范化、缓存/限流
│   ├── 03-接口设计.md        │   认证/管理员/会话/行情/AG-UI/健康检查接口
│   ├── 04-工程与运维.md      │   配置、部署、错误处理、测试、已知限制
│   ├── 05-MCP数据源集成.md    │   内置 provider 目录、用户级启用与工具开关
│   ├── 06-Skill系统.md       │   classpath 内置 Skill、用户启用集装配
│   ├── 07-HITL人工审批.md     │   MCP 写工具判定、interrupt 审批卡片、批准/拒绝续跑
│   ├── 08-聊天图表双通道.md   │   ChartSpec 契约、ToolEmitter 双通道、ECharts/TanStack 渲染
│   ├── 09-估值域.md          │   全 A 中位数/分位、ERP、温度计、行业估值
│   ├── 10-筛选域.md          │   五维 AND 组合筛选、排序限条、宽扫缓存
│   ├── 11-持仓域.md          │   分组记账、成本盈亏引擎、事件重放
│   ├── 12-资产配置域.md       │   内置模板、方案 CRUD、偏离度
│   ├── 13-投研日志域.md       │   四类决策条目、持仓联动、三路时间线
│   ├── 14-收益分析域.md       │   读侧重算：流水重放 × 收盘价融合、TWR/IRR/基准对比、四端点
│   └── 15-行业研究域.md       │   行业板面（四指标+5y分位+景气）、成员排名、历史重算回填
├── conventions/             ← 三、技术规范（有约束力的执行标准）
│   ├── 01-后端DDD分包规范.md  │   后端包划分与依赖规则（ArchUnit 强制）
│   ├── 02-后端架构与代码规范.md │   后端接口/异常/事务/安全编码规范
│   ├── 03-前端架构与代码规范.md │   前端反代/数据访问/流式组件规范
│   └── 04-采集服务架构与代码规范.md │  collector 事务/调度/工具链规范
├── decisions/               ← 四、技术决策（ADR 0001–0011 + 索引）
└── research/                ← 五、技术储备与规划
    ├── 00-技术储备与规划.md    │   储备盘点 + MVP/二期/三期技术规划
    ├── 01-财经新闻与股市数据来源参考.md │   外部数据源全景（行情/新闻/基本面/宏观）与获取方法
    ├── 02-akshare与tushare接口详析.md │   两库接口手册：87 次实测、返回字段、限制、本系统映射
    ├── 03-金融机构MCP服务参考.md   │   国内外金融机构/券商 MCP 服务全景与使用方法
    ├── 04-AGUI事件全景与三场景技术方案.md │ AG-UI 36 事件 + AgentScope 28 事件字段级对照、CopilotKit 消费映射、HITL/富文本/问答三场景方案
    ├── 05-富文本场景技术方案.md │ 聊天流富文本落地：ChartSpec 契约、ToolEmitter 双通道、ECharts/TanStack Table 集成
    ├── agentscope/           │   AgentScope 官方文档离线摘录（vendored，上游链接预期断链，见其 README）
    └── copilotkit/           │   CopilotKit × AG-UI 协议 HITL 支持调研（一手来源核实）
```

## 一、系统架构设计（architecture/）

| 文档 | 内容 |
|---|---|
| [01-系统架构.md](architecture/01-系统架构.md) | 总体架构图（浏览器 → Next.js 反代 → Spring Boot DDD 分层 → 外部依赖）、对话/行情两条请求链路、会话模型 |
| [02-技术栈与工程结构.md](architecture/02-技术栈与工程结构.md) | 技术栈版本定版表、前后端目录结构、Docker Compose 部署拓扑 |
| [03-后端测试架构.md](architecture/03-后端测试架构.md) | 后端四层测试模型（单元/切片/集成/BDD）、source set 划分、PostgresTestSupport 单例容器、JaCoCo 聚合门禁、ArchUnit 守护、BDD 场景清单 |
| [04-数据采集服务架构.md](architecture/04-数据采集服务架构.md) | collector 数据采集服务架构：数据源/转换器/计算/执行/校验/写入链路、任务调度、幂等写入 |
| [05-数据类型与来源.md](architecture/05-数据类型与来源.md) | 证券数据类型全景（运行期实时数据 + 落库估值/基本面）与各来源接口明细（东方财富/新浪/腾讯/Tushare/中债）、降级链与数据治理 |

## 二、技术视角的模块划分与设计（modules/）

后端按「DDD 洋葱分层 + 独立能力域」划分（ArchUnit 强制）；前端按页面 + 同源反代组织。模块篇目分**通用机制篇（01–08，跨业务域的横切机制）**与**业务域篇（09–15，DDD 各能力域）**，各技术模块的设计文档：

| 模块 | 文档 | 内容 |
|------|------|------|
| Agent 能力域 | [01-Agent实现.md](modules/01-Agent实现.md) | AgentConfig 装配、InvestSystemPrompt、InvestTools（7 个 `@Tool`）、AG-UI 端点与服务端状态 |
| 行情数据服务 | [02-行情数据服务.md](modules/02-行情数据服务.md) | 东方财富/新浪/腾讯客户端、降级策略、缓存与限流装饰器 |
| 对外接口 | [03-接口设计.md](modules/03-接口设计.md) | 全部 REST 端点与 AG-UI 对话端点 |
| 工程与运维（横切） | [04-工程与运维.md](modules/04-工程与运维.md) | 配置管理、部署、错误处理、可观测性、测试策略、已知限制 |
| MCP 数据源集成 | [05-MCP数据源集成.md](modules/05-MCP数据源集成.md) | 内置 MCP provider 目录（妙想/Tushare/Wind）、用户级启用与工具开关、按请求装配 MCP 工具 |
| Skill 系统 | [06-Skill系统.md](modules/06-Skill系统.md) | classpath 内置 SKILL.md 目录、用户启用集、HarnessAgent 按用户装配 SkillFilter |
| HITL 人工审批 | [07-HITL人工审批.md](modules/07-HITL人工审批.md) | MCP 写工具 readOnlyHint 判定（缺省视为写）、AG-UI interrupt 审批卡片、批准/拒绝续跑 |
| 聊天图表双通道 | [08-聊天图表双通道.md](modules/08-聊天图表双通道.md) | ChartSpec 双端契约、ToolEmitter 双通道（SSE 全量 + LLM 摘要）、ECharts/TanStack 渲染与体积门禁 |
| 估值域 | [09-估值域.md](modules/09-估值域.md) | 全 A 中位数/历史分位、ERP、情绪温度计、行业估值；collector 快照只读 |
| 筛选域 | [10-筛选域.md](modules/10-筛选域.md) | 12 指标条件 + 行业 AND 组合宽扫、排序限条、宽扫结果缓存；公开只读 |
| 持仓域 | [11-持仓域.md](modules/11-持仓域.md) | 分组账户记账（买/卖/两种分红/现金存取）、Position 成本盈亏引擎与事件重放、四类聚合视图 |
| 资产配置域 | [12-资产配置域.md](modules/12-资产配置域.md) | 4 内置模板、方案 CRUD 与单激活、对照持仓的偏离度 |
| 投研日志域 | [13-投研日志域.md](modules/13-投研日志域.md) | 四类决策条目、tradeId 反查持仓联动、三路合流时间线 |
| 收益分析域 | [14-收益分析域.md](modules/14-收益分析域.md) | 读侧重算（零落库）：流水重放 × 收盘价融合（V13 + 实时补位）、TWR/IRR/基准对比/年度收益/交易统计四端点 |
| 行业研究域 | [15-行业研究域.md](modules/15-行业研究域.md) | 行业板面聚合（四指标 + 近 5 年分位 + 景气）与成员排名（市值/营收/ROE）；`industry_valuation` 跨域共享、历史由重算任务回填 |

## 三、技术规范（conventions/）

具有约束力的执行标准（区别于 modules 的"实现描述"与 decisions 的"选型缘由"），见 [conventions/README.md](conventions/README.md)：

| 规范 | 约束方式 |
|------|---------|
| [01-后端DDD分包规范.md](conventions/01-后端DDD分包规范.md) | ArchUnit 单测强制，违反即构建失败 |
| [02-后端架构与代码规范.md](conventions/02-后端架构与代码规范.md) | ArchUnit（5 条）+ 测试 + 人工评审 |
| [03-前端架构与代码规范.md](conventions/03-前端架构与代码规范.md) | eslint + vitest 门槛 + 人工评审 |
| [04-采集服务架构与代码规范.md](conventions/04-采集服务架构与代码规范.md) | ruff + import-linter + pytest 门槛 + 人工评审 |

## 四、技术决策（decisions/）

架构决策记录（ADR），见 [decisions/README.md](decisions/README.md) 索引。当前有效决策要点：

- **ADR-0001** Agent 框架：AgentScope Java 2.0.3
- **ADR-0002** 交互协议：AG-UI 标准协议（SSE）
- **ADR-0003** 行情源：腾讯主源 + 东财兜底（行情/指数末级再降新浪；2026-09-25 修订，原为东财主源 + 新浪兜底）
- **ADR-0006** 前端框架：CopilotKit（取代 0005 的 assistant-ui）
- **ADR-0007** 用户认证：同源 Cookie 会话 + 管理员审核
- **ADR-0008** 会话持久化：前端工作内存 + 服务端存储（取代 0004；内存模型已被 0011 取代，表结构仍有效）
- **ADR-0009** 后端分层：DDD 洋葱分层 + 独立能力域
- **ADR-0010** MCP 工具权限审批：readOnlyHint 缺省视为写，触发 HITL 审批
- **ADR-0011** 服务端 Agent 状态：HarnessAgent stateStore 接管会话内存

## 五、技术储备与规划（research/）

| 文档 | 内容 |
|---|---|
| [00-技术储备与规划.md](research/00-技术储备与规划.md) | 已有储备盘点 + MVP/二期/三期新增技术能力规划 + 技术债清单 |
| [01-财经新闻与股市数据来源参考.md](research/01-财经新闻与股市数据来源参考.md) | 外部数据源全景参考：行情/新闻/基本面/宏观/公告的获取方法、接口明细、推荐组合（2026-09-06 调研核实） |
| [02-akshare与tushare接口详析.md](research/02-akshare与tushare接口详析.md) | 两库接口手册：87 次实测（真实返回字段/限制/报错根因）+ 本系统映射（已在用/可替代/可扩展） |
| [03-金融机构MCP服务参考.md](research/03-金融机构MCP服务参考.md) | 金融机构/券商 MCP 全景：国内官方（东财妙想/iFinD/Wind/Tushare/长桥/老虎）、国际（Alpha Vantage/FactSet/S&P 等）、A 股社区封装、接入方式与安全注意（2026-09-06 调研核实） |
| [04-AGUI事件全景与三场景技术方案.md](research/04-AGUI事件全景与三场景技术方案.md) | AG-UI 协议 36 事件 + AgentScope 2.0.3 的 28 事件字段级全景、CopilotKit 1.70.1 消费/发射映射、HITL/富文本（Markdown·图表·文件）/用户问答三场景技术方案与风险清单（2026-09-10 调研核实） |
| [05-富文本场景技术方案.md](research/05-富文本场景技术方案.md) | 聊天流富文本（文字/图片/表格/图表）落地细化：单一 ChartSpec 双端契约（table 为变体之一）、AgentScope ToolEmitter 双通道（全量走 SSE、摘要进 LLM）、ECharts 6 按需引入与自写 hook、TanStack Table v9、前置缺陷修复与实施顺序（2026-09-10） |
| [agentscope/](research/agentscope/) | AgentScope 官方文档离线摘录（vendored，见其 [README](research/agentscope/README.md)）：`scripts/fetch_docs.py` 抓取，上游站内链接预期断链；P0 协议勘误以 mcp-hitl 验证记录为准 |
| [copilotkit/](research/copilotkit/) | CopilotKit × AG-UI 协议 HITL 支持调研（一手来源核实，2026-09-08）：interrupt/resume 契约、useInterrupt 等 hooks、2.0.3 修复对照 |

## 按需求快速定位

- 想知道**整体架构与选型** → [architecture/01-系统架构.md](architecture/01-系统架构.md)
- 想知道**后端怎么分包、新代码放哪** → [conventions/01-后端DDD分包规范.md](conventions/01-后端DDD分包规范.md)
- 想知道**AI 怎么实现** → [modules/01-Agent实现.md](modules/01-Agent实现.md)
- 想知道**数据怎么来、怎么保护** → [modules/02-行情数据服务.md](modules/02-行情数据服务.md)
- 想知道**有哪些证券数据、各自来源与接口** → [architecture/05-数据类型与来源.md](architecture/05-数据类型与来源.md)
- 想知道**后续可接入哪些外部数据源** → [research/01-财经新闻与股市数据来源参考.md](research/01-财经新闻与股市数据来源参考.md)
- 想知道**金融机构有哪些 MCP 服务、怎么接入** → [research/03-金融机构MCP服务参考.md](research/03-金融机构MCP服务参考.md)
- 想知道**AG-UI 有哪些事件、HITL/富文本/问答怎么实现** → [research/04-AGUI事件全景与三场景技术方案.md](research/04-AGUI事件全景与三场景技术方案.md)
- 想知道**对外有哪些接口** → [modules/03-接口设计.md](modules/03-接口设计.md)
- 想知道**怎么部署、测试、排错** → [modules/04-工程与运维.md](modules/04-工程与运维.md)
- 想知道**后端测试怎么分层、新测试放哪** → [architecture/03-后端测试架构.md](architecture/03-后端测试架构.md)
- 想知道**数据采集服务怎么设计** → [architecture/04-数据采集服务架构.md](architecture/04-数据采集服务架构.md)
- 想知道**某个技术选型为什么** → [decisions/](decisions/)
- 想知道**后续版本要储备什么技术** → [research/00-技术储备与规划.md](research/00-技术储备与规划.md)
- 想看**产品功能** → [../function/](../function/)

## 维护约定

- 新增技术模块文档：归入 `modules/` 并登记本索引；新增 ADR：归入 `decisions/` 并更新其 README 索引。
- 新增有约束力的规范：归入 `conventions/` 并更新其 README 索引；规范落地优先用自动化强制（如 ArchUnit、覆盖率门槛）。
- 技术栈版本升级后同步 [architecture/02-技术栈与工程结构.md](architecture/02-技术栈与工程结构.md)。
- 新版本（MVP/二期/三期）启动前，先在 [research/00-技术储备与规划.md](research/00-技术储备与规划.md) 评估并登记技术方案。
