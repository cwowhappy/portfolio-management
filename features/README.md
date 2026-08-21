# features · 新特性开发文档

> 本目录用于收集**开发新特性**过程中的产出文档，按 superpowers 插件的开发阶段组织。
> 现有产品功能见 [docs/function/](../docs/function/)，技术实现见 [docs/technology/](../docs/technology/)。

## 目录结构（对应 superpowers 开发阶段）

superpowers 工作流中，仅「设计」与「计划」两个阶段产出持久化文档并收于本目录；实现、测试、审查等阶段产物为代码 / 提交 / 评审意见，不入本目录。

| 子目录 | 开发阶段（superpowers 技能） | 产出物 | 命名约定 |
|---|---|---|---|
| [specs/](specs/) | `brainstorming`（头脑风暴 / 设计） | 设计文档（规格说明） | `YYYY-MM-DD-<主题>-design.md` |
| [plans/](plans/) | `writing-plans`（编写实施计划） | 实施计划 | `YYYY-MM-DD-<特性名>.md` |

## 开发流程（superpowers 工作流）

1. **brainstorming**（头脑风暴/设计）→ 产出设计文档，落入 `specs/`
2. **writing-plans**（编写实施计划）→ 产出实施计划，落入 `plans/`
3. **executing-plans / subagent-driven-development**（执行计划）→ 按计划逐任务实现（产物为代码）
4. **test-driven-development**（测试驱动开发）→ 红-绿-重构，随实现进行
5. **requesting-code-review / receiving-code-review**（代码审查）
6. **verification-before-completion**（完成前验证）
7. **finishing-a-development-branch**（收尾合并）

## 命名约定（沿用 superpowers）

- 设计文档：`specs/YYYY-MM-DD-<主题>-design.md`，例如 `specs/2026-08-20-持仓组合管理-design.md`
- 实施计划：`plans/YYYY-MM-DD-<特性名>.md`，例如 `plans/2026-08-20-持仓组合管理.md`

## 使用方式

开发新特性时，先按 superpowers 的 `brainstorming` 产出设计文档到 `specs/`，再按 `writing-plans` 产出实施计划到 `plans/`；两者命名保持同一日期与主题，便于相互对应。
