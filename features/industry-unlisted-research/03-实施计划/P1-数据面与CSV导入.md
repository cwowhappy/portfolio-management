# P1 · 数据面与 CSV 导入（MS-10）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 未上市策展企业与融资事件两张表（V19+种子）+ 领域/仓储/策展写服务 + 两类 CSV 导入（upsert 幂等、行号+原因清单）+ `/api/industry-curation/**` 登录态端点 + Next 代理 multipart 路由——P2 前端消费的数据面全部就绪。

**Architecture:** `domain/industry` 新增枚举/record/端口（照 IndustryWatchItem 先例）+ `infrastructure/persistence` JPA 三件套 + `application/industry` 两解析器（无注解类）+ 策展服务与导入服务 + `web/IndustryCurationController`；复用 MS-14 导入双层错误语义（文件级 400 / 行级 200）。

**Tech Stack:** Spring Boot 4（Java 21）/ JPA + Flyway / Apache Commons CSV 1.11.0（已在 `backend/build.gradle:88`）/ Next.js 15 route handler。

**Spec:** [01-需求规格](../01-需求规格/需求规格说明.md)（决策 #1/#3/#5b/#7）+ [02-设计规格](../02-设计规格/设计规格说明.md) §二/§三/§四/§五（DDL/端口/契约/校验层以此为准）。

## Global Constraints

- 测试规约：方法名 `given|when|then` 前缀 + 中文 `@DisplayName`（`TestSourceSetConventionsTest.java:75-79`）；test 源集禁 Testcontainers；integrationTest 对真实 PG。
- CSV 口径：RFC 4180、UTF-8（读侧容忍 BOM，写模板带 BOM）；上限 2000 数据行、1MB 文件。
- **upsert 幂等**（设计规格 §九#2，与 MS-14 纯插入语义的关键差异）：命中幂等键更新非键字段，`CurationImportResult(insertedCount, updatedCount, rowErrors)` 200 返回；文件级错误（空/超限/表头不匹配）→ 400 `ApiError`。
- 全局数据无 user 归属（设计规格 §九#1）：写侧仅校验登录（`Authentication` 参数即达成的路由级鉴权），不按人过滤。
- 「今日」= 服务器默认时区 `LocalDate.now()`。
- 覆盖率：后端 JaCoCo ≥0.80；`cd backend && ./gradlew check` 通过。
- 提交信息末尾带 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

---

### Task 1: V19 迁移 + 表契约（含种子）

**Files:**
- Create: `backend/src/main/resources/db/migration/V19__industry_unlisted.sql`
- Modify: `backend/src/integrationTest/java/com/portfolio/invest/migration/FlywayMigrationIntegrationTest.java`

**Interfaces:**
- Produces（后续所有任务的表契约）：`industry_unlisted_company`、`industry_funding_event`，DDL 逐列照设计规格 §二 V19（幂等键 UNIQUE(industry_code, company_name) / UNIQUE(event_date, company_name, round)）。

- [ ] **Step 1: 写失败测试（版本清单 + 两表契约断言）**

```java
// whenFlywayMigrates_thenAllAppliedWithNoFailures 的 containsExactly 追加 "19"
assertThat(versions).containsExactly("1", ..., "18", "19");

@Test
@DisplayName("未上市策展与融资事件表契约（V19）")
void whenSchemaMigrated_thenIndustryUnlistedTablesMatchContract() {
    // 照 V18 断言模板（FlywayMigrationIntegrationTest.java:133-148）
    assertPrimaryKey("industry_unlisted_company", "id");
    assertColumn("industry_unlisted_company", "industry_code", "character varying", false);
    assertColumn("industry_unlisted_company", "company_name", "character varying", false);
    assertColumn("industry_unlisted_company", "latest_round", "character varying", false);
    assertColumn("industry_unlisted_company", "total_funding_yi", "numeric", true, 14, 2);
    assertColumn("industry_unlisted_company", "last_funding_date", "date", true);
    assertColumn("industry_unlisted_company", "updated_at", "timestamp with time zone", false);
    assertUniqueColumns("industry_unlisted_company", "industry_code,company_name");
    assertPrimaryKey("industry_funding_event", "id");
    assertColumn("industry_funding_event", "event_date", "date", false);
    assertColumn("industry_funding_event", "round", "character varying", false);
    assertColumn("industry_funding_event", "amount_yi", "numeric", true, 14, 2);
    assertColumn("industry_funding_event", "industry_code", "character varying", false);
    assertColumn("industry_funding_event", "source_title", "character varying", false);
    assertUniqueColumns("industry_funding_event", "event_date,company_name,round");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./gradlew integrationTest --tests '*FlywayMigrationIntegrationTest*'`
Expected: FAIL（V19 不存在，版本清单少 "19"）。

- [ ] **Step 3: 写 V19（DDL 照设计规格 §二 + 头注释说明用途与决策出处 + 种子 INSERT）**

种子：10 家示例策展企业（电子 801080 ×5、医药生物 801150 ×5，轮次覆盖 B/C+/D/战略投资/未知，1 家 last_funding_date 为 NULL）+ 20 条融资事件（两行业近 24 月分布，含 amount/investors/source_url 为 NULL 的行；幂等键不得重复）。种子企业名用「示例××科技」前缀防与真实数据混淆。

- [ ] **Step 4: 迁移测试通过**

Run: `cd backend && ./gradlew integrationTest --tests '*FlywayMigrationIntegrationTest*'` → PASS。

- [ ] **Step 5: Commit** `feat(industry): V19 未上市策展与融资事件表+种子（MS-10 P1）`

---

### Task 2: domain 枚举与聚合 + 端口

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/industry/FundingRound.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/industry/UnlistedCompany.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/industry/FundingEvent.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/industry/UnlistedCompanyRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/industry/FundingEventRepository.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/industry/FundingRoundTest.java`

**Interfaces:**
- Produces（Task 3/4/5/6/7 依赖，签名精确）:
  - `enum FundingRound { SEED, ANGEL, PRE_A, A, A_PLUS, B, B_PLUS, C, C_PLUS, D, STRATEGIC, PRE_IPO, IPO, ACQUIRED, UNKNOWN }`——`String label()`（种子轮/天使轮/Pre-A轮/…/未知）、`static FundingRound parse(String s)`（非法抛 `IllegalArgumentException`，供解析器 L2/L3 捕获转行错误）、`int order()`（声明序即轮次序，overview 分布排序用）。CSV 输入格式：`PRE_A`/`A_PLUS`/`STRATEGIC`（下划线大写）。
  - `record UnlistedCompany(Long id, String industryCode, String companyName, String segment, FundingRound latestRound, LocalDate lastFundingDate, BigDecimal totalFundingYi, String summary, String sourceNote, Instant updatedAt)`
  - `record FundingEvent(Long id, LocalDate eventDate, String companyName, FundingRound round, BigDecimal amountYi, String investors, String industryCode, String segment, String sourceTitle, String sourceUrl, Instant createdAt)`
  - `interface UnlistedCompanyRepository`：`List<UnlistedCompany> findByIndustry(String industryCode)`（lastFundingDate DESC NULLS LAST，同日按轮次序倒序）/ `long countByIndustry(String)` / `UpsertOutcome upsert(UnlistedCompany c)`（`record UpsertOutcome(boolean inserted)`）/ `Optional<UnlistedCompany> findById(Long)` / `void deleteById(Long)`
  - `interface FundingEventRepository`：`List<FundingEvent> findByIndustrySince(String industryCode, LocalDate since)` / `UpsertOutcome upsert(FundingEvent e)` / `void deleteById(Long)`

- [ ] **Step 1: 失败测试**——FundingRoundTest：parse 合法 15 值、parse 非法抛 IAE、label 中文对齐、order 声明序（`assertThat(FundingRound.SEED.order()).isLessThan(FundingRound.A.order())` 等）。
- [ ] **Step 2:** `./gradlew test --tests '*FundingRoundTest*'` FAIL → **Step 3:** 实现枚举（纯 JUnit 零 Spring）→ **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat(industry): FundingRound 枚举与未上市聚合/端口（MS-10 P1）`

---

### Task 3: persistence JPA 三件套 ×2

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/IndustryUnlistedCompanyJpaEntity.java` + `IndustryUnlistedCompanyJpaRepository.java` + `UnlistedCompanyRepositoryImpl.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/IndustryFundingEventJpaEntity.java` + `IndustryFundingEventJpaRepository.java` + `FundingEventRepositoryImpl.java`
- Test: `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/UnlistedCompanyRepositoryImplTest.java`、`FundingEventRepositoryImplTest.java`

**Interfaces:**
- Consumes: Task 2 端口；V19 表。
- 照抄 `IndustryWatchRepositoryImpl.java:8-40` 模式：`@Repository` 不挂 @Transactional、构造注入 JpaRepository、`toDomain()/fromDomain()` 静态映射；upsert = 先按幂等键 `findBy...` 存在则更新非键字段并 save、否则插入（照 :29-34 先查后插注释风格）。
- JpaEntity 映射注意：`FundingRound` ↔ VARCHAR 存**枚举名**（`@Enumerated(EnumType.STRING)` 不用——record 域与实体分离，fromDomain/toDomain 手写 `FundingRound.valueOf(...)`）。

- [ ] **Step 1: 失败测试**（`@DataJpaTest @AutoConfigureTestDatabase(replace=NONE) @ImportAutoConfiguration(FlywayAutoConfiguration.class) @Import(UnlistedCompanyRepositoryImpl.class)` + `@ServiceConnection static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();`，照 `IndustryWatchRepositoryImplTest.java:24-31`）：
  - findByIndustry 排序（构造 3 家：日期不同/同日不同轮次/日期 NULL）；
  - upsert 首插 inserted=true、同键重导更新非键字段 inserted=false 且字段变化生效；
  - deleteById 幂等（删不存在不抛）。
  - FundingEvent 仓储同构（Since 窗口过滤 + upsert）。
- [ ] **Step 2:** integrationTest FAIL → **Step 3:** 实现 → **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat(industry): 未上市/融资事件 JPA 仓储与 upsert（MS-10 P1）`

---

### Task 4: 策展企业 CSV 解析器（L1/L2）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/industry/UnlistedCompanyCsvParser.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/industry/UnlistedCompanyCsvParserTest.java`

**Interfaces:**
- Produces: `ParseOutcome parse(String content, LocalDate today)`——照 `CsvImportParser.ParseOutcome` 形状（rows/errors 互斥、全量聚齐、行号=CSV 记录序号表头为 1）；`record ParsedCompany(int rowNumber, String industryCode, String companyName, String segment, FundingRound latestRound, LocalDate lastFundingDate, BigDecimal totalFundingYi, String summary, String sourceNote)`。
- 表头八列（设计规格 §五模板即权威）：`industry_code,company_name,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note`；必填 industry_code/company_name/latest_round；`latest_round` L2 层 `FundingRound.parse` 失败转行错误；`last_funding_date ≤ today`；金额 NUMERIC。
- 类头/常量/BOM 剥离/Commons CSV format 全照 `CsvImportParser.java:20-66` 注释与结构（MAX_DATA_ROWS=2000、FILE_LEVEL_ROW=0）。

- [ ] **Step 1: 失败测试**——已知答案样例 `EIGHT_COLUMN_CSV` 内嵌文本块（全 8 列 happy path + 各错误行：表头列序错（文件级）、轮次非法、日期晚于今日、必填空、行数超限、BOM 头剥离后正常）。
- [ ] **Step 2/3/4:** FAIL → 实现 → PASS（`./gradlew test --tests '*UnlistedCompanyCsvParserTest*'`）。
- [ ] **Step 5:** Commit `feat(industry): 策展企业 CSV 解析器 L1/L2（MS-10 P1）`

> 偏差注记（2026-09-26）：`CurationImportResult.java`（原列于 Task 6 Files）提前到 Task 4 创建——两解析器 ParseOutcome 的错误类型即 `CurationImportResult.RowError`（Task 6 要求 parser 错误「直传」结果，须同型），避免另建第三类型再映射。

---

### Task 5: 融资事件 CSV 解析器（L1/L2）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/industry/FundingEventCsvParser.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/industry/FundingEventCsvParserTest.java`

**Interfaces:**
- Produces: 同 Task 4 形状；`record ParsedFundingEvent(int rowNumber, LocalDate eventDate, String companyName, FundingRound round, BigDecimal amountYi, String investors, String industryCode, String segment, String sourceTitle, String sourceUrl)`。
- 表头九列：`event_date,company_name,round,amount_yi,investors,industry_code,segment,source_title,source_url`；必填 event_date/company_name/round/industry_code/source_title；`event_date ≤ today`。

- [ ] **Step 1~5:** 同 Task 4 模式（样例 `NINE_COLUMN_CSV` + 错误行全覆盖）。Commit `feat(industry): 融资事件 CSV 解析器 L1/L2（MS-10 P1）`

> 偏差注记（2026-09-26）：设计规格 §五 事件模板示例行轮次原文「B+」与 FundingRound 枚举 CSV 输入格式（下划线大写 `B_PLUS`，Task 2 契约）矛盾——以枚举格式为准，模板/解析器测试/集成测试三处样例统一写 `B_PLUS`（CsvImportParserTest 矩阵勘误同类先例），设计规格 §五 示例已同步回写。

---

### Task 6: 导入编排服务（L3 引用 + L5 键内重复 + upsert 执行）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/industry/IndustryCurationImportService.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/industry/CurationImportResult.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/industry/IndustryCurationImportServiceTest.java`

**Interfaces:**
- Consumes: Task 3/4/5 产物 + `IndustryRepository.existsIndustry(String)`（既有，`IndustryRepository.java:6-12`）。
- Produces:
  - `record CurationImportResult(int insertedCount, int updatedCount, List<RowError> rowErrors)`（RowError 复用 `ImportSimulator.RowError`? **否**——跨域不引 portfolio 类；在本域新建 `record RowError(int row, String reason)` 于 CurationImportResult 内嵌）
  - `@Transactional <T> CurationImportResult` 双入口：`importCompanies(String csvContent)` / `importFundingEvents(String csvContent)`（today=LocalDate.now()）
- 编排（照 `PortfolioImportService.java:58-108` 五层管线注释风格，**无模拟器**——无跨行资金语义）：
  1. parser.parse → 有错误直传（importedCount=0, updatedCount=0）；
  2. L3：`industryCode` 非白名单（`!industryRepository.existsIndustry(code)`）→ 行错误「行业代码不存在: 801xxx」（一次查全文件去重集合）；
  3. L5：文件内幂等键重复（第二行起）→ 行错误；
  4. 执行：逐条 `repository.upsert`，累计 inserted/updated；**全量预检通过才执行**（all-or-nothing）。

- [ ] **Step 1: 失败测试**（纯 JUnit + Mockito 构造注入，照 `IndustryWatchApplicationServiceTest.java:22-50`）：六用例——全成功插入、重导同文件全 updated、L3 行错误零落库（verify upsert never）、L5 键重复、parser 错误直传、混合 inserted+updated 计数。
- [ ] **Step 2~4:** FAIL → 实现 → PASS。**Step 5:** Commit `feat(industry): 策展/融资 CSV 导入编排 upsert（MS-10 P1）`

---

### Task 7: 策展单条 CRUD 服务

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/industry/IndustryCurationApplicationService.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/industry/SaveUnlistedCompanyCommand.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/industry/IndustryCurationApplicationServiceTest.java`

**Interfaces:**
- Produces:
  - `record SaveUnlistedCompanyCommand(@NotBlank String industryCode, @NotBlank String companyName, String segment, @NotBlank String latestRound, LocalDate lastFundingDate, BigDecimal totalFundingYi, String summary, String sourceNote)`
  - 服务方法：`UnlistedCompany save(SaveUnlistedCompanyCommand cmd)`（id null 插入否则更新，更新前 findById 不存在抛 `IndustryException(IndustryErrorCode.INDUSTRY_NOT_FOUND...)`? **新增错误码** `UNLISTED_NOT_FOUND`，见 Task 8）/ `void deleteCompany(Long id)`（幂等）/ `void deleteFundingEvent(Long id)`（幂等）
  - 校验：existsIndustry（行业不存在 → `IndustryException(INDUSTRY_NOT_FOUND)`，照 `IndustryApplicationService.java:94-96` 文案口径）；latestRound `FundingRound.parse`（非法 → `IndustryException(INVALID_ROUND)`）。
- Consumes: Task 3 仓储、IndustryErrorCode（既有 `IndustryErrorCode.java:6-8` 三常量）。

- [ ] **Step 1~5:** 失败测试（Mockito，含错误码断言 `assertThatThrownBy`）→ 实现 → PASS。Commit `feat(industry): 策展单条 CRUD 服务与错误码（MS-10 P1）`

> 偏差注记（2026-09-26）：① service 签名定为 `save(Long id, SaveUnlistedCompanyCommand cmd)`——计划原文「save(cmd)（id null 插入否则更新）」自相矛盾（命令内无 id 字段无从判插入/更新），id 由控制器路径参数传入；② 端口 `UnlistedCompanyRepository` 增补 `UnlistedCompany save(UnlistedCompany)`（按 id merge、回带库生成 id——POST 需返回落库实体，upsert 只回 UpsertOutcome 不够），仓储集成测试同步补两用例。

---

### Task 8: 写侧 Controller + 模板 + 异常分支 + WebMvc 切片

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/web/IndustryCurationController.java`
- Create: `backend/src/main/java/com/portfolio/invest/web/IndustryCurationTemplates.java`（两模板常量，正文与两 Parser 测试样例逐行同源——照 `PortfolioImportTemplate.java:5-7` javadoc 约定）
- Modify: `backend/src/main/java/com/portfolio/invest/domain/industry/IndustryErrorCode.java`（+`INVALID_ROUND`/`UNLISTED_NOT_FOUND`/`INVALID_CSV`）
- Modify: `backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java`（industry 分支 switch 加三 case，照 `GlobalExceptionHandler.java:162-174` 既有分支）
- Test: `backend/src/test/java/com/portfolio/invest/web/IndustryCurationControllerTest.java`

**Interfaces:**
- 路由（照 `IndustryWatchController.java:19-54` 鉴权/构造/currentUserId 模式；`@RequestMapping("/api/industry-curation")`，javadoc 写明独立前缀避开公开段的原因）：
  - `POST /companies` → 200 body UnlistedCompanyView / `PUT /companies/{id}` → 200 / `DELETE /companies/{id}` → 204
  - `GET /companies/import/template` → CSV（头照 `PortfolioController.java:160-167`：`Content-Disposition: attachment; filename=unlisted-companies-template.csv`）
  - `POST /companies/import`（multipart `file`）→ 200 `CurationImportResult`；空文件/超 1MB → `IndustryException(INVALID_CSV)`（handler → 400）
  - `GET /funding-events/import/template` + `POST /funding-events/import` 同构；`DELETE /funding-events/{id}` → 204
- `record UnlistedCompanyView(...)`（web 层 DTO，字段同 UnlistedCompany 但 round 用 label？**否**——透出枚举名+label 双字段，前端排序用枚举序）落 `application/industry`（照 `IndustryWatchView.java:6` 位置先例）。
- 鉴权：`Authentication auth` 参数（不调用 currentUserId 也保留参数？**不保留**——无用户语义，方法不收 auth 参数，路由级鉴权已由前缀达成；javadoc 说明）。

- [ ] **Step 1: 失败测试**（`@WebMvcTest(IndustryCurationController.class)` + MockMvc，照 `PortfolioImportControllerTest.java:43-44`；multipart 用例 `.multipart("/api/industry-curation/companies/import").file(...)`，模板用例断言 Content-Disposition）；含 401 未认证（`.with(unauthenticated())` 401）与文件级 400 两分支。
- [ ] **Step 2~4:** FAIL → 实现 → PASS（`./gradlew test --tests '*IndustryCurationControllerTest*'` + `check` 全量回归）。
- [ ] **Step 5:** Commit `feat(industry): /api/industry-curation 写侧端点与模板（MS-10 P1）`

---

### Task 9: 全链导入集成测试（all-or-nothing + upsert 实证）

**Files:**
- Create: `backend/src/integrationTest/java/com/portfolio/invest/industry/CurationImportIntegrationTest.java`

**Interfaces:** 照 `CsvImportIntegrationTest.java:35-36`（`@SpringBootTest extends PostgresTestSupport`）：JdbcTemplate 种子行业映射行（申万 801080/801150 若 `industry_valuation` 无该行业行则先插，**注意 BDD 共享口径**：shenwan_industry_mapping 全表 UNIQUE 须用未被占用的测试代码段）——实际上 existsIndustry 查的是哪个表？核对 `IndustryRepositoryImpl.existsIndustry` 实现后照实种子；样例 CSV 与 Parser 测试同源；断言：首导 inserted=N 表计数快照、重导 updated=N 且字段更新、错误文件六表计数不变、AfterEach 清本测试写入行（`DELETE FROM industry_unlisted_company WHERE company_name LIKE '集成测试%'`，不动 V19 种子）。

- [ ] **Step 1~4:** 写测试（对拍手算基准）→ FAIL（服务已实现时应直接 PASS——此时改为「先写测试红」不可行，照实记录：集成测试为验收性测试，实现已在前置任务 TDD 完成，本任务红态以「故意断错字段」自证测试有效性后修正）→ PASS。
- [ ] **Step 5:** Commit `test(industry): 策展/融资导入全链集成实证（MS-10 P1）`

---

### Task 10: Next 代理路由（multipart 透传）

**Files:**
- Create: `frontend/app/api/industry-curation/[...path]/route.ts`

**Interfaces:** 逐字照 `frontend/app/api/portfolio/[...path]/route.ts:1-28`（resolve 前缀改 `/api/industry-curation`；GET/POST[multipart 分支 arrayBuffer+显式 contentType]/PUT/DELETE 四导出；`export const dynamic = "force-dynamic"`）。仓库无 route 级 vitest 先例（portfolio 同款也无），本路由由 P2 e2e 导入用例端到端覆盖——不补 route 单测（与现状一致）。

- [ ] **Step 1:** 实现（无独立测试步骤，`pnpm build` 或 P2 e2e 验证）。
- [ ] **Step 2:** `cd frontend && pnpm build` 通过（确认 route 类型正确）。
- [ ] **Step 3:** Commit `feat(web): industry-curation 代理路由 multipart 透传（MS-10 P1）`

---

## P1 完成门槛

- `cd backend && ./gradlew check` 全绿（单测+集成+BDD+JaCoCo≥0.80）；
- `cd frontend && pnpm build` 通过；
- 手工冒烟（可选）：`bash scripts/e2e-backend.sh` 起后端，`curl -F file=@模板.csv .../api/industry-curation/funding-events/import`（登录 cookie）返回 200 双计数。
