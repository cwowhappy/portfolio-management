# 投资知识库（MS-11 / M13）设计文档

> 创建：2026-09-22 | 状态：待评审
> 依据：[产品落地计划](../../plans/2026-08-27-产品落地计划.md) MS-11、[M13 投资知识库](../../function/modules/13-投资知识库.md)
> 交付范围：M13-F01 投资原则与纪律设定 / F02 读书笔记 / F03 概念速查

## 一、背景与目标

MS-11 投资知识库（二期，无硬依赖）：沉淀投资原则、读书笔记与核心概念，让纪律可见、可被系统引用。

**验收标准**（产品落地计划）：

1. 原则规则**结构化存储**，供三期 MS-15 原则预警消费（如「单票超 20%」「PE>40 不买」触发提醒）；
2. M10 行业研究结论可保存至知识库。

## 二、已确认的关键决策

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | F01 规则建模 | **参数化规则模型**：指标枚举 + 阈值 + 启停，首批 4 种指标；不做通用表达式 |
| 2 | M10 联动 | 行业下钻页 `/industry/[industryCode]` 加「保存研究结论」入口，存为知识库 `RESEARCH_NOTE`（完整笔记区块属三期 M10-F12，不做） |
| 3 | F03 内容源 | 应用层首次访问 seeding 预置约 10 条经典概念 + 用户可增删改（开箱即用，机制见 §4.3） |
| 4 | Markdown 体验 | textarea 编辑 + **编辑/预览切换** + 列表/详情 react-markdown 渲染；不引入编辑器库 |
| 5 | 域组织 | **方案 A**：单域 `domain/wiki`，一张 `wiki_entry` 内容表（type 列）+ 一张 `principle_rule` 规则表；不扩展 journal 域 |

命名遵循仓库「域名 = API 前缀 = 页面路由」惯例：`domain/wiki` + `/api/wiki` + 前端 `/wiki` + `lib/wikiApi.ts`。

## 三、数据模型（Flyway V16）

新迁移 `backend/src/main/resources/db/migration/V16__wiki.sql`（当前最新 V15）：

```sql
CREATE TABLE wiki_entry (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id),
    type          VARCHAR(16)  NOT NULL,   -- BOOK_NOTE / CONCEPT / RESEARCH_NOTE
    title         VARCHAR(200) NOT NULL,   -- 概念词条即术语名本身，不另设 term 列
    content       TEXT NOT NULL,           -- Markdown
    category      VARCHAR(50),             -- CONCEPT 专用：分类（估值/质量/行为…）
    industry_code VARCHAR(16),             -- RESEARCH_NOTE 专用：申万一级行业代码
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wiki_entry_user_type ON wiki_entry(user_id, type, updated_at DESC);

CREATE TABLE principle_rule (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    metric      VARCHAR(32) NOT NULL,      -- 指标枚举（见 3.1）
    threshold   NUMERIC(12,4) NOT NULL,    -- 单位由枚举语义约定
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),              -- 可选：给三期预警展示用的规则说明
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_principle_rule_user_metric UNIQUE (user_id, metric)
);

CREATE TABLE wiki_seed_state (
    user_id    BIGINT PRIMARY KEY REFERENCES app_user(id),
    seeded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 3.1 指标枚举（首批 4 种）

| metric | 含义 | 单位与校验边界 | 示例阈值 |
|---|---|---|---|
| `SINGLE_POSITION_RATIO` | 单票市值占组合比例上限 | 比例，`0 < t ≤ 1` | 0.20 |
| `INDUSTRY_POSITION_RATIO` | 单一行业仓位上限 | 比例，`0 < t ≤ 1` | 0.35 |
| `STOCK_PE_MAX` | 个股 PE(TTM) 上限 | 倍数，`t > 0` | 40 |
| `STOCK_PB_MAX` | 个股 PB 上限 | 倍数，`t > 0` | 6 |

### 3.2 有意识的取舍

1. **`UNIQUE(user_id, metric)`——每指标至多一条规则**：同指标双阈值无意义，三期预警每指标取唯一阈值消费最简；将来差异化规则是三期加 scope 列的进化（YAGNI）。
2. **CONCEPT 不设 term 列**：title 即术语名，少一个冗余字段。
3. **`wiki_entry` 单表 + type 列**：与 journal 单表 4 类型先例同构；类型特有列在其它类型上为 NULL。
4. **乐观锁**：两表均带 `version` 列（V9 模式，JPA `@Version`）。

## 四、后端设计

### 4.1 `domain/wiki`（纯 POJO，零 Spring/JPA）

- `WikiEntry` — 不可变聚合根：静态工厂 `create(userId, type, title, content, category, industryCode, now)` / `reconstitute(...)`，实例方法 `update(...)` 返回新实例；私有 `validate`：title 非空 ≤200、content 非空。类型特有列不做强校验（`industry_code` 由行业入口带入，手工创建可不填——校验从轻，避免堵死用户路径）。
- `WikiEntryType` 枚举：`BOOK_NOTE` / `CONCEPT` / `RESEARCH_NOTE`。
- `PrincipleRule` — 不可变聚合根，同款工厂模式；`validate` 按 `PrincipleMetric` 单位语义校验阈值（比例 `0 < t ≤ 1`、倍数 `t > 0`）、description ≤500。
- `PrincipleMetric` 枚举 — 上述 4 值 + `isRatio()` 单位语义；三期预警消费同一枚举。
- `WikiEntryRepository` / `PrincipleRuleRepository` 端口接口：`findByUserId(userId, type)`、`findByIdAndUserId(id, userId)`、`save`、`deleteById`（journal 仓库签名风格）。
- `WikiErrorCode`（INVALID_INPUT / NOT_FOUND / DUPLICATE_METRIC）+ `WikiException`。

### 4.2 `application/wiki`

- `WikiApplicationService`：`entries(userId, type)` / `getEntry` / `createEntry` / `updateEntry` / `deleteEntry`；`requireEntry(userId, entryId)` 归属校验（缺失抛 NOT_FOUND）；事务边界在此层。
- `PrincipleRuleApplicationService`：`rules(userId)` / `createRule` / `updateRule` / `deleteRule`；UNIQUE 冲突（`DataIntegrityViolationException`）翻译为 `WikiException(DUPLICATE_METRIC)`，走既有 `GlobalExceptionHandler` 领域异常 → HTTP 映射。
- `PresetConceptCatalog` — 读 classpath 资源 `resources/wiki/preset-concepts.json`（约 10 条经典概念，含 title/category/content Markdown 正文；内容执笔：护城河、安全边际、能力圈、市场先生、内在价值、复利、均值回归、ROE、PE、PB）。Skill 域 classpath 资源随 jar 分发的同款先例。
- DTO（record + jakarta 校验）：`CreateWikiEntryCommand` / `UpdateWikiEntryCommand` / `WikiEntryView` / `CreatePrincipleRuleCommand` / `UpdatePrincipleRuleCommand` / `PrincipleRuleView`。

### 4.3 预置 seeding 机制

`entries(userId, CONCEPT)` 首次调用时，同事务内「查 `wiki_seed_state` 无该用户记录 → 批量插入预置词条 → 写标记」。幂等由标记表 PK 保证；用户删光预置词条后不会复活（标记仍在）。GET 带一次性批量写副作用是有意识的小取舍，换来：全库数据 user-scoped、CRUD 完全统一、无 NULL 行/合并逻辑。

### 4.4 `web/WikiController`

全部 authenticated，**不进** `PublicEndpointPaths`（知识库是个人数据）：

- `GET /api/wiki/entries?type=`（缺省全量）/ `POST /api/wiki/entries`（201）
- `GET/PUT/DELETE /api/wiki/entries/{entryId}`
- `GET /api/wiki/rules` / `POST /api/wiki/rules`（201）/ `PUT/DELETE /api/wiki/rules/{ruleId}`

`currentUserId(auth)`（`((AuthenticatedUser) auth.getPrincipal()).user().id()`）提取 userId，与 journal 控制器逐字同款；用户隔离三重保障（Controller 提取 → 用例 userId 首参 → 仓储 `findByIdAndUserId`）。

### 4.5 工程配套

- `PackageConventionsTest` 白名单按层 `..` 通配，**无需改动**；更新 DDD 分包规范文档（12→13 域）。
- 事务、JPA、jsonb 等模式全部复用既有先例（journal / risk_assessment）。

## 五、前端设计

### 5.1 `/wiki` 页面

- `app/wiki/page.tsx`：server component，`<RequireAuth><WikiBoard /></RequireAuth>`（journal 同款）。
- `components/wiki/WikiBoard.tsx`（"use client"）四 tab：**原则纪律 / 读书笔记 / 概念速查 / 研究笔记**；支持 `?tab=` 深链初始 tab。
- **原则 tab**：规则卡片列表（指标中文名 + 按单位格式化阈值 + 启停开关 + 编辑/删除）；「添加规则」表单——指标下拉（已配置指标置灰，服务端 UNIQUE 兜底）、阈值输入按单位切换（比例类显示 `%` 输入、存储 0~1；倍数类直接数值）、说明可选。
- **三个内容 tab**（读书笔记/概念/研究笔记共用组件，概念 tab 编辑器多一个分类输入）：列表 + 编辑器（title + content textarea + **编辑/预览切换**）+ 详情 Markdown 渲染。
- `components/shared/MarkdownView.tsx`：从 chat `ThreadArea` 抽出 ReactMarkdown 配置（remark-gfm、urlTransform、CodeBlock/InlineCode）为共享组件；wiki 与 chat 共用，chat 自定义渲染器经 props 保留、行为不变。

### 5.2 配套

- `lib/wikiApi.ts`：entries/rules CRUD 客户端；zod schema 入 `lib/schemas.ts`、类型入 `lib/types.ts`（`lib/http.ts` `request<T>` 统一出口）。
- `app/api/wiki/[...path]/route.ts`：`lib/proxy.ts` `relay` 反代（每域一文件惯例）。
- `app/layout.tsx` 顶部导航加「知识库」链接。

### 5.3 M10 联动（`IndustryDrilldown.tsx`）

- 页头加「保存研究结论」按钮 → 弹窗表单：标题预填「{行业名} 研究结论」、content textarea + 预览切换、`industryCode` 自动带入 → `POST /api/wiki/entries (type=RESEARCH_NOTE)` → 成功提示 +「在知识库查看」链接跳 `/wiki?tab=research`。
- 行业页公开（无 RequireAuth）：未登录时**隐藏**该按钮（`useAuth()` 判空），不弹登录墙。

## 六、错误处理

- 领域层 `WikiException(WikiErrorCode, message)` → 既有 `GlobalExceptionHandler` 映射 HTTP（INVALID_INPUT/NOT_FOUND/DUPLICATE_METRIC）。
- 前端 `request<T>` 非 2xx 抛响应体 message；规则表单对 DUPLICATE_METRIC 行内提示（指标下拉置灰为主防线，服务端为兜底）。
- 乐观锁冲突沿用既有更新链路语义。

## 七、测试策略（TDD）

| 层 | 覆盖点 |
|---|---|
| 后端域单测 | `PrincipleMetric` 阈值边界（ratio 0~1 闭开区间、倍数 >0）、`WikiEntry` 校验（title/content 非空与长度）、两聚合不可变与工厂语义 |
| application 集成测试 | CRUD 往返、越权 404（userId 隔离）、**seeding 幂等**（二次拉取不重复、删光预置不复活）、UNIQUE 冲突 → DUPLICATE_METRIC |
| 前端 vitest | WikiBoard tab 切换、规则表单 % ↔ 0~1 换算、MarkdownView 渲染、`tests/lib/wikiRoute.test.ts` 反代测试 |
| Playwright e2e | `e2e/wiki.spec.ts`：注册登录 → 概念 tab 预置词条可见 → 建读书笔记且 Markdown 渲染 → 建原则规则 → 行业下钻页存研究结论（industry.spec 空库门控范式）→ `/wiki?tab=research` 可见 |

质量门槛：`make test` 三端全绿（后端覆盖率 ≥80%、前端 V8、无 collector 变更）；e2e 全绿；无新外部数据源，`make smoke` 不重跑（口径同 MS-07/08/09）。

## 八、明确不做（Out of Scope）

- 三期 MS-15 原则预警的**消费侧**（本里程碑只保证规则结构化落库可供消费）；
- M10-F12 完整行业研究笔记区块（编辑/列表/行业关联展示，三期）；
- 富文本/所见即所得编辑器（维持 textarea + 预览切换）；
- 概念词条的 term 独立列、规则的多条同指标、规则作用域（scope）；
- AI 工具入口（M05 系列属 MS-12）。
