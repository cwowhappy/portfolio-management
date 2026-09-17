# P2 · 后端 domain/industry 新域实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `domain/industry` 读侧域：`GET /api/industry/board`（31 行业四指标 + PE/PB 5y 分位 + 景气）与 `GET /api/industry/{code}/stocks`（行业成员排名），公开只读、5min 应用缓存。

**Architecture:** 照 screening 域全套模式（纯 record + 端口 + 错误码常量类 + 域异常 + JdbcTemplate 原生 SQL + ApplicationCache 包装 + GlobalExceptionHandler 内联 switch）。分位与景气阈值是域内纯函数；SQL 只做聚合取数。域间零依赖（不 import `domain/valuation`）。

**Tech Stack:** Java 21 / Spring Boot（JdbcTemplate、Testcontainers 集成测试、Cucumber BDD）。

**Spec:** [01-需求规格](../01-需求规格/需求规格说明.md) §三.A/B/C | [02-设计规格](../02-设计规格/设计规格说明.md) §四（本计划实现其 P2 切分；读模型命名在计划中细化为 `IndustryValuationRow`/`IndustryValuationPoint` + 应用层 `IndustryBoardView` 组合，取代设计稿的单个 `IndustryBoardRow`）

## Global Constraints

- `PackageConventionsTest` 按层通配自动覆盖 `domain/industry`——**不改测试**，但交付时须在 `docs/technology/conventions/01-后端DDD分包规范.md` 域清单登记（Task 10）；
- domain 包零 Spring 注解、不依赖其他域包（`PackageConventionsTest.java:70-73,114-120`）；
- 公开端点登记：`PublicEndpointPaths.PREFIXES` 加 `"/api/industry/"`（`PublicEndpointPaths.java:20-25`），`SecurityConfig.java:51-52` 与 `ActiveUserFilter.java:26` 自动生效；
- 景气阈值常量（±1pp / ±10%）与分位门槛（250 交易日 / 5 年窗口）硬编码在域内，改口径走发版；
- 覆盖率：JaCoCo ≥80%，`cd backend && ./gradlew check` 全绿（含集成/BDD）。

---

### Task 1: Prosperity 景气纯函数（TDD）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/industry/Prosperity.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/industry/ProsperityTest.java`

**Interfaces:**
- Produces: `enum Prosperity { UP, FLAT, DOWN }` + `static Prosperity of(BigDecimal roeDelta, BigDecimal revenueYoy)`（任一入参 null → null）；常量 `ROE_DELTA_UP=1` / `ROE_DELTA_DOWN=-1`（pp）、`REVENUE_UP=10` / `REVENUE_DOWN=-10`（%）。Task 5/6/7 消费。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.domain.industry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProsperityTest {

    @Test
    void up_requires_both_roeDelta_and_revenue() {
        assertThat(Prosperity.of(new BigDecimal("1"), new BigDecimal("10"))).isEqualTo(Prosperity.UP);
        assertThat(Prosperity.of(new BigDecimal("0.99"), new BigDecimal("10"))).isEqualTo(Prosperity.FLAT);
        assertThat(Prosperity.of(new BigDecimal("1"), new BigDecimal("9.99"))).isEqualTo(Prosperity.FLAT);
    }

    @Test
    void down_triggers_on_either_side() {
        assertThat(Prosperity.of(new BigDecimal("-1"), new BigDecimal("50"))).isEqualTo(Prosperity.DOWN);
        assertThat(Prosperity.of(new BigDecimal("10"), new BigDecimal("-10"))).isEqualTo(Prosperity.DOWN);
        assertThat(Prosperity.of(new BigDecimal("-0.99"), new BigDecimal("-9.99"))).isEqualTo(Prosperity.FLAT);
    }

    @Test
    void null_input_returns_null() {
        assertThat(Prosperity.of(null, new BigDecimal("10"))).isNull();
        assertThat(Prosperity.of(new BigDecimal("1"), null)).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests 'com.portfolio.invest.domain.industry.ProsperityTest' -q`
Expected: 编译 FAIL（`Prosperity` 不存在）。

- [ ] **Step 3: 实现**

```java
package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;

/**
 * 景气度三档标注（M10-F05）：ROE 趋势（近4季均值−前4季均值，pp）与营收增速（最新报告期，%）双输入。
 * 上行=且（两条件同时达门槛，防单指标噪声）；下行=或（更敏感，「且/或」不对称为有意设计）。任一输入缺失返回 null（不标注）。
 */
public enum Prosperity {
    UP, FLAT, DOWN;

    static final BigDecimal ROE_DELTA_UP = new BigDecimal("1");
    static final BigDecimal ROE_DELTA_DOWN = new BigDecimal("-1");
    static final BigDecimal REVENUE_UP = new BigDecimal("10");
    static final BigDecimal REVENUE_DOWN = new BigDecimal("-10");

    public static Prosperity of(BigDecimal roeDelta, BigDecimal revenueYoy) {
        if (roeDelta == null || revenueYoy == null) {
            return null;
        }
        if (roeDelta.compareTo(ROE_DELTA_UP) >= 0 && revenueYoy.compareTo(REVENUE_UP) >= 0) {
            return UP;
        }
        if (roeDelta.compareTo(ROE_DELTA_DOWN) <= 0 || revenueYoy.compareTo(REVENUE_DOWN) <= 0) {
            return DOWN;
        }
        return FLAT;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests 'com.portfolio.invest.domain.industry.ProsperityTest' -q`
Expected: 3 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/industry/ backend/src/test/java/com/portfolio/invest/domain/industry/
git commit -m "feat(industry): Prosperity 景气三档纯函数（MS-09 F05）"
```

---

### Task 2: WindowedPercentile 窗口化分位（TDD）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/industry/WindowedPercentile.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/industry/WindowedPercentileTest.java`

**Interfaces:**
- Produces: `static BigDecimal of(BigDecimal current, List<BigDecimal> windowSeries)`——经验分布百分位（严格小于占比 ×100，2 位 HALF_UP；语义同估值域 `Percentile.of` 但加窗口门槛），序列非空值 < 250 返回 null。Task 7 消费。**不 import `domain.valuation.Percentile`**（域间零依赖；语义一致由本测试显式断言锚定）。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.domain.industry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowedPercentileTest {

    private static List<BigDecimal> series(int size, int belowCount, BigDecimal current) {
        // belowCount 个严格小于 current + 1 个等于 + 其余大于（等于不计入 below，对齐经验分布口径）
        List<BigDecimal> s = new ArrayList<>();
        for (int i = 0; i < belowCount; i++) s.add(current.subtract(BigDecimal.ONE));
        s.add(current);
        for (int i = belowCount + 1; i < size; i++) s.add(current.add(BigDecimal.ONE));
        return s;
    }

    @Test
    void below_ratio_times_100_half_up() {
        var current = new BigDecimal("50");
        // 250 个值中 100 个严格小于 → 100/250 = 40.00
        assertThat(WindowedPercentile.of(current, series(250, 100, current)))
                .isEqualByComparingTo("40.00");
        // 249+1=250 个值中 1 个小于 → 1/250 = 0.40（除不尽验证 HALF_UP 到 2 位）
        assertThat(WindowedPercentile.of(current, series(250, 1, current)))
                .isEqualByComparingTo("0.40");
    }

    @Test
    void insufficient_series_returns_null() {
        assertThat(WindowedPercentile.of(new BigDecimal("50"), series(249, 100, new BigDecimal("50")))).isNull();
        assertThat(WindowedPercentile.of(new BigDecimal("50"), List.of())).isNull();
        assertThat(WindowedPercentile.of(null, series(250, 1, new BigDecimal("50")))).isNull();
    }

    @Test
    void null_elements_excluded_from_denominator() {
        var values = new ArrayList<BigDecimal>();
        for (int i = 0; i < 250; i++) values.add(new BigDecimal("10"));
        values.add(null); // 251 个元素、250 个非空——仍够门槛，分母按非空计
        assertThat(WindowedPercentile.of(new BigDecimal("20"), values)).isEqualByComparingTo("100.00");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests 'com.portfolio.invest.domain.industry.WindowedPercentileTest' -q`
Expected: 编译 FAIL。

- [ ] **Step 3: 实现**

```java
package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 窗口化历史分位：当前值在窗口序列经验分布中的百分位（0~100，2 位 HALF_UP，语义对齐估值域 Percentile）。 */
public final class WindowedPercentile {

    /** 序列非空值不足该门槛（约一年交易日）时返回 null——「数据积累中」，不输出误导性分位。 */
    public static final int MIN_SERIES_DAYS = 250;

    private WindowedPercentile() {}

    public static BigDecimal of(BigDecimal current, List<BigDecimal> windowSeries) {
        if (current == null || windowSeries == null || windowSeries.isEmpty()) {
            return null;
        }
        long below = 0;
        long size = 0;
        for (BigDecimal v : windowSeries) {
            if (v == null) continue;
            size++;
            if (v.compareTo(current) < 0) below++;
        }
        if (size < MIN_SERIES_DAYS) {
            return null;
        }
        return BigDecimal.valueOf(below)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests 'com.portfolio.invest.domain.industry.WindowedPercentileTest' -q`
Expected: 3 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/industry/WindowedPercentile.java backend/src/test/java/com/portfolio/invest/domain/industry/WindowedPercentileTest.java
git commit -m "feat(industry): WindowedPercentile 窗口化分位（MS-09 F04，250 日门槛）"
```

---

### Task 3: 错误码 + 域异常 + 读模型 + 仓储端口

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/industry/IndustryErrorCode.java`、`IndustryException.java`、`IndustryValuationRow.java`、`IndustryValuationPoint.java`、`IndustryStock.java`、`IndustryProsperitySnapshot.java`、`IndustryRepository.java`

**Interfaces:**
- Consumes: Task 1 `Prosperity`（`IndustryStock` 字段类型）；
- Produces（后续任务的契约，全部在此定义）：

```java
public record IndustryValuationRow(String industryCode, String industryName,
        BigDecimal pe, BigDecimal pb, BigDecimal roe, BigDecimal dividendYield) {}

public record IndustryValuationPoint(LocalDate tradingDay, String industryCode, BigDecimal pe, BigDecimal pb) {}

/** A1 取舍（同 StockScreeningResult）：纯数据读模型直接作响应契约。 */
public record IndustryStock(String stockCode, String stockName, BigDecimal totalMv,
        BigDecimal revenue, LocalDate revenueReportDate, BigDecimal roe,
        BigDecimal peTtm, BigDecimal pb, BigDecimal dividendYield, Prosperity prosperity) {}

public record IndustryProsperitySnapshot(String industryCode, BigDecimal roeDeltaMedian,
        BigDecimal revenueYoyMedian, long sampleSize) {}

public interface IndustryRepository {
    List<IndustryValuationRow> findLatestIndustries();
    List<IndustryValuationPoint> findValuationHistorySince(LocalDate since);
    boolean existsIndustry(String industryCode);
    List<IndustryStock> findIndustryStocks(String industryCode, String sortBy, String direction, int limit);
    List<IndustryProsperitySnapshot> findIndustryProsperity();
}
```

- [ ] **Step 1: 写六个类型文件**

`IndustryErrorCode.java`（照 `ScreeningErrorCode.java:4-13` 常量类模式）：

```java
package com.portfolio.invest.domain.industry;

public final class IndustryErrorCode {
    private IndustryErrorCode() {}

    public static final String INDUSTRY_NOT_FOUND = "INDUSTRY_NOT_FOUND";
    public static final String INVALID_SORT = "INDUSTRY_INVALID_SORT";
    public static final String INVALID_LIMIT = "INDUSTRY_INVALID_LIMIT";
}
```

`IndustryException.java`（照 `ScreeningException.java:3-14`）：

```java
package com.portfolio.invest.domain.industry;

public class IndustryException extends RuntimeException {
    private final String code;

    public IndustryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

（records 与端口接口按上方 Interfaces 原文落文件；`IndustryRepository` 无实现注解——实现在 infrastructure。）

- [ ] **Step 2: 编译验证**

Run: `cd backend && ./gradlew compileJava -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/industry/
git commit -m "feat(industry): 域读模型/端口/错误码（MS-09 P2 骨架）"
```

---

### Task 4: IndustryRepositoryImpl——估值行与历史序列（TDD 集成）

**Files:**
- Create: `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/IndustryRepositoryImplTest.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/IndustryRepositoryImpl.java`（本任务先实现前两个方法）

**Interfaces:**
- Consumes: Task 3 端口与 records；
- Produces: `findLatestIndustries()`（最新交易日 `industry_valuation` 全行业行）、`findValuationHistorySince(LocalDate)`（升序序列点）、`existsIndustry(String)`（申万映射存在性）。

- [ ] **Step 1: 写失败集成测试**

```java
package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.industry.IndustryRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.portfolio.invest.support.PostgresTestSupport;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(IndustryRepositoryImpl.class)
class IndustryRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired IndustryRepository repository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @Sql(statements = {
        "INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield) VALUES"
        + " ('2026-01-02','801780','银行',5.5,0.8,12.0,4.0),('2026-01-02','801010','农林牧渔',20.0,2.0,NULL,NULL)",
        "INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield) VALUES ('2026-01-01','801780','银行',5.0,0.7,11.0,3.5)",
        "INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name) VALUES ('600519','贵州茅台','801780','银行')"
    }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void finds_latest_industries_and_history() {
        var latest = repository.findLatestIndustries();
        assertThat(latest).extracting("industryCode").containsExactlyInAnyOrder("801780", "801010");
        var bank = latest.stream().filter(r -> r.industryCode().equals("801780")).findFirst().orElseThrow();
        assertThat(bank.pe()).isEqualByComparingTo("5.5");

        var history = repository.findValuationHistorySince(LocalDate.of(2025, 1, 1));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).tradingDay()).isEqualTo(LocalDate.of(2026, 1, 1)); // 升序

        assertThat(repository.existsIndustry("801780")).isTrue();
        assertThat(repository.existsIndustry("999999")).isFalse();
    }
}
```

（注解组合照 `ScreeningRepositoryImplTest.java:28-35`；`@ServiceConnection` 静态容器 + `PostgresTestSupport.postgres()` 同款。若该测试类有 `@DynamicPropertySource`/清理钩子，照抄。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew integrationTest --tests 'com.portfolio.invest.infrastructure.persistence.IndustryRepositoryImplTest' -q`
Expected: 编译 FAIL（`IndustryRepositoryImpl` 不存在）。

- [ ] **Step 3: 实现（本任务三个方法）**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.IndustryProsperitySnapshot;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.IndustryValuationPoint;
import com.portfolio.invest.domain.industry.IndustryValuationRow;
import com.portfolio.invest.domain.industry.Prosperity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IndustryRepositoryImpl implements IndustryRepository {

    private final JdbcTemplate jdbc;

    public IndustryRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<IndustryValuationRow> findLatestIndustries() {
        var sql = """
                SELECT industry_code, industry_name, pe, pb, roe, dividend_yield
                FROM industry_valuation
                WHERE trading_day = (SELECT max(trading_day) FROM industry_valuation)
                """;
        return jdbc.query(sql, (rs, i) -> new IndustryValuationRow(
                rs.getString("industry_code"), rs.getString("industry_name"),
                rs.getBigDecimal("pe"), rs.getBigDecimal("pb"),
                rs.getBigDecimal("roe"), rs.getBigDecimal("dividend_yield")));
    }

    @Override
    public List<IndustryValuationPoint> findValuationHistorySince(LocalDate since) {
        var sql = """
                SELECT trading_day, industry_code, pe, pb
                FROM industry_valuation WHERE trading_day >= ? ORDER BY trading_day
                """;
        return jdbc.query(sql, (rs, i) -> new IndustryValuationPoint(
                rs.getDate("trading_day").toLocalDate(), rs.getString("industry_code"),
                rs.getBigDecimal("pe"), rs.getBigDecimal("pb")), since);
    }

    @Override
    public boolean existsIndustry(String industryCode) {
        Boolean exists = jdbc.query(
                "SELECT EXISTS (SELECT 1 FROM shenwan_industry_mapping WHERE industry_code = ?)",
                Boolean.class, industryCode);
        return Boolean.TRUE.equals(exists);
    }

    // findIndustryStocks / findIndustryProsperity 见 Task 5/6
}
```

（接口其余两方法本任务先 `throw new UnsupportedOperationException("Task 5/6 实现")` 占位编译。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew integrationTest --tests '...IndustryRepositoryImplTest' -q`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/infrastructure/persistence/IndustryRepositoryImpl.java \
        backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/IndustryRepositoryImplTest.java
git commit -m "feat(industry): 仓储估值行/历史序列/存在性查询（MS-09）"
```

---

### Task 5: IndustryRepositoryImpl——行业成员排名 SQL（TDD 集成）

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/IndustryRepositoryImpl.java`（补 `findIndustryStocks`）
- Test: `IndustryRepositoryImplTest`（追加用例）

**Interfaces:**
- Consumes: Task 3 `IndustryStock`；Task 1 `Prosperity.of`；P1 的 `stock_financial.revenue` 列；
- Produces: `findIndustryStocks(code, sortBy, direction, limit)`——`sortBy ∈ SORT_COLUMNS` 白名单（`total_mv/revenue/roe`），direction 由调用方校验后直拼 `ASC/DESC`，`NULLS LAST`。

- [ ] **Step 1: 写失败测试（追加到 IndustryRepositoryImplTest）**

```java
    @Test
    @Sql(statements = {
        "INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name, pe_ttm, pb, dividend_yield, total_mv)"
        " VALUES ('2026-01-02','601398','工商银行',6.0,0.6,5.0,200000000000),"
        "        ('2026-01-02','600519','贵州茅台',25.0,8.0,3.0,180000000000),"
        "        ('2026-01-01','601398','工商银行',6.1,0.61,5.0,190000000000)", // 非最新日，应被过滤
        "INSERT INTO stock_financial (report_date, stock_code, roe, revenue) VALUES"
        " ('2025-12-31','601398',11.0,400000000000),('2025-09-30','601398',10.0,300000000000),"
        " ('2025-12-31','600519',30.0,170000000000)",
        "INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name) VALUES"
        " ('601398','工商银行','801780','银行'),('600519','贵州茅台','801780','银行')"
    }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void finds_industry_stocks_with_latest_financials_and_prosperity() {
        var stocks = repository.findIndustryStocks("801780", "total_mv", "DESC", 1000);
        assertThat(stocks).hasSize(2);
        assertThat(stocks.get(0).stockCode()).isEqualTo("601398");            // 市值降序
        assertThat(stocks.get(0).revenue()).isEqualByComparingTo("400000000000"); // DISTINCT ON 最新报告期
        assertThat(stocks.get(0).revenueReportDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        // 601398 仅 2 季 ROE（不足 8 季→roe_delta null→不标注）；600519 仅 1 季，同样 null
        assertThat(stocks).allSatisfy(s -> assertThat(s.prosperity()).isNull());
    }
```

（`@Sql` 数组末项记得逗号分隔；语句须以分号结尾或数组分项——照 `@Sql` 既有用法。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew integrationTest --tests '...IndustryRepositoryImplTest' -q`
Expected: FAIL（方法仍抛 UnsupportedOperationException）。

- [ ] **Step 3: 实现**

```java
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "total_mv", "d.total_mv",
            "revenue", "f.revenue",
            "roe", "f.roe");

    @Override
    public List<IndustryStock> findIndustryStocks(String industryCode, String sortBy, String direction, int limit) {
        var sql = """
                WITH per_stock AS (
                    SELECT stock_code,
                           AVG(roe) FILTER (WHERE rn <= 4) AS roe_recent,
                           AVG(roe) FILTER (WHERE rn BETWEEN 5 AND 8) AS roe_prior,
                           MAX(revenue_yoy) FILTER (WHERE rn = 1) AS revenue_yoy
                    FROM (SELECT stock_code, roe, revenue_yoy,
                                 ROW_NUMBER() OVER (PARTITION BY stock_code ORDER BY report_date DESC) AS rn
                          FROM stock_financial) t
                    GROUP BY stock_code
                )
                SELECT d.stock_code, d.stock_name, d.total_mv, d.pe_ttm, d.pb, d.dividend_yield,
                       f.roe, f.revenue, f.report_date AS revenue_report_date,
                       (p.roe_recent - p.roe_prior) AS roe_delta, p.revenue_yoy
                FROM stock_valuation_daily d
                LEFT JOIN (
                    SELECT DISTINCT ON (stock_code) stock_code, roe, revenue, report_date
                    FROM stock_financial ORDER BY stock_code, report_date DESC
                ) f ON d.stock_code = f.stock_code
                LEFT JOIN per_stock p ON d.stock_code = p.stock_code
                JOIN shenwan_industry_mapping m ON d.stock_code = m.stock_code
                WHERE d.trading_day = (SELECT max(trading_day) FROM stock_valuation_daily)
                  AND m.industry_code = ?
                ORDER BY """ + SORT_COLUMNS.get(sortBy) + " " + direction + " NULLS LAST LIMIT ?";
        return jdbc.query(sql, (rs, i) -> new IndustryStock(
                rs.getString("stock_code"), rs.getString("stock_name"),
                rs.getBigDecimal("total_mv"), rs.getBigDecimal("revenue"),
                rs.getDate("revenue_report_date") == null ? null : rs.getDate("revenue_report_date").toLocalDate(),
                rs.getBigDecimal("roe"), rs.getBigDecimal("pe_ttm"), rs.getBigDecimal("pb"),
                rs.getBigDecimal("dividend_yield"),
                Prosperity.of(rs.getBigDecimal("roe_delta"), rs.getBigDecimal("revenue_yoy"))),
                industryCode, limit);
    }
```

（`sortBy`/`direction` 的合法性校验在应用层 Task 7——SQL 侧 `SORT_COLUMNS.get` 非白名单键返回 null 会拼出非法 SQL，属防御性故障而非注入面：入参已被应用层白名单拦截。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew integrationTest --tests '...IndustryRepositoryImplTest' -q`
Expected: 全 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/infrastructure/persistence/IndustryRepositoryImpl.java \
        backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/IndustryRepositoryImplTest.java
git commit -m "feat(industry): 行业成员排名查询（营收列+最新报告期+个股景气，MS-09 F03）"
```

---

### Task 6: IndustryRepositoryImpl——行业景气聚合（TDD 集成）

**Files:**
- Modify: `IndustryRepositoryImpl.java`（补 `findIndustryProsperity`）
- Test: `IndustryRepositoryImplTest`（追加用例）

**Interfaces:**
- Produces: `findIndustryProsperity()` → 31 行业 `IndustryProsperitySnapshot`（ROEΔ 中位数 / 营收增速中位数 / 样本数；样本 0 时该行业不出现在结果里，应用层视为 null 景气）。

- [ ] **Step 1: 写失败测试（追加）**

```java
    @Test
    @Sql(statements = {
        // 两只成员股：A 的 ROEΔ=+2、revenue_yoy=15；B 的 ROEΔ=0、revenue_yoy=5 → 中位数 {1.0, 10.0}，样本 2
        "INSERT INTO stock_financial (report_date, stock_code, roe, revenue_yoy) VALUES"
        " ('2025-06-30','000001',12.0,15.0),('2025-03-31','000001',10.0,15.0),"   // rn1..2：recent=12,prior=10 → Δ=2
        " ('2024-12-31','000001',10.0,15.0),('2024-09-30','000001',10.0,15.0),"   // rn3..4：凑足 8 季（prior 含）
        " ('2024-06-30','000001',10.0,15.0),('2024-03-31','000001',10.0,15.0),"
        " ('2023-12-31','000001',10.0,15.0),('2023-09-30','000001',10.0,15.0),"
        " ('2025-06-30','000002',6.0,5.0),('2025-03-31','000002',6.0,5.0),"
        " ('2024-12-31','000002',6.0,5.0),('2024-09-30','000002',6.0,5.0),"
        " ('2024-06-30','000002',6.0,5.0),('2024-03-31','000002',6.0,5.0),"
        " ('2023-12-31','000002',6.0,5.0),('2023-09-30','000002',6.0,5.0)",
        "INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name) VALUES"
        " ('000001','平安银行','801780','银行'),('000002','万科A','801780','银行')"
    }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void aggregates_industry_prosperity_medians() {
        var snaps = repository.findIndustryProsperity();
        var bank = snaps.stream().filter(s -> s.industryCode().equals("801780")).findFirst().orElseThrow();
        assertThat(bank.roeDeltaMedian()).isEqualByComparingTo("1"); // median(2,0)=1
        assertThat(bank.revenueYoyMedian()).isEqualByComparingTo("10"); // median(15,5)=10
        assertThat(bank.sampleSize()).isEqualTo(2);
    }
```

（A 股 `roe` 逐季同值时 recent/prior 均值即该值——Δ=2 来自 12−10。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew integrationTest --tests '...IndustryRepositoryImplTest' -q`
Expected: FAIL（UnsupportedOperationException）。

- [ ] **Step 3: 实现（与 Task 5 共用 per_stock CTE 常量）**

```java
    private static final String PER_STOCK_CTE = """
            WITH per_stock AS (
                SELECT stock_code,
                       AVG(roe) FILTER (WHERE rn <= 4) AS roe_recent,
                       AVG(roe) FILTER (WHERE rn BETWEEN 5 AND 8) AS roe_prior,
                       MAX(revenue_yoy) FILTER (WHERE rn = 1) AS revenue_yoy
                FROM (SELECT stock_code, roe, revenue_yoy,
                             ROW_NUMBER() OVER (PARTITION BY stock_code ORDER BY report_date DESC) AS rn
                      FROM stock_financial) t
                GROUP BY stock_code
            )
            """;

    @Override
    public List<IndustryProsperitySnapshot> findIndustryProsperity() {
        var sql = PER_STOCK_CTE + """
                SELECT m.industry_code,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY (p.roe_recent - p.roe_prior)) AS roe_delta_median,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY p.revenue_yoy) AS revenue_yoy_median,
                       COUNT(p.stock_code) FILTER (WHERE p.roe_recent IS NOT NULL AND p.roe_prior IS NOT NULL) AS sample_size
                FROM per_stock p JOIN shenwan_industry_mapping m ON m.stock_code = p.stock_code
                GROUP BY m.industry_code
                """;
        return jdbc.query(sql, (rs, i) -> new IndustryProsperitySnapshot(
                rs.getString("industry_code"), rs.getBigDecimal("roe_delta_median"),
                rs.getBigDecimal("revenue_yoy_median"), rs.getLong("sample_size")));
    }
```

（Task 5 的 `findIndustryStocks` SQL 把内联 CTE 换成 `PER_STOCK_CTE +` 拼接，消除重复——改完重跑 Task 5 用例确认不回归。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew integrationTest --tests '...IndustryRepositoryImplTest' -q`
Expected: 全 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/infrastructure/persistence/IndustryRepositoryImpl.java \
        backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/IndustryRepositoryImplTest.java
git commit -m "feat(industry): 行业景气中位数聚合（MS-09 F05 行业级）"
```

---

### Task 7: IndustryApplicationService + View（TDD）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/industry/IndustryApplicationService.java`、`IndustryBoardView.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/industry/IndustryApplicationServiceTest.java`

**Interfaces:**
- Consumes: Task 1/2 纯函数、Task 3 端口、`ApplicationCache`（`application/cache/ApplicationCache.java`）、`InvestProperties.AppCache`；
- Produces（Task 8 契约）:

```java
public record IndustryBoardView(String industryCode, String industryName,
        BigDecimal pe, BigDecimal pb, BigDecimal roe, BigDecimal dividendYield,
        BigDecimal pePercentile, BigDecimal pbPercentile,
        Prosperity prosperity, ProsperityInputs prosperityInputs) {

    public record ProsperityInputs(BigDecimal roeDeltaMedian, BigDecimal revenueYoyMedian, long sampleSize) {}
}

// IndustryApplicationService 公开方法：
List<IndustryBoardView> board();                                        // 缓存 key: industry:board:{当日}
List<IndustryStock> stocks(String industryCode, String sortBy, String sortDirection, int limit);
// stocks 校验：sortBy ∈ {total_mv, revenue, roe} 否则 INVALID_SORT；
// sortDirection ∈ {ASC, DESC} 否则 INVALID_SORT；limit ∈ [1,1000] 否则 INVALID_LIMIT；
// existsIndustry 否则 INDUSTRY_NOT_FOUND；缓存 key: industry:{code}:stocks:{sortBy}|{dir}|{limit}
```

- [ ] **Step 1: 写失败测试（ Mockito 风格照 `ScreeningApplicationServiceTest` 既有用例）**

```java
package com.portfolio.invest.application.industry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryProsperitySnapshot;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.IndustryValuationPoint;
import com.portfolio.invest.domain.industry.IndustryValuationRow;
import com.portfolio.invest.domain.industry.Prosperity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndustryApplicationServiceTest {

    private final IndustryRepository repository = mock(IndustryRepository.class);
    private final ApplicationCache cache = mock(ApplicationCache.class);
    private final IndustryApplicationService service =
            new IndustryApplicationService(repository, cache); // 测试用便捷构造（TTL 默认 5min）

    @Test
    void board_composes_percentile_and_prosperity() {
        when(repository.findLatestIndustries()).thenReturn(List.of(
                new IndustryValuationRow("801780", "银行", new BigDecimal("5.5"), new BigDecimal("0.8"),
                        new BigDecimal("12"), new BigDecimal("4"))));
        List<IndustryValuationPoint> history = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            history.add(new IndustryValuationPoint(LocalDate.of(2025, 1, 1).plusDays(i), "801780",
                    i < 100 ? new BigDecimal("5") : new BigDecimal("9"), new BigDecimal("0.8")));
        }
        when(repository.findValuationHistorySince(any())).thenReturn(history);
        when(repository.findIndustryProsperity()).thenReturn(List.of(
                new IndustryProsperitySnapshot("801780", new BigDecimal("1"), new BigDecimal("10"), 42)));

        var board = service.board();
        var row = board.get(0);
        assertThat(row.pePercentile()).isEqualByComparingTo("40.00"); // 100/250 严格小于 5.5
        assertThat(row.prosperity()).isEqualTo(Prosperity.UP);        // Δ=1 且 增速=10 → UP
        assertThat(row.prosperityInputs().sampleSize()).isEqualTo(42);
    }

    @Test
    void stocks_validates_and_maps_prosperity() {
        when(repository.existsIndustry("801780")).thenReturn(true);
        when(repository.findIndustryStocks("801780", "total_mv", "DESC", 1000)).thenReturn(List.of(
                new IndustryStock("601398", "工商银行", null, null, null, null, null, null, null, null)));
        assertThat(service.stocks("801780", "total_mv", "DESC", 1000)).hasSize(1);

        when(repository.existsIndustry("999999")).thenReturn(false);
        assertThatThrownBy(() -> service.stocks("999999", "total_mv", "DESC", 1000))
                .isInstanceOf(IndustryException.class)
                .extracting(e -> ((IndustryException) e).code()).isEqualTo(IndustryErrorCode.INDUSTRY_NOT_FOUND);
        assertThatThrownBy(() -> service.stocks("801780", "pe_ttm", "DESC", 1000))
                .isInstanceOf(IndustryException.class)
                .extracting(e -> ((IndustryException) e).code()).isEqualTo(IndustryErrorCode.INVALID_SORT);
        assertThatThrownBy(() -> service.stocks("801780", "total_mv", "DESC", 1001))
                .isInstanceOf(IndustryException.class)
                .extracting(e -> ((IndustryException) e).code()).isEqualTo(IndustryErrorCode.INVALID_LIMIT);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests 'com.portfolio.invest.application.industry.IndustryApplicationServiceTest' -q`
Expected: 编译 FAIL。

- [ ] **Step 3: 实现**

```java
package com.portfolio.invest.application.industry;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryProsperitySnapshot;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.IndustryValuationPoint;
import com.portfolio.invest.domain.industry.IndustryValuationRow;
import com.portfolio.invest.domain.industry.Prosperity;
import com.portfolio.invest.domain.industry.WindowedPercentile;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class IndustryApplicationService {

    static final Set<String> SORTABLE = Set.of("total_mv", "revenue", "roe");
    static final int DEFAULT_LIMIT = 1000;

    private final IndustryRepository repository;
    private final ApplicationCache cache;
    private final Duration cacheTtl;

    @Autowired
    public IndustryApplicationService(IndustryRepository repository, ApplicationCache cache, InvestProperties props) {
        this(repository, cache, props.getAppCache().getTtl());
    }

    public IndustryApplicationService(IndustryRepository repository, ApplicationCache cache) {
        this(repository, cache, Duration.ofMinutes(5));
    }

    IndustryApplicationService(IndustryRepository repository, ApplicationCache cache, Duration cacheTtl) {
        this.repository = repository;
        this.cache = cache;
        this.cacheTtl = cacheTtl;
    }

    public List<IndustryBoardView> board() {
        return cached("board:" + LocalDate.now(), () -> {
            var latest = repository.findLatestIndustries();
            var history = repository.findValuationHistorySince(LocalDate.now().minusYears(5));
            Map<String, List<BigDecimal>> peBy = history.stream().collect(Collectors.groupingBy(
                    IndustryValuationPoint::industryCode, Collectors.mapping(p -> p.pe(), Collectors.toList())));
            Map<String, List<BigDecimal>> pbBy = history.stream().collect(Collectors.groupingBy(
                    IndustryValuationPoint::industryCode, Collectors.mapping(p -> p.pb(), Collectors.toList())));
            Map<String, IndustryProsperitySnapshot> prosperBy = repository.findIndustryProsperity().stream()
                    .collect(Collectors.toMap(IndustryProsperitySnapshot::industryCode, Function.identity()));
            return latest.stream().map(row -> {
                var snap = prosperBy.get(row.industryCode());
                return new IndustryBoardView(row.industryCode(), row.industryName(),
                        row.pe(), row.pb(), row.roe(), row.dividendYield(),
                        WindowedPercentile.of(row.pe(), peBy.get(row.industryCode())),
                        WindowedPercentile.of(row.pb(), pbBy.get(row.industryCode())),
                        snap == null ? null : Prosperity.of(snap.roeDeltaMedian(), snap.revenueYoyMedian()),
                        snap == null ? null : new IndustryBoardView.ProsperityInputs(
                                snap.roeDeltaMedian(), snap.revenueYoyMedian(), snap.sampleSize()));
            }).toList();
        });
    }

    public List<IndustryStock> stocks(String industryCode, String sortBy, String sortDirection, int limit) {
        if (!SORTABLE.contains(sortBy) || !"ASC".equals(sortDirection) && !"DESC".equals(sortDirection)) {
            throw new IndustryException(IndustryErrorCode.INVALID_SORT, "排序参数非法: " + sortBy + " " + sortDirection);
        }
        int bounded = Math.max(1, Math.min(limit, DEFAULT_LIMIT));
        if (bounded != limit) {
            throw new IndustryException(IndustryErrorCode.INVALID_LIMIT, "limit 须在 1~" + DEFAULT_LIMIT + " 之间");
        }
        if (!repository.existsIndustry(industryCode)) {
            throw new IndustryException(IndustryErrorCode.INDUSTRY_NOT_FOUND, "行业不存在: " + industryCode);
        }
        return cached("stocks:" + industryCode + ":" + sortBy + "|" + sortDirection + "|" + limit,
                () -> repository.findIndustryStocks(industryCode, sortBy, sortDirection, limit));
    }

    private <T> T cached(String kind, java.util.function.Supplier<T> loader) {
        String key = "industry:" + kind;
        T hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        T value = loader.get();
        cache.put(key, value, cacheTtl);
        return value;
    }
}
```

（`InvestProperties` 包路径以 `ScreeningApplicationService.java:26-28` 的真实 import 为准照抄。`IndustryBoardView` record 按 Task 7 Interfaces 原文落文件。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests 'com.portfolio.invest.application.industry.IndustryApplicationServiceTest' -q`
Expected: 2 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/application/industry/ backend/src/test/java/com/portfolio/invest/application/industry/
git commit -m "feat(industry): 应用服务 board/stocks 组装与缓存（MS-09）"
```

---

### Task 8: Controller + 公开登记 + 全局异常（TDD）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/web/IndustryController.java`
- Modify: `backend/src/main/java/com/portfolio/invest/infrastructure/security/PublicEndpointPaths.java:20-25`、`backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java`（追加 handler）
- Test: `backend/src/test/java/com/portfolio/invest/web/IndustryControllerTest.java`

**Interfaces:**
- Consumes: Task 7 服务方法；
- Produces: `GET /api/industry/board`、`GET /api/industry/{industryCode}/stocks?sortBy=&sortDirection=&limit=`（公开只读 JSON）——P3 前端消费契约。

- [ ] **Step 1: 写失败测试（standalone MockMvc，风格照 `ValuationControllerTest`/`ScreeningControllerTest`）**

```java
package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.Prosperity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IndustryControllerTest {

    private final IndustryApplicationService service = mock(IndustryApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new IndustryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void board_returns_views() throws Exception {
        when(service.board()).thenReturn(List.of(new com.portfolio.invest.application.industry.IndustryBoardView(
                "801780", "银行", new BigDecimal("5.5"), null, null, null, null, null, Prosperity.UP, null)));
        mvc.perform(get("/api/industry/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].industryCode").value("801780"))
                .andExpect(jsonPath("$[0].prosperity").value("UP"));
    }

    @Test
    void stocks_maps_not_found_to_404() throws Exception {
        when(service.stocks(eq("999999"), any(), any(), eq(1000)))
                .thenThrow(new IndustryException("INDUSTRY_NOT_FOUND", "行业不存在"));
        mvc.perform(get("/api/industry/999999/stocks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INDUSTRY_NOT_FOUND"));
    }
}
```

（若 `GlobalExceptionHandler` 构造有依赖（如 ObjectMapper），照 `ScreeningControllerTest` 的 advice 接法；无则如上。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests 'com.portfolio.invest.web.IndustryControllerTest' -q`
Expected: 编译 FAIL。

- [ ] **Step 3: 实现 Controller + 两处登记**

```java
package com.portfolio.invest.web;

import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.domain.industry.IndustryStock;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 行业研究中心 REST 接口（/industry board 与下钻页消费；无需登录）。 */
@RestController
@RequestMapping("/api/industry")
public class IndustryController {

    private final IndustryApplicationService industryApplicationService;

    public IndustryController(IndustryApplicationService industryApplicationService) {
        this.industryApplicationService = industryApplicationService;
    }

    @GetMapping("/board")
    public List<com.portfolio.invest.application.industry.IndustryBoardView> board() {
        return industryApplicationService.board();
    }

    /** 行业成员排名（市值/营收/ROE；A1 取舍：IndustryStock 读模型即响应契约）。 */
    @GetMapping("/{industryCode}/stocks")
    public List<IndustryStock> stocks(@PathVariable String industryCode,
            @RequestParam(defaultValue = "total_mv") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestParam(defaultValue = "1000") int limit) {
        return industryApplicationService.stocks(industryCode, sortBy, sortDirection, limit);
    }
}
```

`PublicEndpointPaths.java`（PREFIXES 数组加一行）：

```java
    public static final String[] PREFIXES = {
            "/api/market/",
            "/api/valuation/",
            "/api/screening/",
            "/api/industry/",
            "/actuator/",
    };
```

`GlobalExceptionHandler`（照 `GlobalExceptionHandler.java:109-124` screening handler 的全限定名内联 switch 模式，追加方法）：

```java
    @ExceptionHandler(com.portfolio.invest.domain.industry.IndustryException.class)
    public ResponseEntity<ApiError> industry(com.portfolio.invest.domain.industry.IndustryException e) {
        HttpStatus status = switch (e.code()) {
            case com.portfolio.invest.domain.industry.IndustryErrorCode.INDUSTRY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case com.portfolio.invest.domain.industry.IndustryErrorCode.INVALID_SORT,
                 com.portfolio.invest.domain.industry.IndustryErrorCode.INVALID_LIMIT -> HttpStatus.BAD_REQUEST;
            default -> { log.warn("未识别的行业研究错误码 {}，按 400 处理", e.code()); yield HttpStatus.BAD_REQUEST; }
        };
        return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage()));
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests 'com.portfolio.invest.web.IndustryControllerTest' -q`
Expected: 2 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/web/IndustryController.java \
        backend/src/main/java/com/portfolio/invest/infrastructure/security/PublicEndpointPaths.java \
        backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java \
        backend/src/test/java/com/portfolio/invest/web/IndustryControllerTest.java
git commit -m "feat(industry): /api/industry 两端点 + 公开登记 + 错误映射（MS-09）"
```

---

### Task 9: BDD 场景（行业成员排名）

**Files:**
- Create: `backend/src/bdd/resources/features/industry.feature`
- Create: `backend/src/bdd/java/com/portfolio/invest/bdd/steps/IndustrySteps.java`

**Interfaces:**
- Consumes: Task 7 `IndustryApplicationService`（BDD 直接调应用服务，模式照 `ScreeningSteps.java:29-77`）。

- [ ] **Step 1: 写 feature（zh-CN）**

```gherkin
# language: zh-CN
功能: 行业研究中心·上市公司排名

  场景: 按总市值降序列出行业成员
    假如 银行业中有工商银行与贵州茅台且工商银行市值更大
    当 用户查看银行业成员排名
    那么 排名第一的应是 "601398"

  场景: 不存在的行业返回未找到
    假如 申万行业映射中不存在行业 999999
    当 用户查看行业 999999 成员排名
    那么 系统应提示行业不存在
```

- [ ] **Step 2: 写 Steps（照 ScreeningSteps 的 注解/jdbcTemplate 种子/ScenarioContext 模式）**

```java
package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.domain.industry.IndustryException;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class IndustrySteps {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IndustryApplicationService industryApplicationService;
    private List<com.portfolio.invest.domain.industry.IndustryStock> results;
    private IndustryException error;

    @假如("银行业中有工商银行与贵州茅台且工商银行市值更大")
    @Transactional
    public void 种子行业成员() {
        jdbcTemplate.update("INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name, pe_ttm, pb, total_mv)"
                + " VALUES (CURRENT_DATE, '601398', '工商银行', 6, 0.6, 200000000000)"
                + ", (CURRENT_DATE, '600519', '贵州茅台', 25, 8, 180000000000)");
        jdbcTemplate.update("INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name)"
                + " VALUES ('601398', '工商银行', '801780', '银行'), ('600519', '贵州茅台', '801780', '银行')");
    }

    @当("用户查看银行业成员排名")
    public void 查看成员() {
        results = industryApplicationService.stocks("801780", "total_mv", "DESC", 1000);
    }

    @那么("排名第一的应是 {string}")
    public void 断言首位(String code) {
        assertThat(results.get(0).stockCode()).isEqualTo(code);
    }

    @假如("申万行业映射中不存在行业 {string}")
    public void 无该行业(String code) {
        jdbcTemplate.update("DELETE FROM shenwan_industry_mapping WHERE industry_code = ?", code);
    }

    @当("用户查看行业 {string} 成员排名")
    public void 查看不存在行业(String code) {
        try {
            industryApplicationService.stocks(code, "total_mv", "DESC", 1000);
        } catch (IndustryException e) {
            error = e;
        }
    }

    @那么("系统应提示行业不存在")
    public void 断言未找到() {
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("INDUSTRY_NOT_FOUND");
    }
}
```

（BDD 造数与 ScenarioContext 的注册方式照 `ScreeningSteps.java:29-50` 现状——若其用共享 context 存异常则跟随。BDD 的 stock_valuation_daily 最新日口径：种子用 `CURRENT_DATE` 保证 `max(trading_day)` 命中。）

- [ ] **Step 3: 跑 BDD**

Run: `cd backend && ./gradlew check -q`
Expected: 全绿（含单测/集成/BDD/覆盖率）。

- [ ] **Step 4: Commit**

```bash
git add backend/src/bdd/resources/features/industry.feature backend/src/bdd/java/com/portfolio/invest/bdd/steps/IndustrySteps.java
git commit -m "test(industry): BDD 行业成员排名场景（MS-09）"
```

---

### Task 10: P2 收尾——规范文档登记 + 全量门槛

**Files:**
- Modify: `docs/technology/conventions/01-后端DDD分包规范.md`（域清单登记 `domain/industry`）

**Interfaces:**
- Produces: P2 完成态；P3 前端可启动。

- [ ] **Step 1: 规范文档域清单登记**

在《01-后端DDD分包规范》的域清单（找到既有 `domain/screening`/`domain/valuation` 登记处）追加一行/一节：`domain/industry` —— 行业研究中心读侧（board 聚合、行业成员排名、景气聚合、窗口化分位），无聚合根、纯读模型 + 仓储端口，表 `industry_valuation`（与估值域共享，跨域读先例同 `shenwan_industry_mapping`）。

- [ ] **Step 2: 全量质量门槛**

Run: `cd /Users/lixiaoyi/GitRepository/portfolio-management && make test`
Expected: 后端 `gradlew check`（含集成/BDD/JaCoCo ≥80%）+ 前端 V8 + collector 全绿（P1 若已合入）。

- [ ] **Step 3: Commit**

```bash
git add docs/technology/conventions/01-后端DDD分包规范.md
git commit -m "docs(conventions): DDD 分包规范登记 domain/industry（MS-09）"
```
