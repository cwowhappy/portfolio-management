# M16 · 投资 SOP 工作流

> **状态**：✅ 已完成（F11 已随 M15 MS-23 回收，16/16 收齐） | **进度**：16/16 | **目标版本**：阶段二（MS-24~27，2026-09-29 PR #81；F11 回收 2026-10-03 PR #82）
> **页面**：`/research`（新增，列表 + 详情 + 立项表单）、对话内四个 SOP Skill 引导 + 草稿卡
> **上游规格**：[需求规格 v1.0](../../../features/invest-sop/01-需求规格/需求规格说明.md)（决策 D1~D23）/ [设计规格 v1.0](../../../features/invest-sop/02-设计规格/设计规格说明.md)（S1~S7）

## 一、模块定位

把「新分析 → 制定投资策略 → 建仓与持仓 → 复盘」四阶段投资流程内建为系统作业方式——结构化研究项目（`domain/research`）承载状态与产物，四个内置 SOP Skill 对话式引导，纪律检查与证伪评审全程留痕，复盘结论回流知识库。一句话：让投资决策从「随手记」变成「有流程、有纪律、可复盘」。

## 二、功能点列表

| 编号 | 功能点 | 状态 | 版本 | 备注 |
|------|--------|:----:|:----:|------|
| F01 | SOP 四阶段最佳实践 v1 定稿 | ✅ | 阶段二 | 50 条清单（F02/F03 唯一内容源） |
| F02 | 四个内置 SOP SKILL.md + 草稿双通道 | ✅ | 阶段二 | research_draft 只读工具 + DraftCard |
| F03 | SOP wiki 模板 seeding | ✅ | 阶段二 | 独立标记幂等，删光不复活 |
| F04 | 研究项目域（domain/research） | ✅ | 阶段二 | V2 迁移 11 表 + 完成度唯一计算点 |
| F05 | 新分析立项 | ✅ | 阶段二 | 筛选器/行业中心一键发起 + 模板带入 |
| F06 | 策略文档五字段结构 | ✅ | 阶段二 | 两级状态机 + 证伪谓词 |
| F07 | 研究项目前端页 `/research` | ✅ | 阶段二 | 三态进度角标（无百分比） |
| F08 | 项目与 journal/wiki 联动 | ✅ | 阶段二 | RESEARCH_EVENT 入时间线 + 反查 |
| F09 | 建仓计划 | ✅ | 阶段二 | 凯利手动算术 + Σ占比硬校验 |
| F10 | 买入纪律检查单 | ✅ | 阶段二 | 纯函数三态 + append-only 留痕 |
| F11 | 持仓情报监控 | ✅ | 阶段二 | 原**顺延 M15**（D14），已随 M15 MS-23 回收（2026-10-03） |
| F12 | 加减仓/卖出纪律检查 | ✅ | 阶段二 | 与 F10 同服务 + 证伪核对 |
| F13 | 复盘模板三档 | ✅ | 阶段二 | 常量表驱动（MS-24 收敛只改常量） |
| F14 | 复盘数据自动带入 | ✅ | 阶段二 | 快照写入定格 + 批次时间窗归因 |
| F15 | 策略证伪评审 | ✅ | 阶段二 | 规则式求值 + 日终飞书零数字 |
| F16 | 复盘归档与知识回流 | ✅ | 阶段二 | 确认后入库 + 失败降级可重试 |

## 三、功能点详情（按里程碑分组，交付说明 2026-09-29 PR #81）

### MS-24 · SOP 内容与 Skill 化（F01~F03）

- **F01 最佳实践 v1**：纯价值投资主线（D3），四阶段「必须有/可选项」共 50 条（新分析 10 / 策略 17 / 纪律 11 / 复盘 12），逐条经用户评审确认（删 1.10 催化剂、3.9 免责转页面文案）；内容定稿存 [04-研发过程](../../../features/invest-sop/04-研发过程/2026-09-28-SOP-v1-内容定稿.md)。
- **F02 四 SKILL.md + 草稿通道**：`resources/skills/{new-analysis,strategy,position,review}`（default_enabled: false，按用户启停）；Agent 引导每节要素后调 `research_draft` 只读工具回显，工具结果文本围栏 ```research-draft → 前端 `lib/research-draft.ts` zod 判别联合 safeParse → DraftCard 卡片（失败降级不白屏）；**落库=用户确认**（D9：草稿不自动暂存，保存走 /research API）。
- **F03 模板 seeding**：照 M13-F03 幂等先例，`wiki_seed_state.sop_seeded_at` 独立标记（老用户概念标记不互扰，先行访问自动回填概念预置防谎报）；50 条 RESEARCH_NOTE + category=SOP_TEMPLATE，删光不复活。

### MS-25 · 研究项目域（F04~F08）

- **F04 域模型**：`V2__research.sql`（11 新表 + journal_entry/wiki_entry 加 project_id 软引用 + entry_plan/strategy_doc UNIQUE(project_id)）；ResearchProject 聚合 + `StageCompletionService` 完成度唯一计算点（手动覆盖 REOPENED > MANUAL > 产物齐套 AUTO，S6 只存手动覆盖读时推导）；ArchUnit domain.. 通配自动覆盖（无白名单登记——计划勘误 Ruling）。
- **F05 立项**：筛选器结果行/行业中心标的行「发起研究」→ `/research/new` 预填（withTemplate 带入新分析模板）；对话「新分析」Skill 并列入口；与 watchlist 零自动联动（D17）。
- **F06 策略五字段**：投资逻辑/估值区间（手动 D6）/仓位计划/买入条件/风险与证伪；DRAFT/FINALIZED 两级覆盖式修订（D13 无版本链）；证伪条件 = 价格/估值结构化谓词（PRICE_BELOW/PRICE_ABOVE/PE_ABOVE/PB_ABOVE + 阈值）+ 事件人工勾选（D10，终审修复补齐勾选写路径）。
- **F07 前端**：列表（阶段/状态过滤 + 空态引导）+ 详情（三态进度条 + 完成方式角标 D22 + 策略/建仓/检查留痕/复盘/证伪分区 + 关联记录反查 F08）。
- **F08 联动**：journal 第 5 类型 RESEARCH_EVENT（立项/阶段变更/定稿/修订/检查/评审/复盘创建/回流八类事件写入点）；journal/wiki `?projectId=` 反查；删项目解除关联不删内容。

### MS-26 · 纪律与建仓持仓（F09~F12）

- **F09 建仓计划**：分批方案整替保存；凯利 f\*=p−(1−p)/b 手动参数（D23，计划示例 0.2 系笔误勘误为 0.4）；Σ占比 >1 唯一硬拒（D5 例外，=1 恰过）。
- **F10/F12 检查单**：`DisciplineCheckService` 纯函数（PASS/HIT/UNSET 三态，规则未配置=「未设定」中性）；消费 M13 PrincipleRule（application 层 RuleInput 转换，domain 零横依赖）；页面级确认卡（D18 仿审批卡 UI 不经 useInterrupt，与 portfolio 交易录入解耦）；`research_check_record` append-only（OVERRIDDEN 必填理由）；卖出检查注入证伪核对条目。
- **F11 情报监控**：原**顺延 M15**（D14 验收拆分不阻塞）——已于 M15 MS-23 回收（2026-10-03）：`ResearchIntelligenceSubscriptionHookImpl` 消费项目 `intelligence_alert_enabled` 开关（详情页 UI + `PUT /api/research/projects/{id}/intelligence-alert`），持仓项目公告推送 + journal 时间线「【情报】」条目，详见 [M15 模块文档](15-智能情报.md) MS-23 交付说明。
- **证伪求值**：`FalsifierEvaluator` 四谓词纯函数（缺数据 skipped 不冒充、EVENT 恒 pending 人工勾选、basis 可解释含口径）；日终扫描 18:43 错峰（ACTIVE+POSITION、未评审去重、Ruling-19 条件编辑后重新提醒）；飞书文案仅项目名+条件名零数字（D15）。

### MS-27 · 复盘闭环（F13~F16）

- **F13 三档模板**：月主/季深/周简，字段常量表驱动（`REVIEW_FIELDS`，字段集收敛只改常量）。
- **F14 数据带入**：创建即 `ReviewSnapshotComposer` 定格（区间收益重算自 analytics nav + 圈内 trade vs 后续走势，`priceBasis` 口径标注、「无数据」字符串非 null/0）；归因圈选 = 批次建仓期间 ∪ 持有期时间窗自动 + 手动修正（D11，窗外交易不并入）。
- **F15 证伪评审**：四结论（维持/减仓/退出/修订策略）+ 理由；REVISE 响应 suggestStrategyRevise 提示位不自动改策略；hit 首评占据（Ruling-22）append-only 留痕。
- **F16 知识回流**：确认弹层 → wiki RESEARCH_NOTE（category=SOP_REVIEW + projectId）→ REFLOWN 幂等（二次同 entryId）；写入失败 502 降级回 PENDING 可重试（根因日志留痕）；模板改进建议 `research_feedback` 只收集不生效。

## 四、关联

- **消费 M13**：PrincipleRule 4 指标（检查）、wiki seeding/回流通道；**消费 M12**：analytics nav/trade 流水（复盘快照）；**消费 M08**：portfolio 流水（归因圈选软引用）；**依赖 M15**：F11 情报订阅（已随 M15 MS-23 回收）；**journal**：RESEARCH_EVENT 时间线与反查。
- **v2 已知限制**（终审 triage）：对话侧草稿保存为引导性死路（会话↔项目绑定未落任务——草稿生成后需到 /research 页保存）、SOP seeding 并发窗口（无 (user_id,title) 唯一约束，硬化需拍板同名语义）、research_draft 对话链缺真浏览器 e2e（需 LLM fixture harness）。
