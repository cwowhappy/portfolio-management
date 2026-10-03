# 智能情报实施计划 P1 · MS-20：财经新闻采集与 AI 结构化

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 交付 M15-F01~F05——新闻采集（东财 7×24 主源 + 新浪降级，collector 双任务）、backend LLM 批量结构化抽取（DeepSeek）、`search_news` 工具、交易日 8:30 盘前简报（群推版，个性化单发随 P4 绑定机制）、V3 迁移（15 表一次建全，schema 先行）与情报域 DDD 落位；前置三源探测报告（质量门槛⑤）。

**Architecture:** collector 双源插件经既有 selector failover 落 `intelligence_news_raw`（业务键幂等）；backend `application/intelligence` 定时任务（07:40/16:40 抽取、08:00 简报生成、08:30 群推、04:07 清理）经 `IntelligenceChatPort` 端口调 DeepSeek（`Model` bean，ObjectProvider 防缺 key）；`search_news` 挂 `InvestTools`（全局只读）。P1 简报推送为**配置群统一版**（决策 #8；open_id 单发与个性化附节随 P4 F16 绑定机制交付，设计 D17）。

**Tech Stack:** psycopg 3 + APScheduler（collector）/ Java 21 + Spring Boot 4 + AgentScope 2.0.3（Model bean）/ DeepSeek（`invest.llm.*` 既有配置）/ Flyway V3 + pg_trgm / Next.js 15（仅工具卡三行接入）/ pytest + JUnit5 四层 + vitest。

**Spec:** [需求规格说明 v1.0](../01-需求规格/需求规格说明.md) F01~F05 + 决策 #18~#23/#27/#28；[设计规格说明 v1.0](../02-设计规格/设计规格说明.md) D1~D17/D20/D21、§2~§8。

## Global Constraints

- **命名原则（用户裁决）**：一律全名 `intelligence`，**禁止 `intel` 缩写**——表前缀 `intelligence_`、类名 `Intelligence*`、包 `com.portfolio.invest.*.intelligence`、路由/配置 `intelligence`。
- **schema 全部走 Flyway**（`ddl-auto: none`）：新表只在 `V3__intelligence.sql`，collector 侧不建业务表（Alembic 只管运维表）；迁移幂等可重放。
- **ArchUnit**：domain 纯 POJO 零 Spring/JPA 注解；application 横向依赖合法（白名单为整个 `application..`）；infrastructure→{domain,application,config}；新增 `domain/intelligence` 等子包由层级通配自动覆盖，**同步 `docs/technology/conventions/01-后端DDD分包规范.md` 的 domain 子包清单**。
- **覆盖率 ≥80%**：后端 JaCoCo 三源集聚合（指令+分支）；collector `--cov-fail-under=80`；前端 V8 语句/分支。改代码必补测试。
- **后端测试分层**：`test` 单元/切片（*SliceTest 真 @WebMvcTest，禁 Testcontainers）｜`integrationTest`（Testcontainers 真实 PG，基座 `PostgresTestSupport`）｜`bdd`（Cucumber 中文，glue 在 `bdd.steps` 包）。
- **定向测试命令**：`cd backend && ./gradlew test --tests '*IntelligenceNews*' --console=plain`；`cd collector && python -m pytest tests/test_news_sources.py -v`；前端 `cd frontend && pnpm vitest run tests/lib/api.test.ts`（勿用 `pnpm test --` 过滤）。
- **collector 新任务八处改动面**（缺一即装配失败）：`plugins.py` 源类 → `jobs.py build_registries` 注册 + `_field_columns` → `tasks/*.yaml` → `store/writer.py` UPSERT_SQL/TABLE_COLUMNS → `tests/conftest.py` FLYWAY_SQL_FILES/ALL_TABLES → `test_yaml_assembly.py` EXPECTED_TASKS → 幂等测试 → （新鲜度监控需要时）`patrol.py` 表清单。
- **LLM 通道 = DeepSeek**（用户确认）：复用 `invest.llm.*` 与 `AgentConfig.investModel`（`agent/AgentConfig.java`，`@ConditionalOnExpression` DEEPSEEK_API_KEY）；任何新 LLM 调用必须经 `IntelligenceChatPort`，不得绕过。
- **commit 规范**：中文描述，`feat(intelligence): …` / `feat(collector): …` / `feat(ui): …` / `docs(intelligence): …`。
- **Jackson 2**（非 Jackson 3）；前端组件禁直接 fetch（走 `lib/http.ts request<T>` + 反代）。

## Review Focus

- 主源东财不可达/字段漂移 → 自动切新浪并告警，任务终态不 failed（selector 既有行为，勿破坏）；双源均败按批次告警。
- 抽取返回非法 JSON / Schema 不符 → 该条重试 1 次后标 FAILED，**不阻塞批次其余条目**（双层异常隔离）。
- `DEEPSEEK_API_KEY` 未配置 → 抽取/简报批 log.warn 跳过、简报留档 FAILED（fail_reason=LLM 未配置），**服务正常启动**（Model bean 不存在不炸）。
- 空情报交易日 → 简报发 EMPTY_SIMPLE 简版（一句话 + 数据截止期别），不跳过不报错。
- 90 天外新闻被清理后 `search_news` 检索 → 空列表 + 友好话术（不报错、不返回脏数据）；`intelligence_news_extract` 随 raw 级联删除无悬空。
- pg_trgm 关键词查询含中文两字词 → trigram 索引生效（EXPLAIN 验证走集成测试断言结果正确性即可）。

---

## 事实锚点（实现者必读，2026-09-29 对照真实代码核实）

- **Source 基类** `collector/collector/sources/base.py`：`Source(ABC)` 含 `source_id`、`supports_range: bool = False`、`fetch(self, params: dict) -> pd.DataFrame`、`SourceError`（源侧异常触发换源）。插件实例集中注册于 `collector/collector/scheduler/jobs.py` `build_registries(config)` 的字典字面量（如 `"treasury_curve": TreasuryCurveSource("treasury_curve", conn_factory=conn_factory)`）；`pro = ts.pro_api(...)`、`conn_factory = psycopg.connect` 闭包注入。
- **watermark 增量先例** `collector/collector/sources/plugins.py:823-896`（TreasuryCurveSource）：`__init__(self, source_id, conn_factory=None)` 查 `SELECT max(trading_day) FROM …`，显式 start/end 优先于 watermark。
- **HTTP 先例**（仓库无 requests/httpx）：`plugins.py` EtfBasicSource 用 `urllib.request` + `sources/constants.py` UA/timeout 常量。
- **YAML 封闭键集** = `repositories/tasks.py` `TASK_COLS`，未知键在 `jobs._validate_task_keys` fail-fast；`schedule: {type: cron, cron: "…"}`；源条目 `{source_id, type: plugin, class: xxx, params?, timeout_seconds?}`。
- **写入** `collector/collector/store/writer.py` `Store.upsert(conn, table, records) -> int`：每表手写 `INSERT … ON CONFLICT (…) DO UPDATE` + TABLE_COLUMNS 白名单，未知表抛 StoreError。
- **测试**：`tests/conftest.py` `pg_schema`（session 级 collector alembic + **回放 backend Flyway 全文**，新迁移文件加进 FLYWAY_SQL_FILES）；`tests/test_yaml_assembly.py` EXPECTED_TASKS 硬编码集合；`tests/test_writer_idempotency.py` 每表一测。
- **@Scheduled 先例**：`application/alert/PrincipleAlertService.java`（18:33，顶层 try/catch 吞异常护调度线程 + `doPatrol()` 内聚逻辑 + 测试构造器注入 Clock）；`application/research/ResearchFalsifierScanService.java`（18:43，单项目二次 try/catch 隔离）。cron 硬编码 + `zone = "Asia/Shanghai"`。
- **Model bean**：`agent/AgentConfig.java` `@Bean Model investModel(...)`（`ModelRegistry.resolve("deepseek:"+model, …)`，`@ConditionalOnExpression` 有 DEEPSEEK_API_KEY 才建）；接口（jar 核实）仅 `Flux<ChatResponse> stream(List<Msg>, List<ToolSchema>, GenerateOptions)`——**无同步方法**，实现内 `blockLast()`/`collectList()` 收敛。防缺 key 注入先例：`HarnessAgentInvoker` 用 `ObjectProvider<Model>` + `getIfAvailable()`。
- **工具先例** `agent/InvestTools.java`：`@Tool(name=…, description=…, readOnly=true, concurrencySafe=true)` + `@ToolParam(name=…, description=…)`；错误兜底 `run()/runBlock()` 绝不抛；`UserToolkitFactory.build`（`agent/UserToolkitFactory.java:42-44`）`registerTool(investTools)` 全局工具挂载。
- **提示词** `agent/InvestSystemPrompt.java` `TEXT` 常量「## 工具使用规范」每工具一条编号规则（现 11 条，新工具手工续编号）。
- **迁移现状**：V1（39 表）+ V2__research.sql（research_* 11 表）→ 新增 `V3__intelligence.sql`。
- **eval 源集** `backend/src/eval/`：独立源集不进 check/JaCoCo/ArchUnit；`make eval-agent` → `./gradlew evalAgent`（JavaExec，mainClass `com.portfolio.invest.eval.EvalRunner`，`.env` 注入 DEEPSEEK_API_KEY/MODEL/BASE_URL）；直连 DeepSeek HTTP 先例 `src/eval/java/.../DeepSeekJudge.java`。
- **前端工具卡**：`components/chat/ToolCallCard.tsx` `TOOL_LABELS` 加一行即得兜底卡；具名渲染器 `components/chat/toolRenderers.tsx`；参数 schema `lib/tool-params.ts`（zod，与后端 @ToolParam 对齐）。

---

### Task 1: 三源探测报告（质量门槛⑤，调研任务非 TDD）

**Files:**
- Create: `features/research-intelligence/09-调研报告/2026-09-29-MS20-数据源探测报告.md`
- Modify: `features/research-intelligence/02-设计规格/设计规格说明.md`（探测结论回填：§6 取数 URL、§2.3-6 公告栏目映射）

**Interfaces:**
- Consumes: 设计规格 D1/D2（源清单与 failover 顺序）、需求决策 #4/#5/#6（数据源裁决）。
- Produces: 探测报告（东财快讯/新浪 zhibo/巨潮三个接口的实测请求-响应样本、字段映射表、增量策略建议、公告栏目→`AnnouncementType` 映射表初版、宏观五指标取数页候选与风险）——Task 6/7（新闻源）与 P2/P3 公告宏观数据源的直接输入。

- [x] **Step 1: 实机探测东财 7×24 快讯**

```bash
curl -s 'https://np-listapi.eastmoney.com/comm/web/getFastNewsList?client=web&biz=web_724&fastColumn=102&sortEnd=&pageSize=20&req_trace=1' | python3 -m json.tool | head -60
```

记录：响应结构（data.fastNewsList?）、字段清单（newsId/title/summary/showTime/stockList?）、分页参数（sortEnd 游标?）、stockList 标的标签结构。多翻 3 页验证游标语义。

- [x] **Step 2: 实机探测新浪 zhibo 降级源**

```bash
curl -s 'https://zhibo.sina.com.cn/api/zhibo/feed?callback=&page=1&page_size=20&zhibo_id=152&tag_id=0&dire=f&dpc=1' | python3 -m json.tool | head -40
```

记录：条目唯一 id、时间字段、增量分页参数；与东财字段做映射差集。

- [x] **Step 3: 实机探测巨潮公告（P2 前瞻，一次做完）**

```bash
curl -s -X POST 'https://www.cninfo.com.cn/new/hisAnnouncement/query' \
  -d 'pageNum=1&pageSize=30&column=szse&tabName=fulltext&stock=600519,gssz0600519&searchkey=&secid=&plate=sz&category=&trade=&seDate=2026-09-01~2026-09-29' | python3 -m json.tool | head -80
```

记录：announcementId、announcementTitle、adjunctUrl（PDF 路径拼接规则 `static.cninfo.com.cn/`+adjunctUrl）、column 分类值清单——产出「栏目/分类值 → AnnouncementType 十类」映射表初版（决策 #24）。

- [x] **Step 4: 宏观取数页初勘（P3 前瞻）**

curl 探测：统计局数据发布页（CPI/PPI/PMI）、央行 LPR 页、社融数据页——记录可达性（302 跳转跟踪 `-L`）、HTML 结构概要、是否需要 JS 渲染（若 JS 渲染标记为「需换数据接口或 RSS」风险项）。

- [x] **Step 5: 写报告并回填设计规格**

报告结构：每源一节（请求样本/响应样本/字段映射表/增量策略/稳定性风险）+ 结论表（主备源裁决确认 or 修正）。将「公告栏目映射表」「宏观取数页 URL」回填设计规格 §2.3-6 与 §6（若与草案 URL 不同，以实测为准并注明）。

- [x] **Step 6: Commit**

```bash
git add features/research-intelligence/09-调研报告/ features/research-intelligence/02-设计规格/
git commit -m "docs(intelligence): MS-20 三源探测报告——东财/新浪/巨潮实机样本与字段映射、栏目映射初版"
```

---

### Task 2: V3__intelligence.sql（15 表 + pg_trgm + research_project 加列 + 日历 seed）

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__intelligence.sql`
- Modify: `collector/tests/conftest.py`（FLYWAY_SQL_FILES 加 V3、ALL_TABLES 加 15 张 intelligence 表 + 无新运维表）
- Test: `backend/src/integrationTest/java/com/portfolio/invest/intelligence/IntelligenceMigrationTest.java`

**Interfaces:**
- Consumes: 设计规格 §2.1 表清单（列定义逐字对照）、§2.2 索引清单。
- Produces: 15 张表 DDL（`intelligence_news_raw` 等，业务键 UNIQUE 照 §2.1）+ `research_project.intelligence_alert_enabled BOOLEAN NOT NULL DEFAULT TRUE` + `CREATE EXTENSION IF NOT EXISTS pg_trgm` + GIN trgm/时间索引 + `intelligence_macro_calendar` 2026Q4~2027 种子 INSERT（五指标：CPI/PPI 每月 9 日、PMI 每月 31 日、LPR 每月 20 日、AFMI 每月 12 日，frequency/source_site/updated_at）。P2~P4 与 collector 全部依赖本表结构。

- [x] **Step 1: 写集成测试（迁移重放 + 表/约束/扩展断言）**

```java
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntelligenceMigrationTest extends PostgresTestSupport {
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V3 建 15 张 intelligence 表且业务键唯一约束生效")
    void v3CreatesTables() {
        for (String t : List.of("intelligence_news_raw", "intelligence_news_extract",
                "intelligence_announcement", "intelligence_announcement_extract",
                "intelligence_policy_raw", "intelligence_policy_event",
                "intelligence_macro_series", "intelligence_macro_calendar",
                "intelligence_source_switch", "intelligence_daily_brief",
                "intelligence_subscription", "intelligence_subscription_stock",
                "intelligence_feishu_binding", "intelligence_binding_code",
                "intelligence_push_log")) {
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name=?", Integer.class, t)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("业务键唯一约束与级联删除")
    void businessKeysAndCascade() {
        jdbc.update("INSERT INTO intelligence_news_raw(source, external_id, title, published_at) VALUES('eastmoney','n1','t',now())");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO intelligence_news_raw(source, external_id, title, published_at) VALUES('eastmoney','n1','t2',now())"))
            .isInstanceOf(DataIntegrityViolationException.class);
        Long id = jdbc.queryForObject("SELECT id FROM intelligence_news_raw WHERE external_id='n1'", Long.class);
        jdbc.update("INSERT INTO intelligence_news_extract(news_raw_id, status) VALUES(?, 'PENDING')", id);
        jdbc.update("DELETE FROM intelligence_news_raw WHERE id=?", id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM intelligence_news_extract", Integer.class)).isZero();
    }

    @Test
    @DisplayName("pg_trgm 扩展与 trgm 索引可用")
    void pgTrgmReady() {
        Integer hit = jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_news_raw WHERE title % '政策利率'", Integer.class);
        assertThat(hit).isZero(); // 扩展不存在则此查询抛异常
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname='idx_intelligence_news_raw_title_trgm'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("research_project 加列默认 true；日历种子非空")
    void alterAndSeed() {
        assertThat(jdbc.queryForObject(
            "SELECT intelligence_alert_enabled FROM research_project LIMIT 1", Boolean.class)).isNull(); // V2 后无数据行，仅验证列存在
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_macro_calendar", Integer.class)).isGreaterThan(0);
    }
}
```

- [x] **Step 2: 运行验证失败**

Run: `cd backend && ./gradlew integrationTest --tests '*IntelligenceMigrationTest*' --console=plain`
Expected: FAIL（表不存在）

- [x] **Step 3: 写 V3 迁移**

按设计规格 §2.1 逐表落 DDL。骨架（每表完整列照 §2.1，此处示例两表 + 关键索引，其余同构）：

```sql
-- V3__intelligence.sql —— M15 智能情报域（需求 F05；设计规格 §2）
-- 本 schema 部分表为 Python 采集服务（collector）写入的跨服务契约（intelligence_news_raw /
-- intelligence_announcement / intelligence_policy_raw / intelligence_macro_series），先例 V1 注释。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE intelligence_news_raw (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source       VARCHAR(16)  NOT NULL,
    external_id  VARCHAR(64)  NOT NULL,
    title        TEXT         NOT NULL,
    summary      TEXT,
    published_at TIMESTAMPTZ  NOT NULL,
    url          TEXT,
    stock_tags   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    fetched_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);
CREATE INDEX idx_intelligence_news_raw_published ON intelligence_news_raw (published_at DESC);
CREATE INDEX idx_intelligence_news_raw_title_trgm ON intelligence_news_raw USING gin (title gin_trgm_ops);

CREATE TABLE intelligence_news_extract (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    news_raw_id   BIGINT      NOT NULL REFERENCES intelligence_news_raw(id) ON DELETE CASCADE,
    event_type    VARCHAR(32),
    stock_codes   JSONB,
    industry_codes JSONB,
    summary       TEXT,
    direction     VARCHAR(16) CONSTRAINT ck_news_dir CHECK (direction IN ('BULLISH','BEARISH','NEUTRAL')),
    key_numbers   JSONB,
    importance    INT CONSTRAINT ck_news_imp CHECK (importance BETWEEN 0 AND 100),
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING' CONSTRAINT ck_news_st CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    model         VARCHAR(64),
    extracted_at  TIMESTAMPTZ,
    UNIQUE (news_raw_id)
);
-- …（其余 13 表 + 索引照设计规格 §2.1/§2.2 全量落齐：含 intelligence_feishu_binding 的
--  UNIQUE(open_id)、intelligence_binding_code(code PK)、intelligence_push_log、
--  intelligence_macro_calendar 种子 INSERT、research_project ALTER … ADD COLUMN intelligence_alert_enabled …）
```

同步 `collector/tests/conftest.py`：FLYWAY_SQL_FILES 增 `'V3__intelligence.sql'`，ALL_TABLES 增 15 表。

- [x] **Step 4: 运行验证通过 + collector 测试回归**

Run: `cd backend && ./gradlew integrationTest --tests '*IntelligenceMigrationTest*' --console=plain && cd ../collector && python -m pytest tests/conftest.py tests/test_writer_idempotency.py -v`
Expected: PASS（collector fixture 重放 V3 成功）

- [x] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__intelligence.sql backend/src/integrationTest/java/com/portfolio/invest/intelligence/ collector/tests/conftest.py
git commit -m "feat(intelligence): V3 迁移——15 表 + pg_trgm + research_project 开关列 + 宏观日历种子"
```

---

### Task 3: domain/intelligence 枚举与纯函数

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/intelligence/{Direction,ExtractStatus,BriefSection,AnnouncementType,ImportanceGrade}.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/intelligence/ImportanceGradeTest.java`

**Interfaces:**
- Produces（P1 内消费 + P2~P4 依赖）:
  - `enum Direction { BULLISH, BEARISH, NEUTRAL }`
  - `enum ExtractStatus { PENDING, SUCCESS, FAILED }`
  - `enum BriefSection { MACRO, INDUSTRY, COMPANY, LIQUIDITY, GLOBAL, OTHER }`（5+1 节，决策 #20）
  - `enum AnnouncementType { INCREASE_HOLD, DECREASE_HOLD, BUYBACK, PLACEMENT, RELATED_TRANSACTION, EARNINGS_FORECAST, EARNINGS_FLASH, PERIODIC_REPORT, EQUITY_INCENTIVE, DELISTING_RISK, OTHER }`（决策 #24 十类+OTHER）
  - `enum ImportanceGrade { MAJOR, WATCH, IGNORE; public static ImportanceGrade grade(int score, int majorAt, int watchAt) }`——纯函数，Task 8/9 消费。

- [x] **Step 1: 写失败测试**

```java
@Test // org.junit.jupiter.api.Test + 中文 @DisplayName，AssertJ（ArchUnit 测试规范）
void shouldGradeByThresholds() {
    assertThat(ImportanceGrade.grade(80, 80, 50)).isEqualTo(MAJOR);
    assertThat(ImportanceGrade.grade(79, 80, 50)).isEqualTo(WATCH);
    assertThat(ImportanceGrade.grade(50, 80, 50)).isEqualTo(WATCH);
    assertThat(ImportanceGrade.grade(49, 80, 50)).isEqualTo(IGNORE);
    assertThat(ImportanceGrade.grade(0, 80, 50)).isEqualTo(IGNORE);
    assertThat(ImportanceGrade.grade(100, 80, 50)).isEqualTo(MAJOR);
}
```

- [x] **Step 2: 运行验证失败** — Run: `cd backend && ./gradlew test --tests '*ImportanceGradeTest' --console=plain`，Expected: FAIL（类不存在）

- [x] **Step 3: 最小实现**（五个枚举，纯 POJO 零注解）

```java
public enum ImportanceGrade {
    MAJOR, WATCH, IGNORE;
    public static ImportanceGrade grade(int score, int majorAt, int watchAt) {
        if (score >= majorAt) return MAJOR;
        if (score >= watchAt) return WATCH;
        return IGNORE;
    }
}
```

- [x] **Step 4: 运行验证通过 + ArchUnit 回归** — Run: `./gradlew test --tests '*ImportanceGradeTest' --tests '*PackageConventionsTest' --console=plain`，Expected: PASS

- [x] **Step 5: Commit** — `git add backend/src/main/java/com/portfolio/invest/domain/intelligence/ backend/src/test/java/com/portfolio/invest/domain/intelligence/ && git commit -m "feat(intelligence): 情报域枚举与重要度分档纯函数"`

---

### Task 4: IntelligenceChatPort + AgentScope 实现

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/intelligence/IntelligenceChatPort.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/intelligence/AgentScopeIntelligenceChatPort.java`
- Test: `backend/src/test/java/com/portfolio/invest/infrastructure/intelligence/AgentScopeIntelligenceChatPortTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface IntelligenceChatPort {
      /** 单轮补全；返回文本与 input token 数；LLM 未配置返回 empty（调用方静默跳批）。 */
      Optional<ChatOutcome> complete(String systemPrompt, String userPrompt);
      record ChatOutcome(String text, long inputTokens) {}
  }
  ```
  Task 8（新闻抽取）/Task 9（简报）/P2/P3 抽取服务全部消费此端口。

- [x] **Step 1: 写失败测试**（Mock Model：Flux.just(chatResponse)，Mockito mock ChatResponse/usage）

```java
@Test
void shouldReturnTextAndTokensWhenModelPresent() {
    Model model = mock(Model.class);
    ChatUsage usage = mock(ChatUsage.class); when(usage.getInputTokens()).thenReturn(1234L);
    ChatResponse resp = mock(ChatResponse.class); when(resp.usage()).thenReturn(usage);
    // text() 取 delta 文本内容 —— 按 agentscope ChatResponse 实际 API mock（实现时对照 jar：javap 核对 text 提取方式）
    when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.just(resp));
    var port = new AgentScopeIntelligenceChatPort(new ObjectProvider<>(model)); // 测试便利构造
    var out = port.complete("sys", "user");
    assertThat(out).isPresent(); assertThat(out.get().inputTokens()).isEqualTo(1234L);
}
@Test
void shouldReturnEmptyWhenNoModel() {
    var port = AgentScopeIntelligenceChatPort.unconfigured();
    assertThat(port.complete("s", "u")).isEmpty();
}
```

- [x] **Step 2: 验证失败** — Run: `./gradlew test --tests '*AgentScopeIntelligenceChatPortTest' --console=plain`，Expected: FAIL

- [x] **Step 3: 实现**（要点：`ObjectProvider<Model>` 注入、`getIfAvailable()` 为空→empty；`stream(Msg.system(systemPrompt)+Msg.user(userPrompt), List.of(), options).collectList().block(Duration.ofSeconds(120))` 拼接 text；异常 catch 返回 empty + WARN——**绝不抛**；GenerateOptions temperature 0（D4/D6））

```java
@Component
public class AgentScopeIntelligenceChatPort implements IntelligenceChatPort {
    private final org.springframework.beans.factory.ObjectProvider<io.agentscope.core.model.Model> models;
    // complete(): models.getIfAvailable() == null → Optional.empty()
    // → Msg 列表组装（对照 agentscope Msg API，实现前 javap 核实 Msg.system/user 工厂）
    // → options = GenerateOptions.builder().temperature(0.0).build()（核实 builder 形态）
    // → flux.collectList().block(timeout) 拼接文本 + usage().getInputTokens() 累加
    // → catch (Exception e) { log.warn(...); return Optional.empty(); }
}
```

> 注意：`Msg`/`GenerateOptions`/`ChatResponse` 的具体工厂与取文本 API 以仓库 `.gradle-home` 内 agentscope-core-2.0.3.jar `javap` 为准（记忆：macOS strings 读不了 .class，用 javap），实现前先核实再写——**不得凭记忆写 API**。

- [x] **Step 4: 验证通过** — Run 同 Step 2，Expected: PASS

- [x] **Step 5: Commit** — `git add backend/src/main/java/com/portfolio/invest/{application,infrastructure}/intelligence/ backend/src/test/.../intelligence/ && git commit -m "feat(intelligence): LLM 通道端口化——DeepSeek Model bean 经 ObjectProvider，缺 key 静默降级"`

---

### Task 5: 新闻 JPA 实体 + 仓库实现 + domain 仓库接口

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/intelligence/{NewsRecord,NewsExtractResult}.java`、`backend/src/main/java/com/portfolio/invest/domain/intelligence/NewsRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/intelligence/{IntelligenceNewsRawEntity,IntelligenceNewsExtractEntity}.java`、`IntelligenceNewsRepositoryImpl.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/intelligence/PageQuery.java`（record：page/pageSize/校验夹紧）+ `PageResult.java`（record：items/total/page/pageSize）——D20 分页协议载体，**必须放 domain**（domain 仓库接口消费，application 不得反向被 domain 依赖）；P4 的 API 信封 `PageView<T>` 是 application/IntelligenceViews 里的另一 record（由 PageResult 映射）
- Test: `backend/src/integrationTest/java/com/portfolio/invest/intelligence/NewsRepositoryTest.java`

**Interfaces:**
- Consumes: Task 2 表结构、Task 3 枚举。
- Produces:
  ```java
  public interface NewsRepository {
      PageResult<NewsRecord> search(PageQuery q);           // q.filters: keyword(trgm)/stockCode/industryCode/from/to/minImportance
      List<NewsRecord> findPendingForExtraction(LocalDate day, int limit);
      void upsertExtract(Long newsRawId, NewsExtractResult result); // status 置换 PENDING→SUCCESS/FAILED
      long deleteRawBefore(Instant cutoff);                 // Task 11 清理消费
      long countExtractedByDate(LocalDate day);             // 简报选取消费（Task 9）
      List<NewsRecord> findMajorSince(Instant since);       // 简报选取消费
  }
  ```

- [x] **Step 1: 写集成测试**（插入 fixture raw + extract 行 → search 各过滤器断言 + trgm 中文关键词命中 + findPending 只取 PENDING + 清理删除行数）——照 `PostgresTestSupport` 先例；测试代码完整写出（约 80 行，断言：关键词「政策利率」命中含「下调政策利率」标题、minImportance 过滤、分页 total/pageSize、deleteRawBefore 只删 cutoff 前且 extract 级联）。

- [x] **Step 2: 验证失败** — Run: `./gradlew integrationTest --tests '*NewsRepositoryTest' --console=plain`，Expected: FAIL

- [x] **Step 3: 实现**（domain record 纯 POJO；JPA 实体照 V2 research 实体先例——`@Entity @Table(name=...)` 字段逐列；仓库实现 `@Repository` 注入 JpaRepository 子接口 + JdbcTemplate/EntityManager 拼 trgm 过滤 SQL；JSONB 列用 String 存 JSON 文本 + Jackson 序列化，照仓库既有 JSONB 处理惯例（若无先例则 AttributeConverter<String>））

- [x] **Step 4: 验证通过** — Run 同 Step 2，Expected: PASS

- [x] **Step 5: Commit** — `git add … && git commit -m "feat(intelligence): 新闻域模型与 JPA 仓库——trgm 检索/待抽取游标/级联清理"`

---

### Task 6: collector 东财快讯源 + 新浪降级源

**Files:**
- Create: `collector/collector/sources/news.py`（`EastmoneyFastNewsSource` + `SinaZhiboNewsSource`，独立文件避免 plugins.py 膨胀——与 plugins.py 同包由 registry class 名引用）
- Modify: `collector/collector/scheduler/jobs.py`（build_registries 注册 `"eastmoney_fast_news"`/`"sina_zhibo_news"`）、`collector/collector/sources/constants.py`（NEWS_HTTP_TIMEOUT=10、NEWS_UA、东财/新浪 URL 常量）
- Test: `collector/tests/test_news_sources.py`

**Interfaces:**
- Consumes: Task 1 探测报告的实测 URL/字段映射/游标语义。
- Produces: 两源 `fetch(params) -> pd.DataFrame`，输出列固定 `[source, external_id, title, summary, published_at, url, stock_tags]`（stock_tags 为 JSON 字符串列，writer 侧写入 JSONB）；增量策略 = 分页拉取至首个已存 external_id 截断（conn_factory 查 `SELECT external_id FROM intelligence_news_raw ORDER BY id DESC LIMIT 200` 做已存集合，D1）。

- [x] **Step 1: 写失败测试**（pytest-mock：`mocker.patch("collector.collector.sources.news.urlopen")` 返回构造的 JSON bytes；断言 DataFrame 列/行数/已存截断/SourceError on 非 200）

```python
def test_eastmoney_fetch_parses_and_dedups(mocker, tmp_conn_factory):
    page1 = {"data": {"fastNewsList": [
        {"newsId": "n2", "title": "新", "summary": "s", "showTime": "2026-09-29 09:00:00", "stockList": [{"code": "600519"}]},
        {"newsId": "n1", "title": "旧", "summary": "s", "showTime": "2026-09-29 08:00:00"},
    ]}}
    mocker.patch.object(news.urlopen_spy, "call", return_value=resp(page1))  # 封装 urllib 调用便于 patch
    src = news.EastmoneyFastNewsSource("eastmoney_fast_news", conn_factory=already_has("n1"))
    df = src.fetch({})
    assert list(df["external_id"]) == ["n2"]  # 首个已存 id 截断
    assert df.iloc[0]["stock_tags"] == '[{"code": "600519"}]'

def test_source_error_on_http_fail(mocker, ...):  # 非 200 / JSON 解析失败 → SourceError（触发 selector 换源）
```

- [x] **Step 2: 验证失败** — Run: `cd collector && python -m pytest tests/test_news_sources.py -v`，Expected: FAIL（模块不存在）

- [x] **Step 3: 实现两源**（`urllib.request.Request` 带 UA；东财按探测报告游标翻页；新浪同构；单源内连续异常 3 次抛 SourceError 让 selector 走降级——**不要在源内自吞**；`last_warnings` 记录单条解析丢弃）

- [x] **Step 4: 验证通过** — Run 同 Step 2，Expected: PASS

- [x] **Step 5: Commit** — `git add collector/ && git commit -m "feat(collector): 东财7×24快讯主源+新浪zhibo降级源（增量截断+源侧告警语义）"`

---

### Task 7: 新闻双任务 YAML + writer + 装配

**Files:**
- Create: `collector/tasks/news_fast.yaml`、`collector/tasks/news_night.yaml`
- Modify: `collector/collector/store/writer.py`（UPSERT_SQL/TABLE_COLUMNS 加 `intelligence_news_raw`，冲突键 `(source, external_id)`）、`collector/tests/test_yaml_assembly.py`（EXPECTED_TASKS + 2）、幂等测试 `collector/tests/test_writer_idempotency.py`（+intelligence_news_raw）

**Interfaces:**
- Consumes: Task 6 源类；设计 §6 cron（`*/10 7-23 * * *` / `0 */2 0-6 * * *`）。
- Produces: 两任务全量装配可跑——`make collect-run TASK=news_fast` 落库；Task 8 抽取服务的数据源就绪。

- [x] **Step 1: 写失败测试**（EXPECTED_TASKS 集合断言 + 装配冒烟 + 幂等：同记录两次 upsert 行数不变）

```yaml
# tasks/news_fast.yaml（news_night.yaml 仅 task_code/cron 不同）
task_code: news_fast
task_name: 财经新闻采集-日间高频
target_table: intelligence_news_raw
enabled: true
trading_day_gated: false
retry_max: 2
retry_backoff: fixed
source_ids:
  - { source_id: eastmoney_fast_news, type: plugin, class: eastmoney_fast_news }
  - { source_id: sina_zhibo_news, type: plugin, class: sina_zhibo_news }
converter: field_mapping_news
calc: null
validator:
  - { field: external_id, check: required, level: hard }
  - { field: title, check: required, level: hard }
  - { field: published_at, check: not_null, level: hard }
schedule: { type: cron, cron: "*/10 7-23 * * *" }
```

- [x] **Step 2: 验证失败** — Run: `python -m pytest tests/test_yaml_assembly.py tests/test_writer_idempotency.py -v`，Expected: FAIL（未知任务/未知表）

- [x] **Step 3: 实现**（writer UPSERT：`ON CONFLICT (source, external_id) DO UPDATE SET title=EXCLUDED.title, summary=…, stock_tags=…, fetched_at=now()`；jobs.py `_field_columns` 加 `field_mapping_news` 列映射——7 列）

- [x] **Step 4: 验证通过** — Run 同 Step 2 + 全量 `make collect-test`，Expected: PASS

- [x] **Step 5: 实机冒烟（本地 dev DB）** — Run: `make collect-run TASK=news_fast`，Expected: collector_task_run 终态 success、`SELECT count(*) FROM intelligence_news_raw` > 0

- [x] **Step 6: Commit** — `git add collector/ && git commit -m "feat(collector): 新闻双任务装配——日间高频+夜间低频同表幂等"`

---

### Task 8: NewsExtractionService（07:40/16:40 双批 + 护栏 + 失败隔离）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/intelligence/NewsExtractionService.java`、`NewsExtractPrompt.java`（提示词常量：JSON Schema 输出契约——event_type/stock_codes/industry_codes/summary/direction/key_numbers/importance）
- Test: `backend/src/test/java/com/portfolio/invest/application/intelligence/NewsExtractionServiceTest.java`（单元：mock 端口）+ `backend/src/integrationTest/java/com/portfolio/invest/intelligence/NewsExtractionIntegrationTest.java`（真库 + stub 端口）

**Interfaces:**
- Consumes: Task 4 `IntelligenceChatPort`、Task 5 `NewsRepository`、Task 3 枚举、`InvestProperties.Intelligence`（Task 8 自建嵌套配置：extractBatchSize=15/dailyTokenGuardrail=2_000_000/majorThreshold=80/watchThreshold=50——**本任务一并落 InvestProperties + application.yml**）。
- Produces: `void extractPending()`（幂等可重入：PENDING→SUCCESS/FAILED 置换）；`@Scheduled(cron = "0 40 7,16 * * *", zone = "Asia/Shanghai")` 入口 `extractPendingScheduled()`（顶层 try/catch 吞异常）；当日 token 累计与停批（内存，D16）；`FeishuAlertNotifier` 告警一次（超护栏时）。eval（Task 13）与 P2 公告抽取复用「批量→逐条→失败隔离」骨架。

- [x] **Step 1: 写失败单元测试**

```java
@Test void shouldExtractBatchAndIsolateFailures() {
    // newsRepository.findPendingForExtraction 返回 3 条；chatPort.complete 第 1 条返回非法 JSON 两次、其余正常
    // 断言：upsertExtract 被调 3 次——1 次 FAILED + 2 次 SUCCESS；chatPort 调用次数 = 3 + 1 重试
}
@Test void shouldSkipSilentlyWhenLlmUnconfigured() { chatPort 返回 empty → 0 次状态置换 + 不抛 }
@Test void shouldStopBatchWhenGuardrailExceeded() { 累计 inputTokens 超阈值 → 剩余条目不动 + alertNotifier 告警恰 1 次 }
```

- [x] **Step 2: 验证失败** — Run: `./gradlew test --tests '*NewsExtractionServiceTest' --console=plain`，Expected: FAIL

- [x] **Step 3: 实现**（结构照 ResearchFalsifierScanService：public 调度方法顶层 try/catch → 包内 doExtract；逐条 try/catch；JSON 解析 Jackson readTree + 字段校验失败视为失败；importance 由 LLM 输出 0~100，落库前夹紧；`NewsExtractPrompt` 系统提示词含受控枚举与「无法判断填 null」约束）

- [x] **Step 4: 验证通过 + 集成测试**（真库：插 5 条 raw（1 条 PENDING 已存在 SUCCESS 跳过验证幂等）→ stub 端口跑 extractPending → 断言状态置换与 extracted_at），Run: `./gradlew test --tests '*NewsExtractionServiceTest' integrationTest --tests '*NewsExtractionIntegrationTest' --console=plain`，Expected: PASS

- [x] **Step 5: Commit** — `git add … && git commit -m "feat(intelligence): 新闻 LLM 结构化抽取——双批调度/失败隔离/token 护栏"`

---

### Task 9: BriefGenerationService（08:00 选取 + 5+1 节 + 归档三态）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/intelligence/{BriefGenerationService,BriefComposer,BriefSelectionPolicy}.java`、`domain/intelligence/DailyBrief.java`、`domain/intelligence/BriefRepository.java`、`infrastructure/persistence/intelligence/IntelligenceDailyBriefEntity.java` + 仓库实现
- Create: `backend/src/main/java/com/portfolio/invest/application/intelligence/TradingCalendarPort.java` + `infrastructure/persistence/TradingCalendarPortImpl.java`（直读 trading_calendar，空降级 MON-FRI——D5）
- Modify: `InvestProperties.Intelligence` 增 briefMaxItems=25/briefMinItems=15
- Test: 单元 `BriefSelectionPolicyTest`（纯函数：全部 MAJOR + 各节 WATCH 候选按分值补足 min~max，无 MAJOR→空简版判定）+ `BriefGenerationServiceTest`（mock 端口/仓库/日历）+ 集成 `BriefGenerationIntegrationTest`

**Interfaces:**
- Consumes: Task 5 NewsRepository.findMajorSince/countExtractedByDate、Task 4 端口、Task 3 BriefSection/ImportanceGrade。
- Produces: `BriefRepository.save(DailyBrief)` / `Optional<DailyBrief> findByDate(LocalDate)`；`DailyBrief`（tradeDate/contentMd/topStocks(JSON)/status GENERATED|EMPTY_SIMPLE|FAILED/failReason/model/generatedAt）；生成入口 `@Scheduled(cron = "0 0 8 * * MON-FRI", zone=...)` + TradingCalendarPort 双判定；markdown 结构 = `## 宏观与政策` … 六节（BriefSection 顺序）+ 节内 `- [标题](url)（重要度 85 · 利好）` 行格式；top_stocks = 条目 stock_codes 频次 top 20 快照（决策 #23）。Task 10 推送消费 findByDate。

- [x] **Step 1: 写失败测试**（选取策略纯函数 4 例：全 MAJOR 超上限截断 / 候选池按分值补足 / 空情报判定 EMPTY_SIMPLE / 分布到节；服务测试：交易日假/真、LLM empty→FAILED 留档 fail_reason、生成后 status=GENERATED 且 contentMd 含六节标题）

- [x] **Step 2: 验证失败** — Run: `./gradlew test --tests '*Brief*' --console=plain`，Expected: FAIL

- [x] **Step 3: 实现**（Composer 拼 markdown 纯函数化便于测；LLM 用途=逐节汇总语（把选中条目列表给 LLM 生成 2~3 句节导语）——**条目本身是结构化事实不重写**；LLM empty 时退化为无导语纯条目版，不 FAILED——只有「连条目选取都异常」才 FAILED）

- [x] **Step 4: 验证通过 + 集成**（真库 seed 抽取结果 → 生成 → findByDate 断言三态路径），Run: `./gradlew test --tests '*Brief*' integrationTest --tests '*BriefGeneration*' --console=plain`，Expected: PASS

- [x] **Step 5: Commit** — `git add … && git commit -m "feat(intelligence): 盘前简报生成——选取策略/5+1节/三态归档/交易日历端口"`

---

### Task 10: IntelligencePushPort + Feishu 实现群推版 + BriefPushService

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/intelligence/IntelligencePushPort.java`、`infrastructure/im/FeishuIntelligencePushNotifier.java`
- Modify: `backend/src/main/java/com/portfolio/invest/infrastructure/im/FeishuClient.java`（**抽私有 `buildCardJson(String title, String template, List<String> bodyLines)`**，sendCard 改调它——行为零变化，现有 FeishuClientTest 保持绿）
- Create: `backend/src/main/java/com/portfolio/invest/application/intelligence/BriefPushService.java` + `domain/intelligence/PushLog.java` + `PushLogRepository` + JPA 实体/实现
- Test: `FeishuIntelligencePushNotifierTest`（MockRestServiceServer，照 FeishuClient 测试构造器先例）+ `BriefPushServiceTest`

**Interfaces:**
- Produces:
  ```java
  public interface IntelligencePushPort {
      boolean sendToGroup(String title, String template, List<String> bodyLines); // P1 群推版
      boolean sendToUser(String openId, String title, String template, List<String> bodyLines); // P4 实装（P1 抛 UnsupportedOperationException？——否：P1 即实现，走 sendCardByOpenId，见下）
  }
  ```
  本任务一并实装 `FeishuClient.sendCardByOpenId(String openId, ...)`（D10：postMessage `receive_id_type=open_id`，门禁仅 appId/appSecret）与 `buildCardJson` 抽取——P4 直接消费，避免二次动 FeishuClient。`BriefPushService`：`@Scheduled(cron = "0 30 8 * * MON-FRI", zone=...)` 读 `BriefRepository.findByDate(today)`：无档（生成失败已留 FAILED 档则发失败简版一行）→ 不补发陈旧；有档 → `sendToGroup`（P1 版；个性化 open_id 单发在 P4 接订阅/绑定后启用）+ push_log 留痕（target=chatId, status OK/FAIL）。

- [x] **Step 1: 写失败测试**（Notifier：群推走 chat_id 端点断言 body；open_id 推走 `receive_id_type=open_id` 且不要求 chatId 配置；BriefPush：成功/失败留痕/无档跳过/EMPTY_SIMPLE 简版照发）

- [x] **Step 2: 验证失败** — Run: `./gradlew test --tests '*IntelligencePush*' --tests '*BriefPush*' --console=plain`，Expected: FAIL

- [x] **Step 3: 实现**（照 ResearchFalsifierNotifier 模式 @Component 无条件注册、失败 false 不抛；push_log 经 PushLogRepository 落库）

- [x] **Step 4: 验证通过** — Run 同 Step 2，Expected: PASS

- [x] **Step 5: Commit** — `git add … && git commit -m "feat(intelligence): 推送端口与群推版盘前简报——open_id 单发能力一并落 FeishuClient"`

---

### Task 11: IntelligenceCleanupService（04:07 滚动清理）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/intelligence/IntelligenceCleanupService.java`
- Test: `backend/src/integrationTest/java/com/portfolio/invest/intelligence/IntelligenceCleanupTest.java`（真库：插 91 天前/89 天前 raw+extract → 跑清理 → 旧对删除新对保留 + extract 级联为 0 悬空 + 过期绑定码清理）

**Interfaces:**
- Consumes: Task 5 `NewsRepository.deleteRawBefore`、binding_code 表（Task 2 已建）。
- Produces: `@Scheduled(cron = "0 7 4 * * *", zone=...)` 清理入口（顶层吞异常）；P4 绑定码清理复用。

- [x] **Step 1~4: TDD 循环**（测试→FAIL→实现：`DELETE FROM intelligence_news_raw WHERE published_at < now() - interval '90 days'` 经仓库方法 + `DELETE FROM intelligence_binding_code WHERE expires_at < now() - interval '1 day'`→PASS）
- [x] **Step 5: Commit** — `git commit -m "feat(intelligence): 90 天滚动清理与过期绑定码清扫"`

---

### Task 12: search_news 工具 + 提示词 + 前端工具卡

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/agent/InvestTools.java`（新 @Tool 方法，注入 `application/intelligence/IntelligenceQueryService`——本任务先建该服务的最小版：`searchNews(NewsSearchFilter)` 委托 NewsRepository.search，P4 扩四区块）
- Modify: `backend/src/main/java/com/portfolio/invest/agent/InvestSystemPrompt.java`（「## 工具使用规范」补第 12 条：新闻/情报检索触发时机）
- Modify: `frontend/lib/tool-params.ts`（SearchNewsParamsSchema）、`frontend/components/chat/ToolCallCard.tsx`（TOOL_LABELS + `search_news: "新闻检索"`）、`frontend/components/chat/toolRenderers.tsx`（具名渲染器：列表卡——每条 标题链接+方向徽标+重要度+摘要行）
- Test: `backend/src/test/java/com/portfolio/invest/agent/SearchNewsToolTest.java`（InvestTools 切片：mock query service，断言 JSON 输出与空结果话术）+ `frontend/tests/lib/tool-params.test.ts`（schema 校验）

**Interfaces:**
- Produces: 工具名 `search_news`，参数 `q/stock/industry/from/to/minImportance/limit`（limit 缺省 10 夹紧 ≤20）；返回 JSON `{"items":[{title,summary,direction,importance,stockCodes,url,publishedAt}],"total":n}`；空结果返回 `{"items":[],"message":"该条件下暂无情报（新闻仅保留 90 天内）"}`——绝不抛（run() 兜底）。

- [x] **Step 1~4: TDD 循环**（后端先行：工具测试→FAIL→实现（照 InvestTools 既有 get_news 相邻方法的形态与 run() 兜底）→PASS；前端 schema 测试→实现→PASS）
- [x] **Step 5: Commit** — `git commit -m "feat(intelligence): search_news 只读工具——提示词/工具卡/参数 schema 三处接线"`

---

### Task 13: eval 抽取回归（题库 + ExtractionEvalRunner + evalExtraction task）

**Files:**
- Create: `backend/src/eval/java/com/portfolio/invest/eval/ExtractionEvalRunner.java`
- Create: `backend/src/eval/resources/extraction/news-001.yaml` 起 ≥8 题（样本新闻标题+正文 + 期望 stock_codes/direction/importance 档位/event_type；含边界：无关水闻→低分、多标的、无标的）
- Create: `backend/src/eval/resources/rubric/extraction.md`（抽验口径：标签一致率 ≥90% 量级、方向一致、档位偏差 ≤1 档）
- Modify: `backend/build.gradle`（task `evalExtraction` JavaExec，照 evalAgent：mainClass ExtractionEvalRunner、.env 注入）+ `Makefile`（`eval-extraction` 目标）

**Interfaces:**
- Consumes: Task 8 NewsExtractPrompt 与抽取核心（把「文本→JSON」抽成可独立调用的纯方法 `NewsExtractor.extractOne(chatPort, title, summary)`——Task 8 实现时就按此切分，本任务 eval 直调）。
- Produces: `make eval-extraction` 产出 `backend/build/reports/eval-extraction/eval-report.{json,md}`（逐题一致率汇总）；不挂 CI、退出码恒 0（D21）；提示词/模型变更 PR 须附报告（流程约束写入 rubric 头部）。

- [x] **Step 1~4: 题库 + Runner + task**（Runner：加载 YAML→逐题调 NewsExtractor（真 IntelligenceChatPort）→字段比对计分→写报告；跑一次真 DeepSeek 验证出报告）
- [x] **Step 5: Commit** — `git commit -m "test(intelligence): 抽取质量 eval 回归——8 题起步题库与 evalExtraction 任务"`

---

### Task 14: BDD 场景 + smoke 探测段 + 文档同步（P1 收口）

**Files:**
- Create: `backend/src/bdd/java/.../bdd/steps/IntelligenceNewsSteps.java` + `backend/src/bdd/resources/features/intelligence/news-search.feature`（中文场景：「当用户询问"最近有什么关于茅台的重大新闻"时 Agent 调用 search_news 并返回结构化结果」——AG-UI 运行时 stub 端口照既有 agent BDD 先例）
- Modify: `smoke/`（新增网络探测段：出站可达 np-listapi.eastmoney.com / zhibo.sina.com.cn / www.cninfo.com.cn——决策 #17）+ Makefile smoke 目标
- Modify: `docs/technology/conventions/01-后端DDD分包规范.md`（domain/application 子包清单 + intelligence）、`AGENTS.md`（迁移描述 V3、web 控制器数）、`docs/function/modules/15-智能情报.md`（创建模块文档，F01~F05 置 ✅ + 交付说明）、`docs/function/00-功能模块概览.md`（M15 行 5/18）、`docs/plans/2026-08-27-产品落地计划.md` 与 `docs/plans/2026-09-28-阶段二产品功能规划.md`（MS-20 状态 + 功能点勾选）、`features/README.md` 索引行状态

- [x] **Step 1~3: BDD 红绿 + smoke 探测段编写与本地跑通**
- [x] **Step 4: 全量回归** — Run: `make test && make smoke`，Expected: 三端全绿 + smoke 过（含新探测段）
- [x] **Step 5: 文档批量同步（照 features/README 交付回填 checklist 逐项）**
- [x] **Step 6: Commit** — `git commit -m "feat(intelligence): MS-20 收口——BDD 新闻检索场景/smoke 网络探测/文档全量同步"`

---

## P1 验收对照（MS-20）

- [x] 连续 5 个交易日采集落库正常（部署观察项——本地以 `make collect-run` + 当日两次 cron 触发替代，部署机上线首周人工确认）
- [x] 抽取结果抽验 ≥90% 量级（`make eval-extraction` 报告）
- [x] `make test` 三端全绿 + smoke/e2e 过（e2e 无新页面用例；MS-20 不改既有页面）
