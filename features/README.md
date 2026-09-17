# features · 特性开发文档

> 本目录收集**开发新特性**全生命周期的文档：需求、设计、计划、复盘与调研。产品功能现状见 [docs/function/](../docs/function/)，技术实现见 [docs/technology/](../docs/technology/)，里程碑级落地进度见 [docs/plans/2026-08-27-产品落地计划.md](../docs/plans/2026-08-27-产品落地计划.md)。

## 目录规范（固定编号，2026-09-17 起）

每个特性一个子目录（英文 kebab-case），内部按**固定编号分层**——编号不随上层存在与否变化，各层**按需创建**、不预建空目录：

| 编号 | 目录 | 内容与命名 | 存在性 |
|:---:|---|---|:---:|
| 01 | `01-需求规格/` | `需求规格说明.md`（背景目标 + 澄清决策汇总 + 功能需求 + NFR + 验收标准 + YAGNI） | 必有 |
| 02 | `02-设计规格/` | `设计规格说明.md`（架构/数据模型/接口契约/测试策略，代码引用须 file:line 锚定） | 可选（仅需独立设计时） |
| 03 | `03-实施计划/` | `P<N>-<主题>.md` 分阶段任务级计划（TDD 步骤 + Interfaces 产销块） | 必有 |
| 04–07 | — | **预留**（未定义，勿占用） | — |
| 08 | `08-复盘总结/` | `复盘总结.md`（交付结果/经验/不足/优化建议/计划偏差清单），交付合并后回填 | 可选 |
| 09 | `09-调研报告/` | 特性过程中的调研/探测报告（如数据源可得性探测、技术选型调研），`<日期>-<主题>.md` | 可选 |

- **附属目录**（非文档产物的夹具/评估等，如 `fixtures/`、`eval-reports/`）：语义命名、无编号，需在本 README 索引行或特性内文档注明用途。
- **跨特性工程计划**：`features/plans/YYYY-MM-DD-<主题>.md`（不属于任何单一特性）。

## 状态标注与交付回填

- 索引表「里程碑」列三态格式：`MS-XX（进行中）` / `MS-XX（已交付 YYYY-MM-DD，PR #N）` / `跨 MS-XX~YY（<性质>，<状态>）`。
- **交付回填 checklist**（PR 合并后依序执行）：
  1. 模块文档（docs/function/modules/）功能点置 ✅ + 交付说明；
  2. [功能模块概览](../docs/function/00-功能模块概览.md) 看板（已完成/待开发/进度）；
  3. [产品落地计划](../docs/plans/2026-08-27-产品落地计划.md)：里程碑节状态 + 基线表 + 变更记录行 + 风险表；
  4. 本 README 索引行补「已交付 日期 + PR #N」；
  5. `08-复盘总结/复盘总结.md` 回填（含计划偏差清单）。

## 特性目录索引（目录 ↔ 里程碑 ↔ 功能模块）

| 特性目录 | 里程碑 | 功能模块 | 页面/路由 |
|----------|:------:|:-------:|-----------|
| [user-management](user-management/) | MS-00（一期） | M01 用户与认证、M02 会话管理 | `/login` `/register` `/admin` `/` |
| [market-valuation](market-valuation/) | MS-01、MS-02 | M06、M14、M05 | `/valuation` |
| [portfolio-management](portfolio-management/) | MS-03 | M08 持仓组合管理 | `/portfolio` |
| [asset-allocation](asset-allocation/) | MS-04 | M07 资产组合配置 | `/allocation` |
| [value-screening](value-screening/) | MS-05 | M09 价值投资筛选器、M10 行业研究中心 | `/screener` `/industry` |
| [journal](journal/) | MS-06 | M11 投资决策记录 | `/journal` |
| [analytics](analytics/) | MS-07（已交付 2026-09-16，PR #35） | M12 收益与风险分析 | `/analytics` |
| [rebalancing-screening](rebalancing-screening/) | MS-08（已交付 2026-09-17，PR #36） | M07 资产组合配置、M09 价值投资筛选器 | `/allocation` `/screener`（扩展） |
| [industry-listed-research](industry-listed-research/) | MS-09（进行中） | M10 行业研究中心 | `/industry`（扩展）`/industry/[industryCode]`（新增） |
| [collector-design-optimize](collector-design-optimize/) | 跨 MS-01/05（重构） | M14 系统与工程 | — |
| [mcp-integration](mcp-integration/) | MS-16（已交付 2026-09-07，PR #19） | M03 对话式投研问答、M14 系统与工程 | `/settings/mcp` |
| [skill-integration](skill-integration/) | MS-17（已交付 2026-09-08，PR #20） | M03 对话式投研问答、M14 系统与工程 | `/settings/skills` |
| [mcp-hitl](mcp-hitl/) | MS-18（已交付 2026-09-08，PR #22/#23；修复 #28/#30） | M03 对话式投研问答、M14 系统与工程 | — |
| [chat-rich-content](chat-rich-content/) | MS-19（已交付 2026-09-12，PR #29） | M03 对话式投研问答、M14 系统与工程 | —（对话流内，图表基建惠及 `/portfolio` `/valuation` `/allocation`） |
| [agent-testing](agent-testing/) | 跨 MS-16~19（测试加固，进行中） | M03 对话式投研问答（被测域）、M14 系统与工程 | —（纯测试/评估，无新页面） |

> 里程碑（MS）定义与进度见 [产品落地计划](../docs/plans/2026-08-27-产品落地计划.md)；模块（M）定义与进度看板见 [功能模块概览](../docs/function/00-功能模块概览.md)。

## 开发流程（superpowers 工作流）

1. **brainstorming**（头脑风暴/需求澄清）→ 需求规格，落入 `<feature>/01-需求规格/`
2. **writing-plans**（编写实施计划）→ 分阶段计划 P1/P2/P3，落入 `<feature>/03-实施计划/`
3. **executing-plans / subagent-driven-development**（执行计划）→ 产物为代码
4. **test-driven-development**（红-绿-重构）→ 随实现进行
5. **requesting-code-review / receiving-code-review**（代码审查）
6. **verification-before-completion**（完成前验证）
7. **finishing-a-development-branch**（收尾合并）

> 仅文档阶段产出持久化收于本目录；实现、测试、审查的产物为代码/提交/评审意见，不入本目录。

## 命名约定

- 特性目录：英文 kebab-case，与功能模块/里程碑的对应见上方索引表。
- 各层文件命名见「目录规范」表；调研报告用 `<日期>-<主题>.md`。
- 跨特性工程计划：`plans/YYYY-MM-DD-<主题>.md`。

## 维护约定

- 新增特性：在 `features/` 下建 `<feature>/` 目录，按「01 需求 →（02 设计）→ 03 计划」填充，并在上方索引表登记「目录 ↔ 里程碑 ↔ 模块」映射。
- 特性交付后：按「状态标注与交付回填」checklist 执行。

## 附：存量目录对照（迁移样例）

2026-09-17 全量迁移至固定编号（`git mv` 保历史；旧引用含 docs/ 与代码注释同步修复）：

| 特性 | 迁移前 | 迁移后 |
|---|---|---|
| 常规（如 analytics） | 01-requirement / 02-design / 03-plan | 01-需求规格 / 02-设计规格 / 03-实施计划 |
| 无设计层（如 journal 原态） | 01-requirement / 02-plan / 03-retrospective | 01-需求规格 / 03-实施计划 / 08-复盘总结 |
| 交付含复盘（rebalancing-screening） | 01/02-design/03-plan/04-retrospective | 01-需求规格 / 02-设计规格 / 03-实施计划 / 08-复盘总结 |
| 附属目录（chat-rich-content、agent-testing） | fixtures/ · eval-reports/ | 保持原名（语义命名无编号） |
