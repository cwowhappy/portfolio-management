# 技术规范（conventions/）

> 本目录收录**具有约束力**的技术规范：编码、分层、提交、评审等"必须遵守"的规则。
> 与 [modules/](../modules/)（模块设计，描述"怎么实现的"）和 [decisions/](../decisions/)（决策记录，说明"为什么这么选"）的区别在于：本目录的文档是**执行标准**，违反即构建失败或评审打回。

## 规范索引

| 编号 | 规范 | 约束方式 | 状态 |
|------|------|---------|:----:|
| 01 | [后端 DDD 分包规范](01-后端DDD分包规范.md) | ArchUnit 单测强制（`PackageConventionsTest.java`，违反即构建失败） | ✅ 生效 |

## 生效中规范的要点

### 01 · 后端 DDD 分包规范

- 依赖方向只能向下：`web → application → domain`；`infrastructure → {domain, application, config}`；`agent` 为独立能力域；禁止反向、禁止成环。
- `domain/` 纯 POJO，零 Spring/JPA 注解；`config/` 仅配置属性，不得依赖业务包。
- 新增能力域/域包时：在 `PackageConventionsTest.java` 登记依赖白名单，并同步规范文档。

## 待补充的规范（规划）

| 规范 | 现状 | 计划 |
|------|------|------|
| 前端代码规范 | 散见于 [modules/04-工程与运维.md](../modules/04-工程与运维.md) 与评审结论（TS strict、禁 `any`、zod 边界校验等） | 随 MVP 前端页面扩展时沉淀成文 |
| 接口设计约定 | 体现在 [modules/03-接口设计.md](../modules/03-接口设计.md)（错误模型、状态码映射） | 新增业务接口批量出现时抽取通用约定 |
| Git 提交/分支规范 | 未成文（历史提交为 `feat:/docs:/fix:` 风格） | 需要协作者加入前成文 |
| 测试编写规范 | 门槛已强制（JaCoCo/V8 ≥80%），写法约定散见于评审报告 | 随新模块测试编写时沉淀 |

> 一次性产出物（如 `docs/code-review.md` 代码评审报告）属于质量留档，不是规范，不收入本目录。
