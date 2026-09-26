# P1 · CSV 批量导入（M08-F12）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 历史交易记录 CSV（六类行）全量预检 all-or-nothing 导入——五层校验管线返回行号+原因清单，通过后单事务实体演化落库（零行情调用）。

**Architecture:** `domain/portfolio` 纯函数模拟器（L5 现金/数量演化）+ `application/portfolio` Commons CSV 解析器（L1/L2）与编排服务（L3/L4/执行）+ web 两端点（模板下载/multipart 导入）；前端新上传组件 + proxy multipart 改造。

**Tech Stack:** Spring Boot 4（Java 21）/ Apache Commons CSV 1.11.0 / JdbcTemplate / Next.js 15 + zod / Playwright。

**Spec:** [01-需求规格](../01-需求规格/需求规格说明.md)（决策 #6/#7/#8/#9/#10/#14）+ [02-设计规格](../02-设计规格/设计规格说明.md) §1（格式/管线/契约以此为准）。

## Global Constraints

- 测试规约：方法名 `given|when|then` 前缀 + 中文 `@DisplayName`（`TestSourceSetConventionsTest.java:75-79`）；test 源集禁 Testcontainers；integrationTest 对真实 PG。
- CSV 口径：RFC 4180、UTF-8（读侧容忍 BOM，写模板带 BOM）——对齐 `web/ScreeningCsv.java` 导出口径。
- 上限：2000 数据行、1MB 文件（超出 400 单条 message）。
- 行级错误不走异常通道：`ImportResult{importedCount, rowErrors[]}` 200 返回；文件级错误走既有 `PortfolioException(INVALID_INPUT)`。
- 模拟器**组内首错即停该组**（防连锁误报），跨组互不影响；错误一次聚齐返回。
- 执行阶段禁止调 `PortfolioApplicationService.buy()/sell()`（每笔触发 quoteQuietly 行情调用，2000 行必超时）——用领域实体方法 `Position.applyBuy/applySell/applyCashDividend/applyStockDividend` 演化（校验抛点在实体内，设计规格 §1.2/§1.4）。
- 未来日期「今日」= 服务器默认时区 `LocalDate.now()`（部署 Asia/Shanghai 口径）。
- 覆盖率：后端 JaCoCo BUNDLE 指令+分支 ≥0.80；前端 V8 ≥80%；`make test` 通过。
- 提交信息末尾带 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

---

### Task 1: 未来日期校验收紧（决策 #9，存量缺口一并补）

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/application/portfolio/PortfolioApplicationService.java`（buy :143 / sell :171 / addCashDividend :251 / addStockDividend :266 / addCashTransaction :109 五方法开头）
- Test: `backend/src/test/java/com/portfolio/invest/application/portfolio/PortfolioApplicationServiceTest.java`（追加；若无既有单测文件则照包内最近邻服务测试风格新建）

**Interfaces:**
- Consumes: 既有五用例签名（`buy(Long, BuyCommand)` 等，均已在规格核对）。
- Produces: 五用例对「日期 > 今日」抛 `PortfolioException(PortfolioErrorCode.INVALID_INPUT, "日期不能晚于今日")`——Task 4 的 L4 层文案与此一致。

- [ ] **Step 1: 写失败测试（五用例各一，given 未来日期 when 调用 then INVALID_INPUT）**

```java
@Test
@DisplayName("买入日期晚于今日被拒绝")
void givenFutureTradeDateWhenBuyThenRejected() {
    BuyCommand cmd = new BuyCommand(1L, "600519", "贵州茅台",
            LocalDate.now().plusDays(1), new BigDecimal("1680.00"),
            new BigDecimal("100"), BigDecimal.ZERO);
    PortfolioException e = assertThrows(PortfolioException.class,
            () -> service.buy(1L, cmd));
    assertEquals(PortfolioErrorCode.INVALID_INPUT, e.getCode());
    assertThat(e.getMessage()).contains("日期不能晚于今日");
}
// sell/addCashDividend/addStockDividend/addCashTransaction 四个同构用例：
// SellCommand(1L, LocalDate.now().plusDays(1), price, qty, fee)
// CashDividendCommand(1L, LocalDate.now().plusDays(1), cashPerShare)
// StockDividendCommand(1L, LocalDate.now().plusDays(1), ratio)
// CashTransactionCommand(1L, DEPOSIT, amount, LocalDate.now().plusDays(1), null)
```

（若既有测试用 mock repository 风格，跟随其 arrange 方式；断言核心是错误码+文案。）

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./gradlew test --tests '*PortfolioApplicationServiceTest*'`
Expected: 五新用例 FAIL（现无校验，走完正常流程或 NOT_FOUND 而非 INVALID_INPUT 未来日期文案）。

- [ ] **Step 3: 最小实现（五处方法体开头加同一段）**

```java
if (cmd.tradeDate().isAfter(LocalDate.now())) {   // 各用例字段名：tradeDate/exDate/txDate
    throw new PortfolioException(PortfolioErrorCode.INVALID_INPUT, "日期不能晚于今日");
}
```

（需 import `java.time.LocalDate`。sell 在 requirePosition 之前、addCashTransaction 在 requireGroup 之前加——顺序无行为差异，统一放方法首行。）

- [ ] **Step 4: 运行测试通过 + 回归存量**

Run: `cd backend && ./gradlew test --tests '*Portfolio*'`
Expected: 全 PASS。若有存量用例用了未来日期构造数据 → FAIL，把该用例数据改为 `LocalDate.now().minusDays(n)`（存量语义本就该是历史日期）。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(portfolio): 五录入用例统一拒绝未来日期（MS-14 决策#9 存量缺口）
Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: domain 纯函数 ImportRow + ImportSimulator（L5 模拟重放）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/portfolio/ImportRow.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/portfolio/ImportSimulator.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/portfolio/ImportSimulatorTest.java`

**Interfaces:**
- Produces（Task 3/4 依赖，签名精确）:
  - `ImportRow(int rowNumber, ImportRowType type, LocalDate date, String stockCode, String groupName, Long groupId, BigDecimal price, BigDecimal quantity, BigDecimal fee, BigDecimal amount, String note)`（record；`ImportRowType` 为其嵌套 enum `BUY, SELL, CASH_DIVIDEND, STOCK_DIVIDEND, DEPOSIT, WITHDRAW`。**groupName 由解析层（Task 3）填充、groupId 为 null；L3（Task 4）完成名→id 解析后以 `withGroupId(Long)` 产出新行**——record 显式手写该方法返回 `new ImportRow(其余字段同值, groupId)`）
  - `ImportSimulator.StartState(Map<Long, BigDecimal> groupCash, Map<String, BigDecimal> holdingQty)`（holdingQty key=`groupId + "|" + stockCode`）
  - `ImportSimulator.RowError(int row, String reason)`
  - `static List<RowError> ImportSimulator.simulate(StartState start, List<ImportRow> rows)`

- [ ] **Step 1: 写失败测试（已知答案手算，覆盖九个行为）**

```java
// 全部纯函数直调，零 mock。样例数据约定：组 1/组 2，股 600519。
// 手算基准（2026-09-25 勘误：起点须 1000000）：现金 1000000；BUY 100 股 ×1680+费 5 = 168005 → 余 831995。

@Test @DisplayName("买入扣现金加持仓，卖出回现金减持仓")
void givenBuyThenSellWhenSimulateThenNoError() {
    var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("1000000")), Map.of());
    var rows = List.of(
            row(2, BUY, "2024-01-05", "600519", 1L, "1680.00", "100", "5.00", null),
            row(3, SELL, "2024-01-10", "600519", 1L, "1750.50", "50", "5.00", null));
    assertThat(ImportSimulator.simulate(start, rows)).isEmpty();
}

@Test @DisplayName("买入现金不足返回行错误并停该组")
void givenInsufficientCashWhenSimulateThenRowError() {
    var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("100000")), Map.of());
    var rows = List.of(row(2, BUY, "2024-01-05", "600519", 1L, "1680.00", "100", "5.00", null));
    var errors = ImportSimulator.simulate(start, rows);
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).row()).isEqualTo(2);
    assertThat(errors.get(0).reason()).contains("现金不足").contains("100000").contains("168005");
}

@Test @DisplayName("转出后现金为负返回行错误")
void givenWithdrawExceedsCashWhenSimulateThenRowError();

@Test @DisplayName("卖出超过重放后持仓返回行错误")
void givenSellExceedsHoldingWhenSimulateThenRowError() {
    // 买 100 卖 200 → 第 3 行 SELL_EXCEEDS 语义错误，文案含"超过持仓"
}

@Test @DisplayName("同日多行按文件行序稳定排序")
void givenSameDayRowsWhenSimulateThenRowNumberBreaksTie() {
    // 行 2=SELL 100（无持仓）、行 3=BUY 100：同日同组——行序排后 SELL 在前 → 行 2 报错；
    // 交换行号（BUY 行号小）则通过。两用例断言排序稳定性。
}

@Test @DisplayName("现金分红按当时持仓计现金额，零持仓报行错误")
void givenCashDividendWithZeroHoldingWhenSimulateThenRowError();

@Test @DisplayName("送股按比例放大持仓数量")
void givenStockDividendWhenSimulateThenQuantityMultiplied() {
    // 买 100 → 送股比例 0.05 → 持仓 105；随后 SELL 105 通过、SELL 106 报错
}

@Test @DisplayName("组内首错即停，其他组不受影响")
void givenErrorInGroup1WhenSimulateThenGroup2StillChecked() {
    // 组 1 第 2 行现金不足（停组 1），组 2 第 3 行卖出超限仍被报出 → 两个错误都在清单
}

@Test @DisplayName("乱序输入按日期排序后演化（历史文件倒序写入场景）")
void givenUnsortedRowsWhenSimulateThenSortedByDate() {
    // 文件行序：先 2024-06 SELL 50 再 2024-01 BUY 100——排序后先买后卖，无错误
}

private static ImportRow row(int rowNumber, ImportRowType type, String date, String stockCode,
                             Long groupId, String price, String qty, String fee, String amount) {
    return new ImportRow(rowNumber, type, LocalDate.parse(date), stockCode, null, groupId,
            price == null ? null : new BigDecimal(price),
            qty == null ? null : new BigDecimal(qty),
            fee == null ? null : new BigDecimal(fee),
            amount == null ? null : new BigDecimal(amount), null);  // 模拟器测试直给 groupId，groupName 留 null
}
```

- [ ] **Step 2: 运行确认失败（类不存在编译错即红）**

Run: `cd backend && ./gradlew test --tests '*ImportSimulatorTest*'`

- [ ] **Step 3: 实现 ImportRow record + ImportSimulator**

```java
// ImportRow.java —— record + 嵌套 enum（字段见 Interfaces；compact 构造器不加校验，校验在 L2）
package com.portfolio.invest.domain.portfolio;
public record ImportRow(int rowNumber, ImportRowType type, LocalDate date, String stockCode,
                        String groupName, Long groupId, BigDecimal price, BigDecimal quantity,
                        BigDecimal fee, BigDecimal amount, String note) {
    public enum ImportRowType { BUY, SELL, CASH_DIVIDEND, STOCK_DIVIDEND, DEPOSIT, WITHDRAW }

    /** L3 分组名解析后的行（模拟器/执行只认 groupId 非空的行）。 */
    public ImportRow withGroupId(Long resolvedGroupId) {
        return new ImportRow(rowNumber, type, date, stockCode, groupName, resolvedGroupId,
                price, quantity, fee, amount, note);
    }
}

// ImportSimulator.java 核心骨架（完整 switch 六分支按测试驱动补齐）：
public final class ImportSimulator {
    public record StartState(Map<Long, BigDecimal> groupCash, Map<String, BigDecimal> holdingQty) {}
    public record RowError(int row, String reason) {}

    public static List<RowError> simulate(StartState start, List<ImportRow> rows) {
        List<ImportRow> sorted = rows.stream()
                .sorted(Comparator.comparing(ImportRow::groupId)
                        .thenComparing(ImportRow::date)
                        .thenComparing(ImportRow::rowNumber))
                .toList();
        var cash = new HashMap<>(start.groupCash());
        var qty = new HashMap<>(start.holdingQty());
        List<RowError> errors = new ArrayList<>();
        Set<Long> stoppedGroups = new HashSet<>();
        for (ImportRow r : sorted) {
            if (stoppedGroups.contains(r.groupId())) continue;
            Optional<RowError> err = switch (r.type()) {
                case DEPOSIT -> { cash.merge(r.groupId(), r.amount(), BigDecimal::add); yield Optional.empty(); }
                case WITHDRAW -> { /* 转出后<0 → 错误，else cash 扣减 */ }
                case BUY -> { /* cost=price*qty+fee；不足→错误（文案含可用/需，照 buy() 口径）；否则扣现金加持仓 */ }
                case SELL -> { /* 超持仓→错误；否则减持仓、现金 += price*qty-fee */ }
                case CASH_DIVIDEND -> { /* 持仓<=0→错误；否则现金 += price*当时持仓 */ }
                case STOCK_DIVIDEND -> { /* 持仓<=0→错误；否则持仓 *= (1+price) */ }
            };
            err.ifPresent(e -> { errors.add(e); stoppedGroups.add(r.groupId()); });
        }
        return errors;
    }
    private static String key(ImportRow r) { return r.groupId() + "|" + r.stockCode(); }
}
```

（错误文案常量对齐既有用例：「现金不足：可用 X，本次需 Y（含费）」/「卖出超过持仓」/「转出后分组现金为负」/「分红时该标的持仓为 0」。）

- [ ] **Step 4: 运行测试通过**

Run: `cd backend && ./gradlew test --tests '*ImportSimulatorTest*'` → PASS（九用例全绿）。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(portfolio): CSV 导入 L5 模拟重放器纯函数（组内首错停/排序稳定）
Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: CsvImportParser（L1/L2/L4，Commons CSV）

**Files:**
- Modify: `backend/build.gradle`（:86 附近 dependencies 区加一行）
- Create: `backend/src/main/java/com/portfolio/invest/application/portfolio/CsvImportParser.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/portfolio/CsvImportParserTest.java`

**Interfaces:**
- Consumes: `ImportRow`/`ImportRowType`（Task 2）。
- Produces: `CsvImportParser.ParseOutcome(List<ImportRow> rows, List<ImportSimulator.RowError> errors)` + `ParseOutcome parse(String content, LocalDate today)`（L1/L2/L4 全在此层：rows 与 errors 互斥——有 errors 时 rows 为空 List）。

- [ ] **Step 1: build.gradle 加依赖**

```groovy
implementation 'org.apache.commons:commons-csv:1.11.0'
```

Run: `cd backend && ./gradlew dependencies --configuration runtimeClasspath | grep commons-csv` → 出现 1.11.0。

- [ ] **Step 2: 写失败测试（每层至少两用例）**

```java
// 样例 CSV 直接内嵌 Java 文本块。表头行 + 六类型示例行见设计规格 §1.1。
@Test @DisplayName("L1：标准文件解析出六行 ImportRow 且类型映射正确")
void givenWellFormedCsvWhenParseThenSixRows() {
    var out = parser.parse(TEMPLATE_WITH_ALL_SIX_TYPES, LocalDate.now());
    assertThat(out.errors()).isEmpty();
    assertThat(out.rows()).hasSize(6);
    assertThat(out.rows().get(0).type()).isEqualTo(ImportRowType.BUY);
    // DEPOSIT 行 stockCode 为 null、amount=200000；CASH_DIVIDEND 行 quantity 为 null、price=25.63
}

@Test @DisplayName("L1：UTF-8 BOM 前缀不破坏表头匹配")
void givenBomHeaderWhenParseThenOk();

@Test @DisplayName("L1：列数不符返回行号错误")
void givenEightColumnsWhenParseThenRowError() { /* reason 含"期望 9 列" */ }

@Test @DisplayName("L1：超 2000 数据行返回文件级 ParseOutcome 错误（row=0）")
void givenOverLimitRowsWhenParseThenFileError();

@Test @DisplayName("L2：类型枚举外值返回行错误")
void givenInvalidTypeWhenParseThenRowError() { /* "类型无效 FOO" */ }

@Test @DisplayName("L2：BUY 负价格/零价格返回行错误；DEPOSIT 金额≤0 返回行错误")
void givenNonPositiveNumbersWhenParseThenRowError();

@Test @DisplayName("L2：日期不可解析返回行错误")
void givenMalformedDateWhenParseThenRowError();

@Test @DisplayName("L2：类型相关列空值约束（BUY 缺数量、DEPOSIT 带证券代码均报错）")
void givenTypeColumnMismatchWhenParseThenRowError();

@Test @DisplayName("L4：日期晚于 today 参数返回行错误")
void givenFutureDateWhenParseThenRowError() { /* "日期不能晚于今日" */ }
```

- [ ] **Step 3: 运行确认失败 → 实现 → 通过 → Commit**

实现要点：`CSVFormat.DEFAULT.builder().setHeader(HEADER).setIgnoreEmptyLines(true).build()`（**不 skip header**——表头须作记录 1 精确匹配，skip 后表头从 getRecords() 消失；2026-09-25 T3 实现者 jshell 实测勘误）；`new CSVParser(new StringReader(stripBom(content)), format)`（stripBom 手写：startsWith("﻿") 去首字符）；列约束表按设计规格 §1.1 类型×列矩阵硬编码（BUY/SELL 需 price>0/qty>0/fee≥0 可空默认 0、代码分组必填、SELL 另加 fee<price×qty；分红需 price>0、代码分组必填、qty/fee/amount 必空；DEPOSIT/WITHDRAW 需 amount>0、代码/价格/数量必空、分组必填）。数值解析 try/catch NumberFormatException → L2 行错误。**注意规格 §1.1 示例 CSV 已于 2026-09-25 勘误（DEPOSIT/WITHDRAW 行金额原错位在数量/价格列）——Task 5 模板须用勘误后行**。

```bash
git add -A && git commit -m "feat(portfolio): CSV 导入解析器 L1/L2/L4（Commons CSV，BOM 容忍）
Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: PortfolioImportService（L3 引用校验 + L5 编排 + 执行落库）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/portfolio/PortfolioImportService.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/portfolio/ImportResult.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/portfolio/PortfolioImportServiceTest.java`（mock `PortfolioRepository` 单测）

**Interfaces:**
- Consumes: Task 2/3 产物；`PortfolioRepository`（`findPortfolioByUserId`/`findGroupsByPortfolioId`/`findPositionsByGroupId`/`findCashTransactionsByGroupId`/`findPositionByPortfolioIdAndGroupIdAndStockCode`/`savePosition`/`saveTrade`/`saveDividend`/`saveCashTransaction`）；领域构造器 `Position.create(portfolioId, groupId, stockCode, stockName, Instant)`、`Trade(null, positionId, TradeType, tradeDate, price, quantity, fee, createdAt)`、`Dividend(null, positionId, DividendType, exDate, cashPerShare, stockRatio, createdAt)`、`CashTransaction(null, groupId, CashTransactionType, amount, txDate, note, createdAt)`；`GroupType.ACCOUNT`。
- Produces: `ImportResult(int importedCount, List<ImportSimulator.RowError> rowErrors)` + `ImportResult importCsv(Long userId, String csvContent)`（Task 5 端点依赖）。

- [ ] **Step 1: 写失败测试（编排全链，mock repository）**

```java
@Test @DisplayName("六类行标准文件导入成功：importedCount=6 且分组内现金/持仓/记录全部落库")
void givenValidFileWhenImportThenAllPersisted() {
    // arrange：mock repository——userId→portfolio(1L)、组"主账户"→HoldingGroup(1L, ACCOUNT)；
    // findPositionsByGroupId 空、findCashTransactionsByGroupId 空（起点现金 0）；
    // 文件首行 DEPOSIT 200000 再 BUY 600519 100 股……
    ImportResult r = service.importCsv(1L, SIX_ROW_CSV);
    assertThat(r.rowErrors()).isEmpty();
    assertThat(r.importedCount()).isEqualTo(6);
    // verify：saveCashDocument×2、saveTrade×2、saveDividend×2、savePosition ≥1；
    // ArgumentCaptor 断言 Trade 记录字段（date/type/price/qty/fee）与 CSV 行一致
}

@Test @DisplayName("分组不存在返回行错误且零落库（save* 全零调用）")
void givenUnknownGroupWhenImportThenRowErrorAndNoSave() { /* reason 含"分组不存在" */ }

@Test @DisplayName("TAG 类型分组返回行错误（仅 ACCOUNT 可导入）")
void givenTagGroupWhenImportThenRowError() { /* reason 含"仅支持账户分组" */ }

@Test @DisplayName("解析层错误直接透传（不触达 repository）")
void givenMalformedCsvWhenImportThenParseErrorsPassthrough();

@Test @DisplayName("L5 现金不足零落库：文件 DEPOSIT 100000 + BUY 168005 → 行错误 + save* 全零")
void givenSimulatedInsufficientCashWhenImportThenNothingPersisted();

@Test @DisplayName("既有持仓作起点：库内已有 100 股（mock findPositionsByGroupId 返回），文件只 SELL 150 → 行错误")
void givenExistingHoldingAsStartWhenSimulateThenChecked();

@Test @DisplayName("卖出复用既有持仓行（含已清仓行）：SELL 行解析到既有 positionId，不新建 Position")
void givenExistingPositionRowWhenImportThenReusePositionId() { /* ArgumentCaptor: savePosition 收到的 Position.id 非空 */ }
```

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

```java
@Service
public class PortfolioImportService {
    private final PortfolioRepository repository;
    private final CsvImportParser parser;

    @Transactional
    public ImportResult importCsv(Long userId, String csvContent) {
        var parsed = parser.parse(csvContent, LocalDate.now());
        if (!parsed.errors().isEmpty()) return new ImportResult(0, parsed.errors());

        Portfolio p = repository.findPortfolioByUserId(userId)
                .orElseThrow(() -> new PortfolioException(PortfolioErrorCode.NOT_FOUND, "组合不存在"));
        // L3：分组名→实体（存在 + ACCOUNT；同名多组取最早——holding_group.name 无唯一约束，文档化近似）
        Map<String, HoldingGroup> groups = repository.findGroupsByPortfolioId(p.id()).stream()
                .filter(g -> g.type() == GroupType.ACCOUNT)
                .collect(Collectors.toMap(HoldingGroup::name, g -> g, (a, b) -> a, LinkedHashMap::new));
        List<ImportSimulator.RowError> errors = new ArrayList<>();
        List<ImportRow> resolved = new ArrayList<>();
        for (ImportRow raw : parsed.rows()) {
            // 解析层已填 groupName、groupId=null（ImportRow 双字段设计，Task 2 Interfaces）；
            // 本层完成 name→id：查不到或非 ACCOUNT → 行错误「分组不存在/仅支持账户分组」
            HoldingGroup g = groups.get(raw.groupName());
            if (g == null) { errors.add(new ImportSimulator.RowError(raw.rowNumber(),
                    "分组不存在「" + raw.groupName() + "」，请先在页面创建或修改 CSV")); continue; }
            resolved.add(raw.withGroupId(g.id()));
        }
        if (!errors.isEmpty()) return new ImportResult(0, errors);
        // L5：起点态组装（组现金=cashBalance 口径手写同构：Σ持仓.netCashFlow+Σ(转入−转出)；持仓数量）
        // simulate() 非空 → return new ImportResult(0, errors)
        // 执行：按 simulate 同序（重排序一次），逐行 switch 落库（实体方法演化，见设计规格 §1.2）
        // BUY/SELL：pos = findPositionByPortfolioIdAndGroupIdAndStockCode(...).orElseGet(() -> Position.create(...));
        //           pos = BUY ? pos.applyBuy(price, qty, fee) : pos.applySell(...);
        //           var saved = repository.savePosition(pos); repository.saveTrade(new Trade(null, saved.id(), ...));
        // 分红：pos.applyCashDividend(price.multiply(pos.quantity())) / applyStockDividend(price)
        //       savePosition + saveDividend(new Dividend(null, saved.id(), DividendType.CASH/STOCK, date, price|ratio, null, now))
        // DEPOSIT/WITHDRAW：saveCashTransaction(new CashTransaction(null, groupId, CashTransactionType.DEPOSIT/WITHDRAW, amount, date, note, now))
        return new ImportResult(resolved.size(), List.of());
    }
}
```

（执行排序键与模拟器完全一致：groupId→date→rowNumber——抽公共 `private static List<ImportRow> sorted(List<ImportRow>)` 或让模拟器暴露 `sortedRows()`，保证同构。）

- [ ] **Step 4: 运行通过（含 Task 1~3 回归）→ Step 5: Commit**

```bash
git add -A && git commit -m "feat(portfolio): CSV 导入编排服务（L3 引用校验+L5 编排+实体演化执行）
Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: web 端点（模板下载 + multipart 导入）

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/web/PortfolioController.java`（追加两端点）
- Create: `backend/src/main/java/com/portfolio/invest/web/PortfolioImportTemplate.java`（模板 CSV 常量）
- Test: `backend/src/test/java/com/portfolio/invest/web/PortfolioImportControllerTest.java`（MockMvc 照包内既有 Controller 测试风格）

**Interfaces:**
- Consumes: `PortfolioImportService.importCsv(Long, String)`（Task 4）。
- Produces: `GET /api/portfolio/import/template`（attachment CSV，UTF-8 BOM）；`POST /api/portfolio/import`（multipart 字段 `file`，200 `ImportResult` JSON）——Task 6 前端依赖。

- [ ] **Step 1: 写失败测试**

```java
@Test @DisplayName("模板下载返回 BOM 头+九列表头+六类型示例行")
void givenTemplateRequestWhenGetThenCsvWithBom() {
    MvcResult r = mockMvc.perform(get("/api/portfolio/import/template")).andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=import-template.csv"))
            .andReturn();
    assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).startsWith("﻿日期,类型");
}

@Test @DisplayName("导入端点 multipart 返回 ImportResult JSON")
void givenMultipartWhenPostThenImportResult() { /* 200 + $.importedCount + $.rowErrors */ }

@Test @DisplayName("空文件/超限返回 400 INVALID_INPUT")
void givenEmptyFileWhenPostThen400();

@Test @DisplayName("未登录 401（/api/portfolio/** 本就需登录，回归确认）")
void givenAnonymousWhenPostThen401();
```

- [ ] **Step 2: 确认失败 → Step 3: 实现**

```java
@GetMapping("/import/template")
public ResponseEntity<String> importTemplate() {
    return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=import-template.csv")
            .header("Content-Type", "text/csv; charset=UTF-8")
            .body(PortfolioImportTemplate.CSV);   // 常量：BOM + 表头 + §1.1 六示例行
}

@PostMapping("/import")
public ImportResult importCsv(Authentication auth, @RequestParam("file") MultipartFile file) {
    if (file == null || file.isEmpty()) throw new PortfolioException(PortfolioErrorCode.INVALID_INPUT, "文件为空");
    long dataLines = ...; // 按行数粗校 ≤2000+1（双保险，精确校验在解析层）；文件 >1MB 同样 400
    try {
        return importService.importCsv(currentUserId(auth), file.getInputStream().readAllBytes()  /* UTF-8 decode */);
    } catch (CharacterCodingException e) { throw new PortfolioException(INVALID_INPUT, "文件编码须为 UTF-8"); }
}
```

（行数/大小文件级校验在读内容前完成；`spring.servlet.multipart.max-file-size` 确认 ≥1MB 默认 1MB 恰好边界——application.yml 显式设 2MB 并在端点校 1MB。）

- [ ] **Step 4: 通过 → Step 5: Commit**（`feat(portfolio): CSV 导入两端点（模板下载/multipart 导入）`）

---

### Task 6: 前端（proxy multipart + ImportDialog + 挂载）

**Files:**
- Modify: `frontend/lib/proxy.ts`（relay FormData 分支）
- Modify: `frontend/app/api/portfolio/[...path]/route.ts`（POST 改 arrayBuffer + 透传 content-type）
- Create: `frontend/components/portfolio/ImportDialog.tsx`
- Modify: `frontend/components/portfolio/PortfolioBoard.tsx`（工具区挂「批量导入」按钮 + 成功后 reload()）
- Create: `frontend/lib/portfolioImportApi.ts`（templateHref/importCsv）
- Test: `frontend/tests/components/portfolio/ImportDialog.test.tsx` + `frontend/tests/lib/portfolioImportApi.test.ts`（照既有 vitest 目录结构）

**Interfaces:**
- Consumes: 后端两端点（Task 5）；`useSaveAction`（防连点）；PortfolioBoard `reload()`。
- Produces: `importCsv(file: File): Promise<ImportResult>`；`ImportResult{importedCount: number; rowErrors: {row: number; reason: string}[]}`（zod schema 补 `lib/schemas.ts`）。

- [ ] **Step 1: 写失败测试（ImportDialog 三态：选择/成功/行错误清单渲染）**

```tsx
// mock portfolioImportApi.importCsv；断言：
// 1. 选择文件前提交按钮 disabled；2. 成功后显示"成功导入 N 笔"并触发 onImported（→reload）；
// 3. rowErrors 非空渲染表格行号+原因（aria-label="import-row-errors"），且不触发 onImported；
// 4. 未选文件直接提交不调 API。
```

- [ ] **Step 2: 确认失败 → Step 3: 实现**

```ts
// proxy.ts relay 内 body 分支改造（FormData 不设 Content-Type，浏览器生成 boundary）：
headers: {
  ...(body !== undefined && !(body instanceof FormData) ? { "Content-Type": "application/json" } : {}),
  ...(cookie ? { Cookie: cookie } : {}),
},

// app/api/portfolio/[...path]/route.ts POST：
export async function POST(req: Request, ctx) {
  const path = await resolve(req, ctx);
  const contentType = req.headers.get("content-type");
  if (contentType?.startsWith("multipart/form-data")) {
    return relay(path, "POST", req, await req.arrayBuffer(), undefined, contentType);
  }
  return relay(path, "POST", req, await req.text());
}
// relay 签名扩展（新增一个六参重载，置于既有五参重载之后）：
//   relay(path: string, method: string, req?: Request, body?: BodyInit,
//        timeoutMs?: number, contentType?: string)
// ——contentType 存在时不设默认 JSON 头、显式透传该值（字节流场景；FormData 走 instanceof
//    分支由浏览器生成 boundary 头，两者互斥）。multipart 用默认 15s 超时，无需自定义 timeoutMs。

// portfolioImportApi.ts
export function templateHref() { return "/api/portfolio/import/template"; }
export async function importCsv(file: File): Promise<ImportResult> {
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch("/api/portfolio/import", { method: "POST", body: fd });
  const body = await res.json();       // 非 2xx 时 body.message
  if (!res.ok) throw new Error(body.message ?? "导入失败");
  return ImportResultSchema.parse(body);
}
```

（ImportDialog：`<input type="file" accept=".csv,text/csv">` + 提交（useSaveAction 防连点）+ 结果态三块；模板下载 `<a href={templateHref()} download>`。）

- [ ] **Step 4: `cd frontend && npx vitest run` 全绿 → Step 5: Commit**（`feat(portfolio): 前端批量导入对话框与 multipart 代理`）

---

### Task 7: 集成测试 + e2e + P1 收口

**Files:**
- Create: `backend/src/integrationTest/java/com/portfolio/invest/portfolio/CsvImportIntegrationTest.java`（真实 PG，照包内既有集成测试风格）
- Modify: `frontend/e2e/portfolio.spec.ts`（追加两用例）

**Interfaces:**
- Consumes: Task 4/5/6 全链；e2e 既有 `ADMIN_USERNAME/ADMIN_PASSWORD` seed 与登录 helper（portfolio.spec 既有 gate 模式）。

- [ ] **Step 1: 集成测试（真实库全链对拍——模拟器与执行演化一致性）**

```java
// 前置：建组"导入测试组"（ACCOUNT）；文件：DEPOSIT 200000 → BUY 100×1680+5 → SELL 50×1750.5+5
//       → CASH_DIVIDEND 25.63/股(余50股) → STOCK_DIVIDEND 0.05 → WITHDRAW 1000
// 断言（手算已知答案；2026-09-25 T7 勘误——SELL 回款 87520 已含费用扣除，勿再减 5）：
//   组现金 = 200000 −168005 +87520 +1281.5 −1000 = 119796.5
//   持仓数量 = 100 −50 =50 → 送股×1.05 = 52.5
//   trade 2 行/dividend 2 行/cash_transaction 2 行
// 第二用例：同文件但 SELL 行数量改 999 → rowErrors 含行号、六表零写入（SELECT count 对拍）
```

- [ ] **Step 2: e2e 两用例（真浏览器）**

```ts
// 1. 登录 → /portfolio → 批量导入 → 上传含错误行的 CSV → 断言行错误表渲染（行号+原因）且持仓不变
// 2. 上传六行标准 CSV → 断言"成功导入 6 笔" + 持仓列表出现 600519（E2E_FRESH_BUILD=1 全量跑）
```

- [ ] **Step 3: 全量验证**

Run: `make test`（后端+前端+collector 全绿）→ `cd frontend && E2E_FRESH_BUILD=1 npx playwright test portfolio` → 全 PASS。

- [ ] **Step 4: Commit**（`test(portfolio): CSV 导入集成对拍与 e2e（MS-14 P1 收口）`）
