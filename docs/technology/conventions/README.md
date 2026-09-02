# 技术规范（conventions/）

> 本目录收录**具有约束力**的技术规范：编码、分层、提交、评审等"必须遵守"的规则。
> 与 [modules/](../modules/)（模块设计，描述"怎么实现的"）和 [decisions/](../decisions/)（决策记录，说明"为什么这么选"）的区别在于：本目录的文档是**执行标准**，违反即构建失败或评审打回。

## 规范索引

| 编号 | 规范 | 约束方式 | 状态 |
|------|------|---------|:----:|
| 01 | [后端 DDD 分包规范](01-后端DDD分包规范.md) | ArchUnit 单测强制（`PackageConventionsTest.java`，违反即构建失败） | ✅ 生效 |
| 02 | [后端架构与代码规范](02-后端架构与代码规范.md) | ArchUnit（5 条）+ 测试 + 人工评审 | ✅ 生效 |
| 03 | [前端架构与代码规范](03-前端架构与代码规范.md) | eslint + vitest 门槛 + 人工评审 | ✅ 生效 |
| 04 | [采集服务架构与代码规范](04-采集服务架构与代码规范.md) | ruff + import-linter + pytest 门槛 + 人工评审 | ✅ 生效 |

## 生效中规范的要点

### 01 · 后端 DDD 分包规范

- 依赖方向只能向下：`web → application → domain`；`infrastructure → {domain, application, config}`；`agent` 为独立能力域；禁止反向、禁止成环。
- `domain/` 纯 POJO，零 Spring/JPA 注解；`config/` 仅配置属性，不得依赖业务包。
- 新增能力域/域包时：在 `PackageConventionsTest.java` 登记依赖白名单，并同步规范文档。

### 02 · 后端架构与代码规范（要点）

- 面向接口编程采方案 A：端口接口（domain/application 持有）+ 有装饰需求的服务接口；用例服务保持具体类，出现第二实现/装饰/替换需求时才抽接口（YAGNI）。
- 异常模型：每域 `XxxException` + 稳定 code（`XxxErrorCode` 常量），HTTP 映射集中在 `GlobalExceptionHandler`，错误体统一 `ApiError`。
- 事务边界只在 application 层；liveness 零外呼；permitAll 端点必须评估匿名消耗。
- ArchUnit 强制：`@Transactional` 只在 application、`@Entity` 只在 infrastructure.persistence、禁 `tools.jackson`、domain/application 禁 `System.currentTimeMillis`/`getenv`。

### 03 · 前端架构与代码规范（要点）

- `app/api/**` 只做反代且必须走 `relay()`；组件不直接 fetch，数据访问收敛在 `lib/`。
- 流式三件套铁律：取消/序号守卫、防抖 cleanup 必须 flush、节流 + memo 隔离。
- 跨网络边界响应必须 zod 校验，禁 `any`/裸 `as`；禁 `rehype-raw`/`dangerouslySetInnerHTML`。

### 04 · 采集服务架构与代码规范（要点）

- 事务铁律：捕获 `psycopg.Error` 必须 rollback 或丢弃连接；重试不复用连接；业务写与运维写事务分离。
- 调度：job 显式 `coalesce/max_instances/misfire_grace_time`；新任务必须回答"首次数据何时产出"。
- 涉事务/锁/SQL 的行为必须有真实 PG 测试（mock 通过 ≠ 真实通过）。
- 工具链：ruff（lint+format）+ uv compile 依赖锁定 + import-linter 分层合约，均入 CI。

## 待补充的规范（规划）

| 规范 | 现状 | 计划 |
|------|------|------|
| 接口设计约定 | 体现在 [modules/03-接口设计.md](../modules/03-接口设计.md)（错误模型、状态码映射） | 新增业务接口批量出现时抽取通用约定 |
| Git 提交/分支规范 | 未成文（历史提交为 `feat:/docs:/fix:` 风格） | 需要协作者加入前成文 |

> 一次性产出物（如 `docs/reviews/code-review.md` 代码评审报告）属于质量留档，不是规范，不收入本目录。
