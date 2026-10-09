# docs · 项目文档

> 「九和 · 价值投资与资产配置系统」的文档中心。特性级开发文档见 [features/](../features/)，代码规范入口见 [AGENTS.md](../AGENTS.md)。

## 目录导航

| 目录 | 定位 | 入口 |
|------|------|------|
| [function/](function/) | 产品功能（M01–M14 + 进度看板），供产品/设计/测试/新人 | [README](function/README.md) |
| [technology/](technology/) | 技术文档（架构 / 模块 01–16 / 规范 / 决策 / 储备） | [README](technology/README.md) |
| [roadmap/](roadmap/) | 产品功能规划与进度跟踪（里程碑落地计划、产品功能规划） | [README](roadmap/README.md) |
| [research/](research/) | 跨特性调研报告（调研与规划分离：规划入 roadmap/） | [README](research/README.md) |
| [deployment/](deployment/) | 发布方案与发布计划（v1 首次发布） | [v1/发布计划.md](deployment/v1/发布计划.md) |
| [reviews/](reviews/) | 代码审查一次性报告（留档）与经验沉淀 lessons | 见下 |
| [archive/](archive/) | 历史归档（记录当时决策，不再随代码维护） | [README](archive/README.md) |

## 功能 vs 技术 vs 特性的关系

- 想看**系统有什么功能、做到哪了** → [function/00-功能模块概览.md](function/00-功能模块概览.md)
- 想看**技术怎么实现、为什么这么选** → [technology/](technology/)
- 想看**某个特性的需求/设计/计划** → [features/](../features/)
- 想看**里程碑级落地进度** → [roadmap/2026-08-27-产品落地计划.md](roadmap/2026-08-27-产品落地计划.md)
- 想看**行业调研、资源盘点** → [research/](research/)

## roadmap/ 目录

> 定位：**产品功能规划与进度跟踪**——里程碑落地计划、产品功能规划入本目录。
> 原则：**调研与规划分离**——调研类文档入 [research/](research/)（特性级调研在 `features/<特性>/09-调研报告/`）；工程实施计划入 [features/plans/](../features/plans/)。

| 文档 | 说明 |
|------|------|
| [2026-08-27-产品落地计划.md](roadmap/2026-08-27-产品落地计划.md) | 里程碑级（MS-00~MS-15 + 平台增强 MS-16~19）落地计划与进度跟踪（**跨模块权威**） |
| [2026-09-28-阶段二产品功能规划.md](roadmap/2026-09-28-阶段二产品功能规划.md) | 阶段二（MS-20~27，情报 + 投资 SOP）产品功能规划 |
| [2026-10-04-投研Agent功能迭代方向规划.md](roadmap/2026-10-04-投研Agent功能迭代方向规划.md) | v1 后迭代方向：D1–D8 八个方向 + P0–P3 路线图（依据 [research/ 趋势调研](research/2026-10-04-投研Agent发展趋势调研.md)，MS-28 及以后立项输入） |
| [2026-10-05-阶段三产品功能规划.md](roadmap/2026-10-05-阶段三产品功能规划.md) | 阶段三（MS-28~32：工程前置 + 可信溯源/记忆个性化/深度研究 + 评测观测工程项）里程碑规划与进度跟踪 |

## research/ 目录

> 原则：**调研与规划分离**——调研类文档（行业趋势、资源盘点、数据源探测）入本目录；由其产出的功能迭代/落地安排入 [roadmap/](roadmap/)。特性级过程调研仍在 `features/<特性>/09-调研报告/`。索引见 [research/README.md](research/README.md)。

| 文档 | 说明 |
|------|------|
| [2026-10-04-投研Agent发展趋势调研.md](research/2026-10-04-投研Agent发展趋势调研.md) | 2025–2026 金融投研 Agent 七大趋势（T1–T7）+ 本项目现状差距对照（24 项来源） |
| [2026-10-04-国内金融MCP资源盘点.md](research/2026-10-04-国内金融MCP资源盘点.md) | 国内商业（13 项）/开源（20+ 实现）金融 MCP 盘点 + 本项目对接优先级建议 |

## reviews/ 目录

| 文档 | 说明 |
|------|------|
| [code-review.md](reviews/code-review.md) | 2026-08-19 首次代码审查报告 |
| [code-review-2026-08-29.md](reviews/code-review-2026-08-29.md) | 2026-08-29 深度审查报告（含修复闭环） |
| [code-review-2026-09-03.md](reviews/code-review-2026-09-03.md) | 2026-09-03 三端全量审查报告（双轴方法，13 处 P1） |
| [features/code-review-2026-10/](../features/code-review-2026-10/) | 2026-10-04 起审查报告与修复按特性目录管理（09-调研报告=审查报告，01/03/08=需求/计划/复盘） |
| [code-review-lessons.md](reviews/code-review-lessons.md) | 代码审查经验沉淀（滚动文档，评审/开发前参考） |

> 一次性审查报告归档于 reviews/；长期沉淀的**问题模式与方法论**见 [code-review-lessons.md](reviews/code-review-lessons.md)。
