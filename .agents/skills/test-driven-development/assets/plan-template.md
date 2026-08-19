# [功能名] TDD 实现计划

> **执行方式**：使用 test-driven-development skill，按 Red-Green-Refactor 逐条执行，每完成一批（默认 3 条测试）暂停汇报。
> **计划定位**：先写测试清单（想清楚行为），再逐条实现（先红后绿再重构）。

## 目标 (Goal)

[一句话说明要构建什么，可观察的行为是什么]

## 技术栈 (Tech Stack)

- 语言/框架：[Java 21 + Spring Boot 4 / TypeScript + React 19 / ...]
- 测试框架：[JUnit5 + AssertJ + Mockito / Vitest + RTL / ...]
- 覆盖率门槛：[如：指令/分支 ≥ 80%]
- 参考模式：[references/java.md / references/typescript.md]

## 测试清单 (Test List)

> 每条 = 一个可独立验证的行为/规则。按「从简到繁、从无依赖到有依赖」排序。实现中想到新测试随时补入；全部勾选即完成。

- [ ] T1: [行为描述] —— 期望：[期望结果]
- [ ] T2: [行为描述] —— 期望：[期望结果]
- [ ] T3: [边界/异常用例] —— 期望：[期望结果]

## 实现步骤 (Implementation Steps)

### Step 1 · T1 [行为描述]

- **RED** — 写失败测试：
  - 文件：`path/to/XxxTest.java`（或 `xxx.test.ts`）
  - 断言要点：[给定输入 → 期望输出]
  - 运行确认失败：`<命令，如 ./gradlew test --tests "...">`
- **GREEN** — 最小实现：
  - 文件：`path/to/Xxx.java`（或 `xxx.ts`）
  - 要点：[最小实现思路，允许硬编码/先造假]
- **REFACTOR** — [无 / 去重 / 提取方法 / ...]

### Step 2 · T2 [行为描述]

[同上结构]

## 验收标准 (Acceptance)

- [ ] 全部测试通过：`<命令，如 make test>`
- [ ] 覆盖率达标：[门槛值]
- [ ] 无回归：全量测试绿
