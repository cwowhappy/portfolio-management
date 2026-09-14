# 06 · Skill 系统

> AgentScope Skill（数据源使用说明书）的内置目录、用户启用集与按用户装配。
> Agent 装配链路见 [01-Agent实现.md](01-Agent实现.md)；MCP 工具接入见 [05-MCP数据源集成.md](05-MCP数据源集成.md)。
> 对应代码：`backend/src/main/java/com/portfolio/invest/web/SkillConfigController.java`、`application/skill/`、`domain/skill/`、`infrastructure/persistence/Skill*`（仓库实现）、`backend/src/main/resources/db/migration/V11__skill.sql`、`backend/src/main/resources/skills/tushare_data/SKILL.md`、`backend/src/main/resources/skills/wind_finance/SKILL.md`、`backend/src/main/java/com/portfolio/invest/agent/HarnessAgentFactory.java`（装配）；前端 `frontend/app/settings/skills/page.tsx` + `frontend/components/skill/SkillSettingsPage.tsx` + `frontend/lib/skillApi.ts`（`application/skill/`、`domain/skill/`、`infrastructure/persistence/` 均相对 `backend/src/main/java/com/portfolio/invest/`）。

## 1. 概述

Skill 是**提示词层的领域知识**：一份 SKILL.md 告诉模型「某数据源覆盖什么、何时用、怎么取数、如何标注来源」。与 MCP 的分工——MCP 提供**工具**（能用什么），Skill 提供**用法**（怎么用）。本模块：

- **目录在 classpath**：内置 skill 以 `resources/skills/**/SKILL.md` 随应用发布，**DB 不存目录**，只存用户选择（`V11__skill.sql` 单表 `skill_user_config`）——目录与代码同版本演进，无目录漂移问题。
- **用户级启用集**：每个用户独立勾选；未选择时按 SKILL.md front-matter 的 `default_enabled` 兜底（两个内置 skill 均 `false`）。
- **装配进 HarnessAgent**：`HarnessAgentFactory` 用 `SkillFilter.only(启用集)` 装配，未启用 skill 不进入 Agent 上下文。

## 2. 架构与数据流

### 2.1 内置目录（resources/skills/）

两个 `category: data_source` 的 skill，front-matter metadata 经 `SkillView` 透出：

| skill | depends_on_provider | 定位 |
|---|---|---|
| `tushare_data` | `tushare` | 广度兜底：期货期权、港美股、债券、宏观序列、财务三表明细、指数成分（220+ 接口） |
| `wind_finance` | `wind` | 机构级数据与文档：公告/年报/招股书、财经新闻、宏观 EDB、跨标的聚合排名（7 个 server_type 路由表） |

两者 SKILL.md 内含明确的「用/不用」触发条件与分工规约（与 `InvestSystemPrompt` 的「MCP 扩展数据源 / Skill 数据源技能」章节口径一致：内置工具优先，MCP 兜底，skill 给出取数流程）。

### 2.2 装配链路

```
SkillApplicationService.enabledSkillCodes(userId)
    = 目录全集 × 用户选择（skill_user_config 覆盖 default_enabled）
HarnessAgentFactory.build(userId)
    .skillRepository(builtInSkillRepository)     # ClasspathSkillRepository（AgentScope 内置）
    .skillFilter(SkillFilter.only(启用 codes))   # 只装启用 skill
    .disableDefaultWorkspaceSkills()             # 关闭 AgentScope 默认 workspace skills
    .disableDynamicSkills()                      # 关闭运行时动态发现
```

### 2.3 用户选择语义（skill_user_config）

- `effective(skill) = 用户选择 ?? default_enabled`——用户显式选择（含关闭）永久覆盖默认值。
- `save` 为**全量保存**：对目录中每个 skill upsert 一行（在集合内 = 启用，不在 = 停用），缺行 skill 由 `effective` 兜底。

### 2.4 与 MCP 的关系（独立配置，前端提示依赖）

`depends_on_provider` 仅是**展示元数据**（设置页渲染「依赖 tushare/wind」徽标）：启用 skill 不自动启用对应 MCP provider，也不校验其可用性——provider 启用走 [05-MCP数据源集成.md](05-MCP数据源集成.md) 的个人配置；两者由用户各自管理。

## 3. 关键类与配置

| 类/文件 | 职责 |
|---|---|
| `web/SkillConfigController` | 2 个端点（见 §4），归属当前登录用户 |
| `application/skill/SkillApplicationService` | 目录（classpath 全集 + 启用态）、`enabledSkillCodes`（装配用）、全量保存 |
| `application/skill/SkillView` | 目录视图（skillCode/description/category/defaultEnabled/dependsOnProvider/enabled） |
| `domain/skill/SkillUserConfig` | 不可变领域对象（user_id + skill_code + enabled），`update` 返回新实例 |
| `domain/skill/SkillConfigRepository` | 仓库端口（findByUserId / findByUserIdAndSkillCode / save） |
| `agent/HarnessAgentFactory` | `ClasspathSkillRepository` + `SkillFilter.only` 按用户装配 |

无独立配置键；skill 目录路径由 `ClasspathSkillRepository` 约定（classpath `skills/`）。

## 4. 端点（统一前缀 `/api/skills`，需登录）

| 端点 | 说明 |
|---|---|
| `GET /api/skills` | 内置目录 + 我的启用态（`SkillView` 列表） |
| `PUT /api/skills/config` | 全量保存启用集（`{enabled: [skillCode...]}`），返回最新目录 |

错误码：`SkillErrorCode.INVALID_INPUT` → 400（`GlobalExceptionHandler`）。

## 5. 测试

| 层 | 位置 | 覆盖 |
|---|---|---|
| 单测（application） | `backend/src/test/java/com/portfolio/invest/application/skill/SkillApplicationServiceTest` | 目录/effective 覆盖语义/全量保存 |
| 单测（application） | `backend/src/test/java/com/portfolio/invest/application/skill/BuiltInSkillCatalogTest` | 内置目录完整性（两个 SKILL.md 可解析、metadata 齐全） |
| 单测（domain） | `backend/src/test/java/com/portfolio/invest/domain/skill/SkillUserConfigTest` | 领域对象校验 |
| 集成 | `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/SkillConfigRepositoryImplTest` | Testcontainers 真实 PostgreSQL 读写 |
| 测试资源 | `backend/src/test/resources/test-skills/` | 测试专用 skill 目录（不依赖生产目录内容） |
| 前端单测 | `frontend/tests/lib/skillApi.test.ts`、`skillRoute.test.ts` | API 封装与路由 |

## 6. 已知限制

- **目录随发版**：新增/修改 skill 需改代码重新部署，不支持运行时上传或管理端维护。
- **依赖不联动**：`depends_on_provider` 仅前端提示，后端不校验「启用 skill 时对应 provider 是否启用/有 token」——组合出「有说明书无工具」的状态时，模型只能依赖提示词兜底。
- **保存为全量覆盖**：并发多端同时保存同一用户配置为后写覆盖（与会话一致的取舍）。
- **单表无版本**：`skill_user_config` 无 config_version（对比 MCP 的 mcp_user_config 有），保存不追踪变更次数。
