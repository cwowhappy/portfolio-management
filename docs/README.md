# docs · 项目文档

> 「九和 · 价值投资与资产配置系统」的文档中心。特性级开发文档见 [features/](../features/)，代码规范入口见 [AGENTS.md](../AGENTS.md)。

## 目录导航

| 目录 | 定位 | 入口 |
|------|------|------|
| [function/](function/) | 产品功能（M01–M14 + 进度看板），供产品/设计/测试/新人 | [README](function/README.md) |
| [technology/](technology/) | 技术文档（架构/模块/规范/决策/储备） | [README](technology/README.md) |
| [plans/](plans/) | 跨模块/版本级计划（产品落地计划） | 见下 |
| [reviews/](reviews/) | 代码审查一次性报告（留档）与经验沉淀 lessons | 见下 |
| [archive/](archive/) | 历史归档（记录当时决策，不再随代码维护） | [README](archive/README.md) |

## 功能 vs 技术 vs 特性的关系

- 想看**系统有什么功能、做到哪了** → [function/00-功能模块概览.md](function/00-功能模块概览.md)
- 想看**技术怎么实现、为什么这么选** → [technology/](technology/)
- 想看**某个特性的需求/设计/计划** → [features/](../features/)
- 想看**里程碑级落地进度** → [plans/2026-08-27-产品落地计划.md](plans/2026-08-27-产品落地计划.md)

## plans/ 目录

| 文档 | 说明 |
|------|------|
| [2026-08-27-产品落地计划.md](plans/2026-08-27-产品落地计划.md) | 里程碑级（MS-00~MS-15）落地计划与进度跟踪（**跨模块权威**） |

## reviews/ 目录

| 文档 | 说明 |
|------|------|
| [code-review.md](reviews/code-review.md) | 2026-08-19 首次代码审查报告 |
| [code-review-2026-08-29.md](reviews/code-review-2026-08-29.md) | 2026-08-29 深度审查报告（含修复闭环） |
| [code-review-2026-09-03.md](reviews/code-review-2026-09-03.md) | 2026-09-03 三端全量审查报告（双轴方法，13 处 P1） |
| [code-review-lessons.md](reviews/code-review-lessons.md) | 代码审查经验沉淀（滚动文档，评审/开发前参考） |

> 一次性审查报告归档于 reviews/；长期沉淀的**问题模式与方法论**见 [code-review-lessons.md](reviews/code-review-lessons.md)。
