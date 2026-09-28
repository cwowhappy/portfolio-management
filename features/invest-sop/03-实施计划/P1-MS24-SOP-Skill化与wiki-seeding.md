# 投资 SOP 实施计划 P1 · MS-24：SOP Skill 化与 wiki seeding

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 M16-F02/F03——四个 SOP SKILL.md（按用户启停、Agent 按阶段引导产出结构化草稿）+ `research_draft` 只读工具 + 前端 DraftCard（确认后落库，D9/D20）+ SOP 模板 wiki 幂等 seeding；F01 内容定稿前以结构先行 + 示例内容占位、定稿后回填。

**Architecture:** 草稿通道完全照 chart 双通道先例：后端 `agent/research/ResearchDraftSpec.java`（sealed interface 四变体）→ 只读 @Tool `research_draft` 入参即草稿（toolkit JSON Schema 约束）→ 前端 `lib/research-draft.ts` zod 镜像 + `DraftCard` 渲染（safeParse）+ 「保存到项目」按钮（v1 只做草稿卡片渲染与本地暂存态，落库 API 在 P2 交付后接通——本计划前端保存动作为 no-op 提示）。wiki seeding 照 `WikiApplicationService.seedPresetConcepts` 幂等模式扩展 SOP 模板（独立标记列）。

**Tech Stack:** Java 21 / AgentScope 2.0.3（ToolResultBlock / toolkit 参数 schema）、Jackson 2、Next.js 15 + zod + vitest、Flyway（`wiki_seed_state` 加列随 P2 V2 迁移，本计划不动 schema——**seeding 任务排在 P2 V2 合入之后执行**）。

**Spec:** [需求规格说明 v1.0](../01-需求规格/需求规格说明.md) F02/F03 + D1/D9/D19/D20；[设计规格说明](../02-设计规格/设计规格说明.md) §1.2-S7、§4.2、§6。

## Global Constraints

- **ArchUnit 测试规范**：`given…when…then…` 方法名 + 中文 `@DisplayName`；test 源集禁 Testcontainers；AssertJ + Mockito 字段式 `mock(X.class)`；覆盖率 ≥80%。
- **agent 包依赖**：只读工具不 import `application/research`（P1 时该包尚不存在）；`agentOnlyAccessWhitelistedPackages`（`PackageConventionsTest.java:80-81`）允许 agent→application/domain/config，不得新增越界依赖。
- **Skill 契约**：front-matter 键集 = `name/description/category/default_enabled/depends_on_provider`（消费点 `SkillView.java:13-15`）；`default_enabled: false`；`effective()` 语义见 `SkillApplicationService.java:66-74`。
- **序列化**：spec 类逐字段 `@JsonInclude(NON_NULL)`（照 `ChartSpec.java` 注释：wire 契约 ALWAYS 会发 null，前端 zod `.optional()` 不接受 null）。
- **定向测试命令**：`cd backend && ./gradlew test --tests '*ResearchDraft*' --console=plain`；前端 `cd frontend && pnpm vitest run tests/lib/research-draft.test.ts`（勿用 `pnpm test --` 过滤）。
- **commit scope**：`feat(agent): …` / `feat(ui): …` / `docs(skills): …`，中文描述。
- **F01 内容依赖（已解锁）**：F01 已定稿（2026-09-28 用户逐条评审确认）——内容源 = [SOP v1 内容定稿](../04-研发过程/2026-09-28-SOP-v1-内容定稿.md)（61 条生效：删 1.10、3.9 转页面文案）。SKILL.md 正文与 `sop-templates.json` 内容块**直接取定稿清单对应阶段条目**；测试只断言结构与键，不断言内容文本。

## Review Focus

- zod 镜像收到含 `null` 字段的 spec（后端漏 `@JsonInclude`）→ safeParse 应失败并渲染降级文本卡，不白屏。
- `research_draft` 收到非法 stage 参数 → 工具返回错误文本（不抛异常中断会话）。
- DraftCard 对 `specVersion` 不认识的草稿 → 渲染「草稿版本不兼容」占位，不崩。
- SOP seeding 在用户已删光全部模板后再次访问 → 不复活（标记不随条目删除）。
- 四个 SKILL.md 任一未启用 → 不进 Agent 上下文（`SkillFilter.only`），但 seeding 与 wiki 模板不受影响（两条线独立）。

---

## 事实锚点（实现者必读，均已对照真实代码核实）

- `ChartSpec.java:1-54`：sealed interface + `specVersion()/type()` 判别组件 + 每 record `@JsonInclude(NON_NULL)`——`ResearchDraftSpec` 逐字照此模式。
- `InvestTools.java:50` 注入 Spring 已配 ObjectMapper；`:89-97` kline 模式：空数据返回 `ToolResultBlock.text(...)`，有数据返回文本摘要 + 结构块（`.put(...)` spec）双通道。
- 前端消费链：`toolRenderers.tsx`（工具名→renderer 注册表）→ `ChartCard.tsx:36` `ChartSpecSchema.safeParse(json)` 判别联合分发。
- `skills/tushare_data/SKILL.md:1-7`：front-matter 实键 `name/description/category/default_enabled/depends_on_provider`，`default_enabled: false`。
- `HarnessAgentFactory.java:38-46,62-64`：`enabledSkillCodes(userId)` → `SkillFilter.only(enabled…)`；skill 目录经 `builtInSkillRepository` 装配。
- `WikiApplicationService.java:29-48`：`entries()` 在 `type == CONCEPT` 时 `seedPresetConcepts(userId)`；`:39-41` `existsByUserId` 先查标记；`:43-47` 写 `PresetConceptCatalog` 后 `insert(userId)`——「幂等：删光不复活」。
- `wiki_seed_state`（`V1__baseline.sql:472-475`）：`user_id PK + seeded_at`；SOP 模板 seeding 需**新标记列** `sop_seeded_at`（老用户已 seeded CONCEPT，不能复用同一行判定）——随 P2 `V2__research.sql` 落列。
- `WikiEntryType`（`domain/wiki/WikiEntryType.java`）：`BOOK_NOTE / CONCEPT / RESEARCH_NOTE` 三值；SOP 模板复用 `RESEARCH_NOTE` + `category='SOP_TEMPLATE'`（设计 S2）。
- vitest 先例：`frontend/tests/lib/api.test.ts`（mock fetch + schema 校验断言）。

---

### Task 1: ResearchDraftSpec 后端契约

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/agent/research/ResearchDraftSpec.java`
- Test: `backend/src/test/java/com/portfolio/invest/agent/research/ResearchDraftSpecTest.java`

**Interfaces:**
- Consumes: 无（纯 record 契约）。
- Produces: `sealed interface ResearchDraftSpec { int specVersion(); String stage(); }`；变体 `AnalysisDraft(int specVersion, String stage, String symbol, String companyName, String industry, List<String> checklistDone, String summary)`、`StrategyDraft(int specVersion, String stage, String thesis, BigDecimal valuationLow, BigDecimal valuationHigh, String positionPlan, String buyConditions, List<FalsifierItem> riskItems)`、`EntryPlanDraft(int specVersion, String stage, List<BatchItem> batches, BigDecimal winRate, BigDecimal payoffRatio, String note)`、`ReviewDraft(int specVersion, String stage, String tier, String periodStart, String periodEnd, String narrative)`；嵌套 `FalsifierItem(String kind, String predicate, BigDecimal threshold, String note)`、`BatchItem(BigDecimal priceLow, BigDecimal priceHigh, long quantity, BigDecimal ratio)`；常量 `int CURRENT_VERSION = 1`；静态工厂 `ResearchDraftSpec.analysis(...)/strategy(...)/entryPlan(...)/review(...)`（内部盖 specVersion/stage）。

- [ ] **Step 1: 写失败测试**

```java
@Test
@DisplayName("序列化剥 null 字段且携带 specVersion/stage 判别组件")
void givenStrategyDraft_whenSerialized_thenNoNullFieldsAndDiscriminatorsPresent() throws Exception {
    String json = MAPPER.writeValueAsString(ResearchDraftSpec.strategy(
            new BigDecimal("12.5"), new BigDecimal("18.0"), "逻辑", null, "条件", List.of()));
    assertThat(json).contains("\"specVersion\":1").contains("\"stage\":\"STRATEGY\"");
    assertThat(json).doesNotContain("null");
}
```

（`MAPPER` 为静态 `new ObjectMapper()`；另测三变体与 `CURRENT_VERSION`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests '*ResearchDraftSpecTest' --console=plain`
Expected: FAIL 编译错误（类不存在）

- [ ] **Step 3: 最小实现**

按 Interfaces 块创建 sealed interface 与 record，每个 record 与嵌套 record 标 `@JsonInclude(JsonInclude.Include.NON_NULL)`，工厂方法内部填 `specVersion=CURRENT_VERSION` 与对应 `stage` 字符串（`NEW_ANALYSIS/STRATEGY/POSITION/REVIEW`）。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/agent/research/ backend/src/test/java/com/portfolio/invest/agent/research/
git commit -m "feat(agent): ResearchDraftSpec 草稿契约（四变体 sealed interface，照 ChartSpec 模式）"
```

### Task 2: research_draft 只读工具

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/agent/InvestTools.java`（追加方法）
- Test: `backend/src/test/java/com/portfolio/invest/agent/InvestToolsResearchDraftTest.java`

**Interfaces:**
- Consumes: Task 1 `ResearchDraftSpec` 全部工厂。
- Produces: `@Tool` 方法 `String researchDraft(String stage, String draftJson)`——入参 stage ∈ 四值、draftJson 为草稿字段 JSON；返回文本（校验通过：回显结构化摘要；不通过：`[research_draft] 参数错误：<原因>` 文本，不抛异常）。JSON 解析用已注入 ObjectMapper（`InvestTools.java:50`）。

- [ ] **Step 1: 写失败测试**（成功回显、非法 stage、非法 JSON 三例，断言返回文本含 `[research_draft]` 前缀或摘要标记）
- [ ] **Step 2: 跑测试确认失败**（方法不存在，编译错误）
- [ ] **Step 3: 实现**——解析 draftJson → 按 stage 构造对应变体（字段缺失容忍：null 允许，类型错报错）→ 返回 `摘要 + "\n```research-draft\n" + 序列化 + "\n```"` 围栏块（前端按围栏标记提取，不依赖 tool result 结构块——与 chart 的差异点：草稿走文本围栏通道，避免自定义 ToolResultBlock 类型）。
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: Commit** `feat(agent): research_draft 只读工具（文本围栏双通道，readOnly 无副作用）`

### Task 3: zod 镜像 + DraftCard

**Files:**
- Create: `frontend/lib/research-draft.ts`
- Create: `frontend/components/chat/DraftCard.tsx`
- Modify: `frontend/components/chat/toolRenderers.tsx`（注册 research_draft 文本渲染入口：扫描助手消息内 ` ```research-draft ` 围栏）
- Test: `frontend/tests/lib/research-draft.test.ts`、`frontend/tests/components/DraftCard.test.tsx`

**Interfaces:**
- Consumes: Task 2 围栏格式 ```` ```research-draft {json} ``` ````。
- Produces: `ResearchDraftSchema = z.discriminatedUnion("stage", [AnalysisDraftSchema, StrategyDraftSchema, EntryPlanDraftSchema, ReviewDraftSchema])`（数值用 `z.number().optional()`，**不接受 null**）；`DraftCard({ raw }: { raw: string })` 组件：safeParse 成功 → 表单化只读展示 + 「保存到项目」按钮（P1 阶段 onClick 弹 toast「研究项目功能即将上线」，P2 接通 API）；失败/版本不识别 → 降级文本卡「草稿格式不兼容」。

- [ ] **Step 1: 写失败测试**——合法四变体 parse 通过；含 `null` 字段拒绝；未知 `specVersion` 拒绝（schema 限定 `specVersion: z.literal(1)`）；DraftCard 降级渲染。
- [ ] **Step 2: `pnpm vitest run tests/lib/research-draft.test.ts` 确认失败**
- [ ] **Step 3: 实现 schema 与组件**（组件样式照 `InterruptApprovalCard.tsx` 卡片壳，标题按 stage 中文映射）
- [ ] **Step 4: 跑两个测试文件确认通过**
- [ ] **Step 5: Commit** `feat(ui): 草稿 zod 镜像与 DraftCard（safeParse + 降级兜底）`

### Task 4: 四个 SKILL.md

**Files:**
- Create: `backend/src/main/resources/skills/new-analysis/SKILL.md`（同目录 strategy/ position/ review/）
- Test: 扩展 `backend/src/test/java/com/portfolio/invest/application/skill/`（既有 skill 目录测试类追加断言）

**Interfaces:**
- Consumes: Task 2 `research_draft(stage, draftJson)` 用法。
- Produces: 四文件，front-matter 依次 `name: sop-new-analysis / sop-strategy / sop-position / sop-review`、`category: sop`、`default_enabled: false`、`depends_on_provider:` 留空；正文结构固定四节：`## 目标` / `## 必须有清单`（F01 定稿内容回填位，先放评审中示例） / `## 引导流程`（逐要素收集话术 + 每完成一节调一次 `research_draft` 回显） / `## 纪律提醒`（strategy 篇含 D19 建议项话术：建议在证伪条件勾选「跌破估值区间下限」模板项）。

- [ ] **Step 1: 扩展 skill 目录测试**——断言目录含 6 个 skill（2 既有 + 4 新）、四新 skill 的 `default_enabled` 均为 false、`category == "sop"`（照既有目录测试断言风格）。
- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 写四文件**（checklist 正文取 F01 定稿清单对应阶段条目，逐条对应不做增删）
- [ ] **Step 4: 跑测试确认通过 + 全量 `./gradlew test --tests '*Skill*'` 无回归**
- [ ] **Step 5: Commit** `docs(skills): 四个 SOP SKILL.md（结构先行，内容待 F01 定稿回填）`

### Task 5: SOP 模板 seeding（**前置：P2 Task 1 V2 迁移已合入**）

**Files:**
- Create: `backend/src/main/resources/wiki/sop-templates.json`
- Modify: `backend/src/main/java/com/portfolio/invest/application/wiki/WikiApplicationService.java`
- Modify: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/`（`WikiSeedState` 实体/JPA 加 `sopSeededAt` 列映射）
- Test: `backend/src/test/java/com/portfolio/invest/application/wiki/WikiSopSeedingTest.java`
- Test（集成）: `backend/src/integrationTest/java/com/portfolio/invest/application/wiki/WikiSopSeedingIntegrationTest.java`

**Interfaces:**
- Consumes: `V2__research.sql` 中 `ALTER TABLE wiki_seed_state ADD COLUMN sop_seeded_at TIMESTAMPTZ`（P2 Task 1 交付）；`WikiApplicationService.entries()` 既有入口。
- Produces: `entries()` 追加逻辑——`type == RESEARCH_NOTE` 时 `seedSopTemplates(userId)`：`sopSeededAt != null` 直接返回；否则批量写 `WikiEntry`（`RESEARCH_NOTE` + `category="SOP_TEMPLATE"`，title=「SOP·<阶段>·<模板名>」）后置 `sopSeededAt`。JSON 条目结构：`[{ "stage": "NEW_ANALYSIS", "name": "公司基本面清单", "content": "…" }]`（内容 F01 回填位）。

- [ ] **Step 1: 写失败切片测试**——首次调用写 N 条 + 置标记；二次调用零新增；用户删光后调用零新增（mock repository 断言 save 次数）。
- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现 seeding + JSON 内容取 F01 定稿四阶段模板条目**
- [ ] **Step 4: 切片测试通过 + 集成测试（真实 PG： seeding 幂等、RESEARCH_NOTE 列表含 SOP_TEMPLATE、CONCEPT seeding 不受影响）通过**
- [ ] **Step 5: Commit** `feat(wiki): SOP 模板幂等 seeding（独立标记列，删光不复活）`

### Task 6: P1 收口

- [ ] `make test-backend-unit` + `pnpm vitest run`（前端全量）全绿；`git push` 前 `./gradlew check` 通过。
- [ ] 更新本文件勾选状态；MS-24 代码侧完成（F01 内容评审另行跟踪）。
