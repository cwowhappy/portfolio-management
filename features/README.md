# features · 特性开发文档

> 本目录收集**开发新特性**过程中的需求、设计、计划文档。产品功能现状见 [docs/function/](../docs/function/)，技术实现见 [docs/technology/](../docs/technology/)，里程碑级落地进度见 [docs/plans/2026-08-27-产品落地计划.md](../docs/plans/2026-08-27-产品落地计划.md)。

## 目录结构

每个特性一个子目录，内部按「需求 → （设计）→ 计划」组织：

```
features/
├── <feature>/             ← 一个特性（对应一个或多个里程碑 MS）
│   ├── 01-requirement/    需求规格说明.md（需求 + 关键决策）
│   ├── 02-design/         设计规格说明.md（可选，仅需独立设计时）
│   └── 02-plan/ 或 03-plan/  P1/P2/P3 分阶段实施计划（无独立设计用 02-plan，有则用 03-plan）
├── plans/                 ← 跨特性工程/质量计划（测试体系加固、CodeReview 修复）
└── README.md
```

## 特性目录索引（目录 ↔ 里程碑 ↔ 功能模块）

| 特性目录 | 里程碑 | 功能模块 | 页面/路由 |
|----------|:------:|:-------:|-----------|
| [user-management](user-management/) | MS-00（一期） | M01 用户与认证、M02 会话管理 | `/login` `/register` `/admin` `/` |
| [market-valuation](market-valuation/) | MS-01、MS-02 | M06、M14、M05 | `/valuation` |
| [portfolio-management](portfolio-management/) | MS-03 | M08 持仓组合管理 | `/portfolio` |
| [asset-allocation](asset-allocation/) | MS-04 | M07 资产组合配置 | `/allocation` |
| [value-screening](value-screening/) | MS-05 | M09 价值投资筛选器、M10 行业研究中心 | `/screener` `/industry` |
| [journal](journal/) | MS-06 | M11 投资决策记录 | `/journal` |
| [collector-design-optimize](collector-design-optimize/) | 跨 MS-01/05（重构） | M14 系统与工程 | — |

> 里程碑（MS）定义与进度见 [产品落地计划](../docs/plans/2026-08-27-产品落地计划.md)；模块（M）定义与进度看板见 [功能模块概览](../docs/function/00-功能模块概览.md)。

## 开发流程（superpowers 工作流）

1. **brainstorming**（头脑风暴/需求澄清）→ 需求规格，落入 `<feature>/01-requirement/`
2. **writing-plans**（编写实施计划）→ 分阶段计划 P1/P2/P3，落入 `<feature>/02-plan/`（或 `03-plan/`）
3. **executing-plans / subagent-driven-development**（执行计划）→ 产物为代码
4. **test-driven-development**（红-绿-重构）→ 随实现进行
5. **requesting-code-review / receiving-code-review**（代码审查）
6. **verification-before-completion**（完成前验证）
7. **finishing-a-development-branch**（收尾合并）

> 仅「需求/设计/计划」阶段产出持久化文档收于本目录；实现、测试、审查的产物为代码/提交/评审意见，不入本目录。

## 命名约定

- 特性目录：英文 kebab-case，与功能模块/里程碑的对应见上方索引表。
- 需求：`<feature>/01-requirement/需求规格说明.md`
- 设计：`<feature>/02-design/设计规格说明.md`（可选）
- 计划：`<feature>/02-plan/P<N>-<主题>.md`（无独立设计时），或 `<feature>/03-plan/...`（有独立设计时）
- 跨特性工程计划：`plans/YYYY-MM-DD-<主题>.md`

## 维护约定

- 新增特性：在 `features/` 下建 `<feature>/` 目录，按「需求 → （设计）→ 计划」填充，并在上方索引表登记「目录 ↔ 里程碑 ↔ 模块」映射。
- 特性交付后：同步更新 [产品落地计划](../docs/plans/2026-08-27-产品落地计划.md) 的里程碑状态，与 [功能模块概览](../docs/function/00-功能模块概览.md) 的看板。
