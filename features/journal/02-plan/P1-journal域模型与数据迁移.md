# P1 journal 域模型与数据迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 `domain/journal` 纯领域层（JournalEntryType/PeriodType/JournalEntry/异常/仓库端口）与 Flyway V8 单表迁移，并通过 Testcontainers 集成测试验证持久化。

**Architecture:** 沿用 M08 持仓域 / M07 配置域的模式——domain 为纯 POJO（零 Spring/JPA 注解），infrastructure 用扁平 JPA 实体 + 仓库实现回填。四类记录统一建模为单聚合根 `JournalEntry`（`type` 枚举区分，类型特有字段可空），单表 `journal_entry` 持久化。`domain/journal` 零项目内依赖。

**Tech Stack:** Java 21 · Spring Boot 4 · JPA (Hibernate) · PostgreSQL 16 (Flyway) · JUnit 5 + AssertJ + Mockito · Testcontainers

**Spec:** `features/journal/01-requirement/需求规格说明.md`

## Global Constraints

- 后端 DDD 洋葱分层，`domain/journal` 纯 POJO，禁 Spring/JPA 注解（ArchUnit `domainHasNoSpringAnnotations` 强制）。
- `domain/journal` 零项目内依赖；`application.journal` 才允许依赖 `domain.portfolio`（P2 联动与时间线）。
- schema 由 Flyway 管理（`ddl-auto: none`），迁移文件 `V8__journal.sql`，表名 snake_case 单数。
- 金额/价格用 `BigDecimal`；`eventDate`/`periodStart`/`periodEnd` 用 `LocalDate`；`createdAt`/`updatedAt` 用 `Instant`。
- `trade_id` 为软引用（BIGINT 无外键、不级联），M08 交易删除后悬空保留。
- 领域不可变对象用静态工厂 `create` / `reconstitute` + 包级私有构造器，变更操作 `update` 返回新实例。
- 后端覆盖率门槛 ≥80%（JaCoCo 聚合三层 exec）。
- 测试四层：`test`（单元+切片）/ `integrationTest`（Testcontainers 真实 PG）/ `bdd`（Cucumber）。

---

### Task 1: 记录类型/期间枚举 + 领域异常

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/journal/JournalEntryType.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/journal/PeriodType.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/journal/JournalException.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/journal/JournalErrorCode.java`

**Interfaces:**
- Produces: `JournalEntryType`（`BUY_MEMO`/`SELL_MEMO`/`RESEARCH_NOTE`/`REVIEW`，各带 `label()`）；`PeriodType`（`QUARTERLY`/`ANNUAL`，各带 `label()`）；`JournalException(String code, String message)` + `.code()`；`JournalErrorCode` 常量。

- [ ] **Step 1: 写失败测试**

Create `backend/src/test/java/com/portfolio/invest/domain/journal/JournalEntryTypeTest.java`:

```java
package com.portfolio.invest.domain.journal;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JournalEntryTypeTest {
    @Test
    void 四种记录类型带中文标签() {
        assertThat(JournalEntryType.values()).hasSize(4);
        assertThat(JournalEntryType.BUY_MEMO.label()).isEqualTo("买入备忘");
        assertThat(JournalEntryType.SELL_MEMO.label()).isEqualTo("卖出备忘");
        assertThat(JournalEntryType.RESEARCH_NOTE.label()).isEqualTo("研究笔记");
        assertThat(JournalEntryType.REVIEW.label()).isEqualTo("定期复盘");
    }
}
```

Create `backend/src/test/java/com/portfolio/invest/domain/journal/PeriodTypeTest.java`:

```java
package com.portfolio.invest.domain.journal;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PeriodTypeTest {
    @Test
    void 两种期间类型带中文标签() {
        assertThat(PeriodType.values()).hasSize(2);
        assertThat(PeriodType.QUARTERLY.label()).isEqualTo("季度");
        assertThat(PeriodType.ANNUAL.label()).isEqualTo("年度");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.journal.JournalEntryTypeTest" --tests "com.portfolio.invest.domain.journal.PeriodTypeTest" --console=plain`
Expected: 编译失败（类型不存在）。

- [ ] **Step 3: 实现**

`JournalEntryType.java`:
```java
package com.portfolio.invest.domain.journal;

public enum JournalEntryType {
    BUY_MEMO("买入备忘"),
    SELL_MEMO("卖出备忘"),
    RESEARCH_NOTE("研究笔记"),
    REVIEW("定期复盘");

    private final String label;

    JournalEntryType(String label) { this.label = label; }

    public String label() { return label; }
}
```

`PeriodType.java`:
```java
package com.portfolio.invest.domain.journal;

public enum PeriodType {
    QUARTERLY("季度"),
    ANNUAL("年度");

    private final String label;

    PeriodType(String label) { this.label = label; }

    public String label() { return label; }
}
```

`JournalException.java`:
```java
package com.portfolio.invest.domain.journal;

public class JournalException extends RuntimeException {
    private final String code;

    public JournalException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
```

`JournalErrorCode.java`:
```java
package com.portfolio.invest.domain.journal;

public final class JournalErrorCode {
    private JournalErrorCode() {}

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String TRADE_NOT_FOUND = "TRADE_NOT_FOUND";
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/journal/JournalEntryType.java \
        backend/src/main/java/com/portfolio/invest/domain/journal/PeriodType.java \
        backend/src/main/java/com/portfolio/invest/domain/journal/JournalException.java \
        backend/src/main/java/com/portfolio/invest/domain/journal/JournalErrorCode.java \
        backend/src/test/java/com/portfolio/invest/domain/journal/JournalEntryTypeTest.java \
        backend/src/test/java/com/portfolio/invest/domain/journal/PeriodTypeTest.java
git commit -m "feat(journal): 定义记录类型/期间枚举与领域异常"
```

---

### Task 2: 记录聚合根

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/journal/JournalEntry.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/journal/JournalEntryTest.java`

**Interfaces:**
- Produces: `JournalEntry` 聚合根，静态工厂 `create(...)` 与 `reconstitute(...)`；实例方法 `update(...)` 返回新实例；`static validate(...)` 做类型相关校验，违例抛 `JournalException`。访问器 `id()/userId()/type()/stockCode()/stockName()/tradeId()/title()/content()/targetPrice()/stopLoss()/periodType()/periodStart()/periodEnd()/eventDate()/createdAt()/updatedAt()`。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.domain.journal;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");

    private static JournalEntry buyMemo() {
        return JournalEntry.create(1L, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", 10L,
                "买入茅台", "理由：长期持有", new BigDecimal("1800"), new BigDecimal("1500"),
                null, null, null, LocalDate.of(2026, 9, 2), NOW);
    }

    @Test
    void 创建买入备忘带目标价止损价() {
        var e = buyMemo();
        assertThat(e.type()).isEqualTo(JournalEntryType.BUY_MEMO);
        assertThat(e.stockCode()).isEqualTo("600519");
        assertThat(e.targetPrice()).isEqualByComparingTo("1800");
        assertThat(e.stopLoss()).isEqualByComparingTo("1500");
        assertThat(e.eventDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void 标题为空抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.RESEARCH_NOTE, null, null, null,
                "  ", "内容", null, null, null, null, null, LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 买入备忘缺股票代码抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.BUY_MEMO, null, null, null,
                "标题", "内容", null, null, null, null, null, LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 目标价为负抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", null,
                "标题", "内容", new BigDecimal("-1"), null, null, null, null, LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 复盘缺期间字段抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.REVIEW, null, null, null,
                "复盘", "内容", null, null, null, null, null, LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 复盘起始日晚于结束日抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.REVIEW, null, null, null,
                "复盘", "内容", null, null, PeriodType.QUARTERLY,
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 7, 1), LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 研究笔记可不关联股票() {
        var e = JournalEntry.create(1L, JournalEntryType.RESEARCH_NOTE, null, null, null,
                "白酒行业研究", "Markdown 内容", null, null, null, null, null, LocalDate.now(), NOW);
        assertThat(e.stockCode()).isNull();
    }

    @Test
    void 更新返回新实例且原实例不变() {
        var e = buyMemo();
        var updated = e.update("600519", "贵州茅台", 11L, "新标题", "新内容",
                new BigDecimal("2000"), new BigDecimal("1600"), null, null, null,
                LocalDate.of(2026, 9, 3));
        assertThat(updated.title()).isEqualTo("新标题");
        assertThat(updated.tradeId()).isEqualTo(11L);
        assertThat(e.title()).isEqualTo("买入茅台"); // 原实例不变
        assertThat(e.tradeId()).isEqualTo(10L);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.journal.JournalEntryTest" --console=plain`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package com.portfolio.invest.domain.journal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 投资决策记录聚合根：不可变，变更操作 update 返回新实例。四类记录统一建模，类型特有字段可空。 */
public final class JournalEntry {

    private final Long id;
    private final Long userId;
    private final JournalEntryType type;
    private final String stockCode;
    private final String stockName;
    private final Long tradeId;
    private final String title;
    private final String content;
    private final BigDecimal targetPrice;
    private final BigDecimal stopLoss;
    private final PeriodType periodType;
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final LocalDate eventDate;
    private final Instant createdAt;
    private final Instant updatedAt;

    private JournalEntry(Long id, Long userId, JournalEntryType type, String stockCode, String stockName,
                         Long tradeId, String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
                         PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                         LocalDate eventDate, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.tradeId = tradeId;
        this.title = title;
        this.content = content;
        this.targetPrice = targetPrice;
        this.stopLoss = stopLoss;
        this.periodType = periodType;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.eventDate = eventDate;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static JournalEntry create(Long userId, JournalEntryType type, String stockCode, String stockName,
                                      Long tradeId, String title, String content,
                                      BigDecimal targetPrice, BigDecimal stopLoss,
                                      PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                      LocalDate eventDate, Instant now) {
        validate(type, stockCode, title, content, targetPrice, stopLoss,
                periodType, periodStart, periodEnd, eventDate);
        return new JournalEntry(null, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, now, now);
    }

    public static JournalEntry reconstitute(Long id, Long userId, JournalEntryType type,
                                            String stockCode, String stockName, Long tradeId,
                                            String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
                                            PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                            LocalDate eventDate, Instant createdAt, Instant updatedAt) {
        return new JournalEntry(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, updatedAt);
    }

    /** 更新可变字段（type 不可变），返回新实例。 */
    public JournalEntry update(String stockCode, String stockName, Long tradeId, String title, String content,
                               BigDecimal targetPrice, BigDecimal stopLoss,
                               PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                               LocalDate eventDate) {
        validate(type, stockCode, title, content, targetPrice, stopLoss,
                periodType, periodStart, periodEnd, eventDate);
        return new JournalEntry(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, Instant.now());
    }

    private static void validate(JournalEntryType type, String stockCode, String title, String content,
                                 BigDecimal targetPrice, BigDecimal stopLoss,
                                 PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                 LocalDate eventDate) {
        if (title == null || title.isBlank()) {
            throw new JournalException(JournalErrorCode.INVALID_INPUT, "标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new JournalException(JournalErrorCode.INVALID_INPUT, "内容不能为空");
        }
        if (eventDate == null) {
            throw new JournalException(JournalErrorCode.INVALID_INPUT, "事件日期不能为空");
        }
        switch (type) {
            case BUY_MEMO, SELL_MEMO -> {
                if (stockCode == null || stockCode.isBlank()) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "股票代码不能为空");
                }
                if (targetPrice != null && targetPrice.signum() <= 0) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "目标价必须为正");
                }
                if (stopLoss != null && stopLoss.signum() <= 0) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "止损价必须为正");
                }
            }
            case REVIEW -> {
                if (periodType == null || periodStart == null || periodEnd == null) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "复盘期间必填");
                }
                if (periodStart.isAfter(periodEnd)) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "复盘起始日不能晚于结束日");
                }
            }
            case RESEARCH_NOTE -> { /* stockCode 可选 */ }
        }
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public JournalEntryType type() { return type; }
    public String stockCode() { return stockCode; }
    public String stockName() { return stockName; }
    public Long tradeId() { return tradeId; }
    public String title() { return title; }
    public String content() { return content; }
    public BigDecimal targetPrice() { return targetPrice; }
    public BigDecimal stopLoss() { return stopLoss; }
    public PeriodType periodType() { return periodType; }
    public LocalDate periodStart() { return periodStart; }
    public LocalDate periodEnd() { return periodEnd; }
    public LocalDate eventDate() { return eventDate; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/journal/JournalEntry.java \
        backend/src/test/java/com/portfolio/invest/domain/journal/JournalEntryTest.java
git commit -m "feat(journal): 记录聚合根（类型校验/更新/不可变）"
```

---

### Task 3: 仓库端口 + Flyway V8 迁移

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/journal/JournalEntryRepository.java`
- Create: `backend/src/main/resources/db/migration/V8__journal.sql`

**Interfaces:**
- Produces: 仓库端口 `JournalEntryRepository`（方法见下）。迁移建 `journal_entry` 单表。

- [ ] **Step 1: 写仓库端口**

```java
package com.portfolio.invest.domain.journal;

import java.util.List;
import java.util.Optional;

/** 记录仓库端口：归属过滤（userId）在用例层双重保障。 */
public interface JournalEntryRepository {
    List<JournalEntry> findByUserId(Long userId, JournalEntryType type);
    Optional<JournalEntry> findByIdAndUserId(Long id, Long userId);
    JournalEntry save(JournalEntry entry);
    void deleteById(Long id);
}
```

- [ ] **Step 2: 写迁移**

```sql
-- 投资决策记录：买入/卖出备忘、研究笔记、定期复盘（单表，类型特有字段可空）
-- trade_id 为 M08 交易的软引用（无外键、不级联），交易删除后悬空保留。

CREATE TABLE journal_entry (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES app_user(id),
    type         VARCHAR(24) NOT NULL,
    stock_code   VARCHAR(16),
    stock_name   VARCHAR(64),
    trade_id     BIGINT,
    title        VARCHAR(128) NOT NULL,
    content      TEXT NOT NULL,
    target_price NUMERIC(18,4),
    stop_loss    NUMERIC(18,4),
    period_type  VARCHAR(16),
    period_start DATE,
    period_end   DATE,
    event_date   DATE NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_journal_entry_user ON journal_entry(user_id, event_date DESC);
```

- [ ] **Step 3: Commit（仓库端口 + 迁移无需独立单测，迁移由 Task 5 集成测试验证）**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/journal/JournalEntryRepository.java \
        backend/src/main/resources/db/migration/V8__journal.sql
git commit -m "feat(journal): 记录仓库端口与 Flyway V8 迁移"
```

---

### Task 4: JPA 实体与仓库实现

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/JournalEntryJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/JournalEntryJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/JournalEntryRepositoryImpl.java`

**Interfaces:**
- Consumes: `JournalEntryRepository`（Task 3）、`JournalEntry`（Task 2）。
- Produces: `JournalEntryRepositoryImpl implements JournalEntryRepository`（Spring `@Repository`）。

- [ ] **Step 1: 写 JPA 实体**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.PeriodType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "journal_entry")
public class JournalEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private JournalEntryType type;

    @Column(name = "stock_code", length = 16)
    private String stockCode;

    @Column(name = "stock_name", length = 64)
    private String stockName;

    @Column(name = "trade_id")
    private Long tradeId;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "target_price")
    private BigDecimal targetPrice;

    @Column(name = "stop_loss")
    private BigDecimal stopLoss;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", length = 16)
    private PeriodType periodType;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JournalEntryJpaEntity() {}

    public static JournalEntryJpaEntity fromDomain(JournalEntry e) {
        JournalEntryJpaEntity entity = new JournalEntryJpaEntity();
        entity.id = e.id();
        entity.userId = e.userId();
        entity.type = e.type();
        entity.stockCode = e.stockCode();
        entity.stockName = e.stockName();
        entity.tradeId = e.tradeId();
        entity.title = e.title();
        entity.content = e.content();
        entity.targetPrice = e.targetPrice();
        entity.stopLoss = e.stopLoss();
        entity.periodType = e.periodType();
        entity.periodStart = e.periodStart();
        entity.periodEnd = e.periodEnd();
        entity.eventDate = e.eventDate();
        entity.createdAt = e.createdAt();
        entity.updatedAt = e.updatedAt();
        return entity;
    }

    public JournalEntry toDomain() {
        return JournalEntry.reconstitute(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, updatedAt);
    }
}
```

- [ ] **Step 2: 写 JpaRepository**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.journal.JournalEntryType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryJpaRepository extends JpaRepository<JournalEntryJpaEntity, Long> {
    List<JournalEntryJpaEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    List<JournalEntryJpaEntity> findByUserIdAndTypeOrderByUpdatedAtDesc(Long userId, JournalEntryType type);
    Optional<JournalEntryJpaEntity> findByIdAndUserId(Long id, Long userId);
}
```

- [ ] **Step 3: 写仓库实现**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional。
@Repository
public class JournalEntryRepositoryImpl implements JournalEntryRepository {

    private final JournalEntryJpaRepository jpa;

    public JournalEntryRepositoryImpl(JournalEntryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<JournalEntry> findByUserId(Long userId, JournalEntryType type) {
        List<JournalEntryJpaEntity> entities = type == null
                ? jpa.findByUserIdOrderByUpdatedAtDesc(userId)
                : jpa.findByUserIdAndTypeOrderByUpdatedAtDesc(userId, type);
        return entities.stream().map(JournalEntryJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<JournalEntry> findByIdAndUserId(Long id, Long userId) {
        return jpa.findByIdAndUserId(id, userId).map(JournalEntryJpaEntity::toDomain);
    }

    @Override
    public JournalEntry save(JournalEntry entry) {
        return jpa.save(JournalEntryJpaEntity.fromDomain(entry)).toDomain();
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/infrastructure/persistence/JournalEntryJpaEntity.java \
        backend/src/main/java/com/portfolio/invest/infrastructure/persistence/JournalEntryJpaRepository.java \
        backend/src/main/java/com/portfolio/invest/infrastructure/persistence/JournalEntryRepositoryImpl.java
git commit -m "feat(journal): 记录 JPA 实体与仓库实现"
```

---

### Task 5: 仓库集成测试（Testcontainers 真实 PG）

**Files:**
- Test: `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/JournalEntryRepositoryImplTest.java`

**Interfaces:**
- Consumes: `JournalEntryRepositoryImpl`（Task 4）、Flyway V8（Task 3）。
- 参考既有 `PortfolioRepositoryImplTest` 的 `PostgresTestSupport` 基座（`testFixtures` 源集，共享 Testcontainers 容器，禁用 Ryuk）。

- [ ] **Step 1: 写集成测试**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.PeriodType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class JournalEntryRepositoryImplTest extends PostgresTestSupport {

    @Autowired
    private JournalEntryRepositoryImpl repository;

    private static JournalEntry buyMemo(Long userId) {
        return JournalEntry.create(userId, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", 10L,
                "买入茅台", "理由", new BigDecimal("1800"), new BigDecimal("1500"),
                null, null, null, LocalDate.of(2026, 9, 2), Instant.now());
    }

    @Test
    void 保存记录并回读全部字段() {
        JournalEntry saved = repository.save(buyMemo(1L));

        assertThat(saved.id()).isNotNull();
        var found = repository.findByIdAndUserId(saved.id(), 1L).orElseThrow();
        assertThat(found.type()).isEqualTo(JournalEntryType.BUY_MEMO);
        assertThat(found.stockCode()).isEqualTo("600519");
        assertThat(found.stockName()).isEqualTo("贵州茅台");
        assertThat(found.tradeId()).isEqualTo(10L);
        assertThat(found.targetPrice()).isEqualByComparingTo("1800");
        assertThat(found.stopLoss()).isEqualByComparingTo("1500");
        assertThat(found.eventDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void 按类型过滤() {
        repository.save(buyMemo(1L));
        repository.save(JournalEntry.create(1L, JournalEntryType.REVIEW, null, null, null,
                "Q3 复盘", "内容", null, null,
                PeriodType.QUARTERLY, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 30), Instant.now()));

        assertThat(repository.findByUserId(1L, JournalEntryType.BUY_MEMO)).hasSize(1);
        assertThat(repository.findByUserId(1L, JournalEntryType.REVIEW)).hasSize(1);
        assertThat(repository.findByUserId(1L, null)).hasSize(2);
    }

    @Test
    void 用户隔离() {
        repository.save(buyMemo(1L));
        assertThat(repository.findByUserId(2L, null)).isEmpty();
        assertThat(repository.findByIdAndUserId(repository.findByUserId(1L, null).get(0).id(), 2L)).isEmpty();
    }

    @Test
    void 更新后替换原记录() {
        JournalEntry saved = repository.save(buyMemo(1L));
        repository.save(saved.update("600519", "贵州茅台", 99L, "新标题", "新内容",
                new BigDecimal("2000"), new BigDecimal("1600"), null, null, null,
                LocalDate.of(2026, 9, 3)));

        var found = repository.findByIdAndUserId(saved.id(), 1L).orElseThrow();
        assertThat(found.title()).isEqualTo("新标题");
        assertThat(found.tradeId()).isEqualTo(99L);
        assertThat(found.targetPrice()).isEqualByComparingTo("2000");
        assertThat(found.eventDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void 删除记录() {
        JournalEntry saved = repository.save(buyMemo(1L));
        repository.deleteById(saved.id());
        assertThat(repository.findByIdAndUserId(saved.id(), 1L)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认通过**

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.infrastructure.persistence.JournalEntryRepositoryImplTest" --console=plain`
Expected: PASS（真实 PG，Flyway V8 迁移生效）。

- [ ] **Step 3: Commit**

```bash
git add backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/JournalEntryRepositoryImplTest.java
git commit -m "test(journal): 记录仓库集成测试（真实 PG + V8 迁移）"
```

---

## P1 完成验证

```bash
make test-backend-unit        # 领域单测 + 架构测试（PackageConventionsTest 无需改动）
make test-backend-integration # 含 JournalEntryRepositoryImplTest
```

确认：`domain/journal` 零 Spring 注解、零项目内跨域依赖（ArchUnit 自动校验）。
