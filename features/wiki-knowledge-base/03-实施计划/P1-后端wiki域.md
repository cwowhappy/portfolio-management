# P1 · 后端 wiki 域实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `domain/wiki` 域（WikiEntry 内容聚合 + PrincipleRule 规则聚合）+ application 用例（CRUD + 概念预置 seeding）+ `/api/wiki` REST 端点 + Flyway V16 三表 + JPA 持久化。

**Architecture:** 照 journal 域分层先例：domain 纯 POJO 不可变聚合（静态工厂 + update 返回新实例）→ application @Service 用例（事务边界、userId 首参）→ infrastructure JPA 实现（entity↔domain 转换）→ web Controller（`currentUserId(auth)`）。概念预置走 classpath JSON 目录 + 首次访问 seeding（`wiki_seed_state` 幂等标记）。

**Tech Stack:** Spring Boot 3 / JPA / Flyway / JUnit5 + AssertJ + Mockito / MockMvc / Testcontainers PostgreSQL

**Spec:** `features/wiki-knowledge-base/01-需求规格/需求规格说明.md`、`features/wiki-knowledge-base/02-设计规格/设计规格说明.md`（本计划代码样例均已对照仓库真实代码核实）

## Global Constraints

- 包根 `com.portfolio.invest`；新域包名 `domain/wiki`、`application/wiki`（DDD 分层规范）
- Flyway 最新版本 V15，新迁移为 **V16**（`ddl-auto: none`，schema 只走迁移）
- `/api/wiki/**` 全部 authenticated，**不**加入 `PublicEndpointPaths`
- 阈值单位语义：ratio 类 `0 < t ≤ 1`，倍数类 `t > 0`（`PrincipleMetric.isRatio()`）
- `UNIQUE(user_id, metric)`：每用户每指标至多一条规则
- 错误码（String 常量类风格，同 `JournalErrorCode`）：`NOT_FOUND` / `INVALID_INPUT` / `DUPLICATE_METRIC`
- 后端 JaCoCo 覆盖率 ≥80%；`make test-backend`（`gradlew check`）+ `make test-backend-integration` 全绿
- ArchUnit 白名单按层 `..` 通配，**无需改** `PackageConventionsTest`

---

### Task 1: 域基础件与 WikiEntry 聚合

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/wiki/WikiErrorCode.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/wiki/WikiException.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/wiki/WikiEntryType.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/wiki/WikiEntry.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/wiki/WikiEntryTest.java`

**Interfaces:**
- Produces: `WikiEntry.create(Long userId, WikiEntryType type, String title, String content, String category, String industryCode, Instant now)` / `reconstitute(Long id, Long userId, WikiEntryType type, String title, String content, String category, String industryCode, Instant createdAt, Instant updatedAt, Long version)` / 实例 `update(String title, String content, String category, String industryCode)` 返回新实例；无参访问器 `id()/userId()/type()/title()/content()/category()/industryCode()/createdAt()/updatedAt()/version()`。后续 Task 3/7 依赖这些签名。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.domain.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiEntryTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @DisplayName("创建读书笔记：类型特有列可空")
    @Test
    void givenBookNote_whenCreate_thenSucceed() {
        WikiEntry e = WikiEntry.create(1L, WikiEntryType.BOOK_NOTE, "《聪明的投资者》笔记",
                "## 核心\n- 市场先生", null, null, NOW);
        assertThat(e.id()).isNull();
        assertThat(e.userId()).isEqualTo(1L);
        assertThat(e.type()).isEqualTo(WikiEntryType.BOOK_NOTE);
        assertThat(e.createdAt()).isEqualTo(NOW);
        assertThat(e.updatedAt()).isEqualTo(NOW);
    }

    @DisplayName("创建研究结论：带行业代码与分类可空列")
    @Test
    void givenResearchNote_whenCreate_thenIndustryCodeKept() {
        WikiEntry e = WikiEntry.create(1L, WikiEntryType.RESEARCH_NOTE, "白酒行业研究结论",
                "景气上行", null, "801120", NOW);
        assertThat(e.industryCode()).isEqualTo("801120");
    }

    @DisplayName("标题空/超200字抛INVALID_INPUT")
    @Test
    void givenBlankOrOverlongTitle_whenCreate_thenThrow() {
        assertThatThrownBy(() -> WikiEntry.create(1L, WikiEntryType.CONCEPT, " ", "内容", null, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> WikiEntry.create(1L, WikiEntryType.CONCEPT, "标".repeat(201), "内容", null, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
    }

    @DisplayName("内容空抛INVALID_INPUT")
    @Test
    void givenBlankContent_whenCreate_thenThrow() {
        assertThatThrownBy(() -> WikiEntry.create(1L, WikiEntryType.CONCEPT, "护城河", "", null, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
    }

    @DisplayName("update 返回新实例，type 与 id 不变，createdAt 保留")
    @Test
    void givenEntry_whenUpdate_thenNewInstance() {
        WikiEntry original = WikiEntry.reconstitute(5L, 1L, WikiEntryType.CONCEPT, "护城河", "旧内容",
                "质量", null, NOW, NOW, 0L);
        WikiEntry updated = original.update("护城河（修订）", "新内容", "质量", null);
        assertThat(updated).isNotSameAs(original);
        assertThat(updated.id()).isEqualTo(5L);
        assertThat(updated.type()).isEqualTo(WikiEntryType.CONCEPT);
        assertThat(updated.createdAt()).isEqualTo(NOW);
        assertThat(updated.title()).isEqualTo("护城河（修订）");
        assertThat(original.title()).isEqualTo("护城河"); // 不可变
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.wiki.WikiEntryTest" --console=plain`
Expected: 编译失败（`WikiEntry` 等类不存在）

- [ ] **Step 3: 最小实现**

`WikiErrorCode.java`（照 `JournalErrorCode` 的 String 常量风格）：

```java
package com.portfolio.invest.domain.wiki;

public final class WikiErrorCode {
    private WikiErrorCode() {}

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String DUPLICATE_METRIC = "DUPLICATE_METRIC";
}
```

`WikiException.java`（照 `JournalException`）：

```java
package com.portfolio.invest.domain.wiki;

public class WikiException extends RuntimeException {
    private final String code;

    public WikiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
```

`WikiEntryType.java`：

```java
package com.portfolio.invest.domain.wiki;

/** 知识库条目类型：读书笔记 / 概念速查 / 行业研究结论。 */
public enum WikiEntryType {
    BOOK_NOTE, CONCEPT, RESEARCH_NOTE
}
```

`WikiEntry.java`（照 `JournalEntry` 的不可变聚合模式）：

```java
package com.portfolio.invest.domain.wiki;

import java.time.Instant;

/** 知识库条目聚合根：不可变，update 返回新实例。三类条目统一建模，类型特有字段（category/industryCode）可空。 */
public final class WikiEntry {

    private final Long id;
    private final Long userId;
    private final WikiEntryType type;
    private final String title;
    private final String content;
    private final String category;
    private final String industryCode;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Long version;

    private WikiEntry(Long id, Long userId, WikiEntryType type, String title, String content,
                      String category, String industryCode, Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.category = category;
        this.industryCode = industryCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static WikiEntry create(Long userId, WikiEntryType type, String title, String content,
                                   String category, String industryCode, Instant now) {
        validate(title, content);
        return new WikiEntry(null, userId, type, title, content, category, industryCode, now, now, null);
    }

    public static WikiEntry reconstitute(Long id, Long userId, WikiEntryType type, String title, String content,
                                         String category, String industryCode,
                                         Instant createdAt, Instant updatedAt, Long version) {
        return new WikiEntry(id, userId, type, title, content, category, industryCode, createdAt, updatedAt, version);
    }

    /** 更新可变字段（type/userId 不可变），返回新实例。 */
    public WikiEntry update(String title, String content, String category, String industryCode) {
        validate(title, content);
        return new WikiEntry(id, userId, type, title, content, category, industryCode,
                createdAt, Instant.now(), version);
    }

    /** 类型特有列从轻校验（校验从轻，避免堵死用户路径——设计规格 §3.1）。 */
    private static void validate(String title, String content) {
        if (title == null || title.isBlank()) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "标题不能为空");
        }
        if (title.length() > 200) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "标题长度不能超过200字");
        }
        if (content == null || content.isBlank()) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "内容不能为空");
        }
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public WikiEntryType type() { return type; }
    public String title() { return title; }
    public String content() { return content; }
    public String category() { return category; }
    public String industryCode() { return industryCode; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Long version() { return version; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.wiki.WikiEntryTest" --console=plain`
Expected: PASS（5 tests）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/wiki/ backend/src/test/java/com/portfolio/invest/domain/wiki/
git commit -m "feat(wiki): 域基础件与 WikiEntry 聚合（不可变 + 类型特有列从轻校验）"
```

---

### Task 2: PrincipleMetric 枚举与 PrincipleRule 聚合

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/wiki/PrincipleMetric.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/wiki/PrincipleRule.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/wiki/PrincipleRuleTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WikiException`/`WikiErrorCode`
- Produces: `PrincipleMetric` 四枚举值 + `isRatio()` + `label()`；`PrincipleRule.create(Long userId, PrincipleMetric metric, BigDecimal threshold, boolean enabled, String description, Instant now)` / `reconstitute(Long id, Long userId, PrincipleMetric metric, BigDecimal threshold, boolean enabled, String description, Instant createdAt, Instant updatedAt, Long version)` / `update(BigDecimal threshold, boolean enabled, String description)`；访问器 `id()/userId()/metric()/threshold()/enabled()/description()/createdAt()/updatedAt()/version()`。三期 MS-15 预警与 Task 5/8 依赖。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.domain.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrincipleRuleTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @DisplayName("比例指标：0 < t ≤ 1")
    @Test
    void givenRatioMetric_whenCreate_thenBoundChecked() {
        PrincipleRule r = PrincipleRule.create(1L, PrincipleMetric.SINGLE_POSITION_RATIO,
                new BigDecimal("0.20"), true, "单票不超过20%", NOW);
        assertThat(r.threshold()).isEqualByComparingTo("0.20");
        assertThat(r.enabled()).isTrue();

        assertThatThrownBy(() -> PrincipleRule.create(1L, PrincipleMetric.SINGLE_POSITION_RATIO,
                BigDecimal.ZERO, true, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> PrincipleRule.create(1L, PrincipleMetric.SINGLE_POSITION_RATIO,
                new BigDecimal("1.01"), true, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
    }

    @DisplayName("倍数指标：t > 0")
    @Test
    void givenMultipleMetric_whenCreate_thenPositiveRequired() {
        PrincipleRule r = PrincipleRule.create(1L, PrincipleMetric.STOCK_PE_MAX,
                new BigDecimal("40"), true, "高估值不买", NOW);
        assertThat(r.metric().isRatio()).isFalse();

        assertThatThrownBy(() -> PrincipleRule.create(1L, PrincipleMetric.STOCK_PE_MAX,
                BigDecimal.ZERO, true, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
    }

    @DisplayName("说明超500字抛INVALID_INPUT；null 可")
    @Test
    void givenOverlongDescription_whenCreate_thenThrow() {
        assertThatThrownBy(() -> PrincipleRule.create(1L, PrincipleMetric.STOCK_PB_MAX,
                new BigDecimal("6"), true, "长".repeat(501), NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
        PrincipleRule r = PrincipleRule.create(1L, PrincipleMetric.STOCK_PB_MAX,
                new BigDecimal("6"), false, null, NOW);
        assertThat(r.description()).isNull();
    }

    @DisplayName("update 返回新实例，metric 不可变")
    @Test
    void givenRule_whenUpdate_thenNewInstanceAndMetricKept() {
        PrincipleRule original = PrincipleRule.reconstitute(9L, 1L, PrincipleMetric.STOCK_PE_MAX,
                new BigDecimal("40"), true, null, NOW, NOW, 0L);
        PrincipleRule updated = original.update(new BigDecimal("30"), false, "收紧");
        assertThat(updated).isNotSameAs(original);
        assertThat(updated.metric()).isEqualTo(PrincipleMetric.STOCK_PE_MAX);
        assertThat(updated.threshold()).isEqualByComparingTo("30");
        assertThat(updated.enabled()).isFalse();
    }

    @DisplayName("metric 单位语义与中文标签")
    @Test
    void givenMetrics_whenInspect_thenRatioAndLabelCorrect() {
        assertThat(PrincipleMetric.SINGLE_POSITION_RATIO.isRatio()).isTrue();
        assertThat(PrincipleMetric.INDUSTRY_POSITION_RATIO.isRatio()).isTrue();
        assertThat(PrincipleMetric.STOCK_PE_MAX.isRatio()).isFalse();
        assertThat(PrincipleMetric.STOCK_PB_MAX.isRatio()).isFalse();
        assertThat(PrincipleMetric.SINGLE_POSITION_RATIO.label()).isEqualTo("单票仓位上限");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.wiki.PrincipleRuleTest" --console=plain`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`PrincipleMetric.java`：

```java
package com.portfolio.invest.domain.wiki;

/**
 * 原则纪律指标（三期 MS-15 预警消费同一枚举——稳定契约）。
 * 单位语义固化在枚举：ratio 类阈值区间 (0,1]，倍数类阈值 > 0。
 */
public enum PrincipleMetric {
    SINGLE_POSITION_RATIO(true, "单票仓位上限"),
    INDUSTRY_POSITION_RATIO(true, "单行业仓位上限"),
    STOCK_PE_MAX(false, "个股PE上限"),
    STOCK_PB_MAX(false, "个股PB上限");

    private final boolean ratio;
    private final String label;

    PrincipleMetric(boolean ratio, String label) {
        this.ratio = ratio;
        this.label = label;
    }

    public boolean isRatio() { return ratio; }
    public String label() { return label; }
}
```

`PrincipleRule.java`：

```java
package com.portfolio.invest.domain.wiki;

import java.math.BigDecimal;
import java.time.Instant;

/** 投资原则纪律规则聚合根：不可变，update 返回新实例（metric 不可改）。每用户每指标至多一条（DB UNIQUE 兜底）。 */
public final class PrincipleRule {

    private final Long id;
    private final Long userId;
    private final PrincipleMetric metric;
    private final BigDecimal threshold;
    private final boolean enabled;
    private final String description;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Long version;

    private PrincipleRule(Long id, Long userId, PrincipleMetric metric, BigDecimal threshold, boolean enabled,
                          String description, Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.metric = metric;
        this.threshold = threshold;
        this.enabled = enabled;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static PrincipleRule create(Long userId, PrincipleMetric metric, BigDecimal threshold,
                                       boolean enabled, String description, Instant now) {
        validate(metric, threshold, description);
        return new PrincipleRule(null, userId, metric, threshold, enabled, description, now, now, null);
    }

    public static PrincipleRule reconstitute(Long id, Long userId, PrincipleMetric metric, BigDecimal threshold,
                                             boolean enabled, String description,
                                             Instant createdAt, Instant updatedAt, Long version) {
        return new PrincipleRule(id, userId, metric, threshold, enabled, description, createdAt, updatedAt, version);
    }

    /** 更新可变字段（metric/userId 不可变——指标换设走删旧建新），返回新实例。 */
    public PrincipleRule update(BigDecimal threshold, boolean enabled, String description) {
        validate(metric, threshold, description);
        return new PrincipleRule(id, userId, metric, threshold, enabled, description,
                createdAt, Instant.now(), version);
    }

    private static void validate(PrincipleMetric metric, BigDecimal threshold, String description) {
        if (metric == null) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "指标不能为空");
        }
        if (threshold == null) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "阈值不能为空");
        }
        // 单位语义边界：ratio 类 (0,1]，倍数类 > 0（compareTo 兼容任意 scale）
        int cmpZero = threshold.compareTo(BigDecimal.ZERO);
        if (metric.isRatio()) {
            if (cmpZero <= 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
                throw new WikiException(WikiErrorCode.INVALID_INPUT, "比例阈值必须在 (0,1] 区间（如 0.20 表示 20%）");
            }
        } else if (cmpZero <= 0) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "阈值必须为正数");
        }
        if (description != null && description.length() > 500) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "说明长度不能超过500字");
        }
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public PrincipleMetric metric() { return metric; }
    public BigDecimal threshold() { return threshold; }
    public boolean enabled() { return enabled; }
    public String description() { return description; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Long version() { return version; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.wiki.PrincipleRuleTest" --console=plain`
Expected: PASS（5 tests）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/wiki/Principle*.java backend/src/test/java/com/portfolio/invest/domain/wiki/PrincipleRuleTest.java
git commit -m "feat(wiki): PrincipleMetric 指标枚举（单位语义）与 PrincipleRule 聚合"
```

---

### Task 3: DTO 与 WikiEntryRepository 端口、WikiApplicationService CRUD

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/wiki/WikiEntryRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/wiki/WikiEntryView.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/wiki/CreateWikiEntryCommand.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/wiki/UpdateWikiEntryCommand.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/wiki/WikiApplicationService.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/wiki/WikiApplicationServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WikiEntry`/`WikiEntryType`/`WikiException`/`WikiErrorCode`
- Produces: 端口 `WikiEntryRepository { List<WikiEntry> findByUserId(Long userId, WikiEntryType type); Optional<WikiEntry> findByIdAndUserId(Long id, Long userId); WikiEntry save(WikiEntry entry); void deleteById(Long id); }`；服务方法 `entries(Long userId, WikiEntryType type)` / `getEntry(Long userId, Long entryId)` / `createEntry(Long userId, CreateWikiEntryCommand cmd)` / `updateEntry(Long userId, Long entryId, UpdateWikiEntryCommand cmd)` / `deleteEntry(Long userId, Long entryId)`（Task 6 控制器、Task 7 持久化实现依赖）。`WikiEntryView(Long id, WikiEntryType type, String title, String content, String category, String industryCode, Instant createdAt, Instant updatedAt)` record + `from(WikiEntry)`。Command：`CreateWikiEntryCommand(@NotNull WikiEntryType type, @NotBlank String title, @NotBlank String content, String category, String industryCode)` / `UpdateWikiEntryCommand(@NotBlank String title, @NotBlank String content, String category, String industryCode)`。

- [ ] **Step 1: 写失败测试**（Mockito 风格，照 `JournalApplicationServiceTest`）

```java
package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiApplicationServiceTest {

    private final WikiEntryRepository repo = mock(WikiEntryRepository.class);
    private WikiApplicationService service;

    @BeforeEach
    void setUp() {
        service = new WikiApplicationService(repo); // Task 4 扩为三参（catalog + seedState）时同步改此处
    }

    private static WikiEntry entry(long id, WikiEntryType type) {
        return WikiEntry.reconstitute(id, 1L, type, "标题" + id, "内容" + id,
                type == WikiEntryType.CONCEPT ? "质量" : null,
                type == WikiEntryType.RESEARCH_NOTE ? "801120" : null,
                java.time.Instant.now(), java.time.Instant.now(), 0L);
    }

    @DisplayName("entries 委托仓库（Task 4 将扩展 seeding，本测固定 null type 全量行为）")
    @Test
    void givenEntries_whenList_thenReturnViews() {
        when(repo.findByUserId(1L, WikiEntryType.BOOK_NOTE)).thenReturn(List.of(entry(5L, WikiEntryType.BOOK_NOTE)));
        var views = service.entries(1L, WikiEntryType.BOOK_NOTE);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).title()).isEqualTo("标题5");
    }

    @DisplayName("创建条目：trim 标题后保存返回视图")
    @Test
    void givenCommand_whenCreate_thenSaveAndReturnView() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.createEntry(1L, new CreateWikiEntryCommand(
                WikiEntryType.BOOK_NOTE, "  笔记标题 ", "内容", null, null));
        assertThat(view.title()).isEqualTo("笔记标题");
        verify(repo).save(any(WikiEntry.class));
    }

    @DisplayName("getEntry 归属校验：他人条目抛 NOT_FOUND")
    @Test
    void givenOthersEntry_whenGet_thenThrowNotFound() {
        when(repo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getEntry(1L, 9L))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.NOT_FOUND));
    }

    @DisplayName("更新条目：reconstitute 后 update 保存")
    @Test
    void givenOwnedEntry_whenUpdate_thenSaved() {
        WikiEntry existing = entry(5L, WikiEntryType.CONCEPT);
        when(repo.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.updateEntry(1L, 5L,
                new UpdateWikiEntryCommand("新标题", "新内容", "估值", null));
        assertThat(view.title()).isEqualTo("新标题");
    }

    @DisplayName("删除条目：先归属校验再删")
    @Test
    void givenOwnedEntry_whenDelete_thenDeleted() {
        when(repo.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(entry(5L, WikiEntryType.BOOK_NOTE)));
        service.deleteEntry(1L, 5L);
        verify(repo).deleteById(5L);
    }
}
```

> 注：构造器采用**小步演进**——本任务两参（`repo`），Task 4 引入 `PresetConceptCatalog` 与 `WikiSeedStateRepository` 时扩为三参并同步改本测试 setUp 与既有用例（原 5 个用例不触 seeding，不受影响）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.wiki.WikiApplicationServiceTest" --console=plain`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

`WikiEntryRepository.java`：

```java
package com.portfolio.invest.domain.wiki;

import java.util.List;
import java.util.Optional;

/** 知识库条目仓库端口：归属过滤（userId）在用例层双重保障。 */
public interface WikiEntryRepository {
    List<WikiEntry> findByUserId(Long userId, WikiEntryType type);
    Optional<WikiEntry> findByIdAndUserId(Long id, Long userId);
    WikiEntry save(WikiEntry entry);
    void deleteById(Long id);
}
```

`WikiEntryView.java`（照 `JournalEntryView`）：

```java
package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import java.time.Instant;

public record WikiEntryView(Long id, WikiEntryType type, String title, String content,
                            String category, String industryCode, Instant createdAt, Instant updatedAt) {

    public static WikiEntryView from(WikiEntry e) {
        return new WikiEntryView(e.id(), e.type(), e.title(), e.content(),
                e.category(), e.industryCode(), e.createdAt(), e.updatedAt());
    }
}
```

`CreateWikiEntryCommand.java` / `UpdateWikiEntryCommand.java`（jakarta 校验，照 `CreateJournalEntryCommand`）：

```java
package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateWikiEntryCommand(
        @NotNull WikiEntryType type,
        @NotBlank String title,
        @NotBlank String content,
        String category,
        String industryCode
) {}
```

```java
package com.portfolio.invest.application.wiki;

import jakarta.validation.constraints.NotBlank;

public record UpdateWikiEntryCommand(
        @NotBlank String title,
        @NotBlank String content,
        String category,
        String industryCode
) {}
```

`WikiApplicationService.java`（本任务两参构造版，照 `JournalApplicationService`）：

```java
package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WikiApplicationService {

    private final WikiEntryRepository repository;

    public WikiApplicationService(WikiEntryRepository repository) {
        this.repository = repository;
    }

    public List<WikiEntryView> entries(Long userId, WikiEntryType type) {
        return repository.findByUserId(userId, type).stream().map(WikiEntryView::from).toList();
    }

    @Transactional
    public WikiEntryView createEntry(Long userId, CreateWikiEntryCommand cmd) {
        WikiEntry entry = WikiEntry.create(userId, cmd.type(), cmd.title().trim(), cmd.content(),
                cmd.category(), cmd.industryCode(), Instant.now());
        return WikiEntryView.from(repository.save(entry));
    }

    public WikiEntryView getEntry(Long userId, Long entryId) {
        return WikiEntryView.from(requireEntry(userId, entryId));
    }

    @Transactional
    public WikiEntryView updateEntry(Long userId, Long entryId, UpdateWikiEntryCommand cmd) {
        WikiEntry existing = requireEntry(userId, entryId);
        WikiEntry updated = existing.update(cmd.title().trim(), cmd.content(), cmd.category(), cmd.industryCode());
        return WikiEntryView.from(repository.save(updated));
    }

    @Transactional
    public void deleteEntry(Long userId, Long entryId) {
        requireEntry(userId, entryId);
        repository.deleteById(entryId);
    }

    private WikiEntry requireEntry(Long userId, Long entryId) {
        return repository.findByIdAndUserId(entryId, userId)
                .orElseThrow(() -> new WikiException(WikiErrorCode.NOT_FOUND, "条目不存在"));
    }
}
```

同时把 Step 1 测试中 `new WikiApplicationService(repo, null, null)` 改为 `new WikiApplicationService(repo)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.wiki.WikiApplicationServiceTest" --console=plain`
Expected: PASS（5 tests）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/wiki/WikiEntryRepository.java backend/src/main/java/com/portfolio/invest/application/wiki/ backend/src/test/java/com/portfolio/invest/application/wiki/
git commit -m "feat(wiki): 条目仓库端口与应用服务 CRUD（DTO + 归属校验）"
```

---

### Task 4: PresetConceptCatalog 与概念预置 seeding

**Files:**
- Create: `backend/src/main/resources/wiki/preset-concepts.json`
- Create: `backend/src/main/java/com/portfolio/invest/domain/wiki/WikiSeedStateRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/wiki/PresetConceptCatalog.java`
- Modify: `backend/src/main/java/com/portfolio/invest/application/wiki/WikiApplicationService.java`（两参→三参 + seeding）
- Modify: `backend/src/test/java/com/portfolio/invest/application/wiki/WikiApplicationServiceTest.java`（setUp 适配 + 新增 seeding 用例）
- Test: `backend/src/test/java/com/portfolio/invest/application/wiki/PresetConceptCatalogTest.java`

**Interfaces:**
- Consumes: Task 3 的 `WikiApplicationService`/`WikiEntryRepository`
- Produces: 端口 `WikiSeedStateRepository { boolean existsByUserId(Long userId); void insert(Long userId); }`；`PresetConceptCatalog { List<PresetConcept> concepts(); }`，`record PresetConcept(String title, String category, String content)`；seed 行为：`entries(userId, CONCEPT)` 首次调用插入全部预置 + 写标记（幂等）。Task 7 持久化实现依赖端口签名。

- [ ] **Step 1: 写失败测试**

`PresetConceptCatalogTest.java`：

```java
package com.portfolio.invest.application.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PresetConceptCatalogTest {

    @DisplayName("预置目录加载：10 条、字段齐全、术语唯一")
    @Test
    void whenLoad_thenTenConceptsWithUniqueTitles() {
        PresetConceptCatalog catalog = new PresetConceptCatalog();
        var concepts = catalog.concepts();
        assertThat(concepts).hasSize(10);
        assertThat(concepts).allSatisfy(c -> {
            assertThat(c.title()).isNotBlank();
            assertThat(c.category()).isNotBlank();
            assertThat(c.content()).isNotBlank();
        });
        assertThat(concepts.stream().map(PresetConceptCatalog.PresetConcept::title).distinct()).hasSize(10);
    }
}
```

`WikiApplicationServiceTest.java` 新增（并改 setUp 为三参，catalog 用真实目录、seedState 用 mock）：

```java
    // —— seeding（Task 4）——

    @DisplayName("首次拉取概念：插入预置 + 写标记")
    @Test
    void givenNoSeedMarker_whenListConcepts_thenSeedPresetAndMark() {
        WikiApplicationService seeded = new WikiApplicationService(repo,
                new PresetConceptCatalog(), seedStateRepo);
        when(seedStateRepo.existsByUserId(1L)).thenReturn(false);
        when(repo.findByUserId(1L, WikiEntryType.CONCEPT)).thenReturn(java.util.List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        seeded.entries(1L, WikiEntryType.CONCEPT);

        verify(repo, org.mockito.Mockito.times(10)).save(any(WikiEntry.class));
        verify(seedStateRepo).insert(1L);
    }

    @DisplayName("已有标记：不重复 seeding")
    @Test
    void givenSeedMarkerExists_whenListConcepts_thenNoSeed() {
        WikiApplicationService seeded = new WikiApplicationService(repo,
                new PresetConceptCatalog(), seedStateRepo);
        when(seedStateRepo.existsByUserId(1L)).thenReturn(true);
        when(repo.findByUserId(1L, WikiEntryType.CONCEPT)).thenReturn(java.util.List.of());

        seeded.entries(1L, WikiEntryType.CONCEPT);

        verify(repo, org.mockito.Mockito.never()).save(any());
        verify(seedStateRepo, org.mockito.Mockito.never()).insert(1L);
    }

    @DisplayName("非概念类型不触发 seeding")
    @Test
    void givenBookNote_whenList_thenNoSeedCheck() {
        WikiApplicationService seeded = new WikiApplicationService(repo,
                new PresetConceptCatalog(), seedStateRepo);
        when(repo.findByUserId(1L, WikiEntryType.BOOK_NOTE)).thenReturn(java.util.List.of());

        seeded.entries(1L, WikiEntryType.BOOK_NOTE);

        verify(seedStateRepo, org.mockito.Mockito.never()).existsByUserId(1L);
    }
```

测试类顶部加字段 `private final com.portfolio.invest.domain.wiki.WikiSeedStateRepository seedStateRepo = org.mockito.Mockito.mock(com.portfolio.invest.domain.wiki.WikiSeedStateRepository.class);`，setUp 改为 `service = new WikiApplicationService(repo, new PresetConceptCatalog(), seedStateRepo);`，原 5 个用例不受影响（catalog/seedState 不被触发）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.wiki.*" --console=plain`
Expected: 编译失败（`PresetConceptCatalog`/`WikiSeedStateRepository` 不存在）

- [ ] **Step 3: 最小实现**

`backend/src/main/resources/wiki/preset-concepts.json`（10 条，classpath 随 jar 分发）：

```json
[
  {"title": "护城河", "category": "质量", "content": "企业长期抵御竞争的结构性优势。五种来源：无形资产（品牌/专利/牌照）、转换成本、网络效应、成本优势、有效规模。\n\n评估关键：竞争对手砸钱能否复制？溢价与高回报能否持续十年？"},
  {"title": "安全边际", "category": "估值", "content": "格雷厄姆核心概念：**买入价显著低于内在价值**，为误判与坏运气留缓冲。\n\n安全边际把「不必精准预测市场」变成方法论——估值容错靠折扣，不靠小数点精度。"},
  {"title": "能力圈", "category": "行为", "content": "巴菲特：只投自己真正理解的生意。\n\n边界比大小重要——知道自己在圈外，比误以为在圈内值钱得多。"},
  {"title": "市场先生", "category": "行为", "content": "格雷厄姆寓言：市场是位躁郁的报价者，每天给你一个价格。\n\n你可以利用他的情绪（低价买/高价卖），但别让他的情绪指挥你的决策。"},
  {"title": "内在价值", "category": "估值", "content": "企业存续期自由现金的折现值，与市场价格相互独立。\n\n估算必然粗糙——所以结论要用安全边际兜底，而不是追求精确。"},
  {"title": "复利效应", "category": "基础", "content": "收益再生收益的非线性积累：年化 15% 十年约 4 倍。\n\n两个前提：**时间足够长**、**不亏大钱**（-50% 需要 +100% 才回本）。"},
  {"title": "均值回归", "category": "行为", "content": "极端的好与坏都难持久：超高 ROE 吸引竞争与替代，超低估值终被修复。\n\n警惕把「暂时的好」线性外推成「永远的好」。"},
  {"title": "净资产收益率（ROE）", "category": "质量", "content": "净利润 / 净资产，衡量股东资本的回报率。\n\n杜邦拆解：净利率 × 资产周转 × 杠杆——高 ROE 要看靠哪一段撑起来（杠杆撑的不可持续）。"},
  {"title": "市盈率（PE）", "category": "估值", "content": "价格 / 每股收益（或市值 / 净利润），「按当前盈利回本年数」的粗略刻度。\n\n注意：口径（TTM/静态）、亏损失真、周期股「低 PE 陷阱」（盈利顶点 PE 最低）。"},
  {"title": "市净率（PB）", "category": "估值", "content": "价格 / 每股净资产。\n\n适合重资产与金融业估值；轻资产公司 PB 常年偏高，跨行业不可直接比较。"}
]
```

`WikiSeedStateRepository.java`（domain 端口）：

```java
package com.portfolio.invest.domain.wiki;

/** 概念预置 seeding 幂等标记仓库端口。 */
public interface WikiSeedStateRepository {
    boolean existsByUserId(Long userId);
    void insert(Long userId);
}
```

`PresetConceptCatalog.java`（application，classpath 资源加载）：

```java
package com.portfolio.invest.application.wiki;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Component;

/** 预置概念目录：classpath 资源随 jar 分发（Skill 域同款先例），构造时一次加载。 */
@Component
public class PresetConceptCatalog {

    public record PresetConcept(String title, String category, String content) {}

    private final List<PresetConcept> concepts;

    public PresetConceptCatalog() {
        try (var in = getClass().getResourceAsStream("/wiki/preset-concepts.json")) {
            if (in == null) {
                throw new IllegalStateException("预置概念资源缺失：/wiki/preset-concepts.json");
            }
            this.concepts = new ObjectMapper().readValue(in, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("预置概念资源解析失败", e);
        }
    }

    public List<PresetConcept> concepts() {
        return concepts;
    }
}
```

`WikiApplicationService.java` 改三参构造 + seeding（`entries` 加 `@Transactional`——首次含批量写）：

```java
    private final WikiEntryRepository repository;
    private final PresetConceptCatalog presetConcepts;
    private final WikiSeedStateRepository seedStateRepository;

    public WikiApplicationService(WikiEntryRepository repository,
                                  PresetConceptCatalog presetConcepts,
                                  WikiSeedStateRepository seedStateRepository) {
        this.repository = repository;
        this.presetConcepts = presetConcepts;
        this.seedStateRepository = seedStateRepository;
    }

    /** 概念首次拉取触发预置 seeding（幂等：wiki_seed_state 标记；删光不复活）。 */
    @Transactional
    public List<WikiEntryView> entries(Long userId, WikiEntryType type) {
        if (type == WikiEntryType.CONCEPT) {
            seedPresetConcepts(userId);
        }
        return repository.findByUserId(userId, type).stream().map(WikiEntryView::from).toList();
    }

    private void seedPresetConcepts(Long userId) {
        if (seedStateRepository.existsByUserId(userId)) {
            return;
        }
        Instant now = Instant.now();
        for (PresetConceptCatalog.PresetConcept c : presetConcepts.concepts()) {
            repository.save(WikiEntry.create(userId, WikiEntryType.CONCEPT,
                    c.title(), c.content(), c.category(), null, now));
        }
        seedStateRepository.insert(userId);
    }
```

（其余方法不变；import 区加 `org.springframework.transaction.annotation.Transactional` 已有。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.wiki.*" --console=plain`
Expected: PASS（8 tests：原 5 + seeding 3）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/wiki/ backend/src/main/java/com/portfolio/invest/domain/wiki/WikiSeedStateRepository.java backend/src/main/java/com/portfolio/invest/application/wiki/ backend/src/test/java/com/portfolio/invest/application/wiki/
git commit -m "feat(wiki): 预置概念目录（10 条）与首次访问幂等 seeding"
```

---

### Task 5: PrincipleRuleRepository 端口与 PrincipleRuleApplicationService

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/wiki/PrincipleRuleRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/wiki/PrincipleRuleView.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/wiki/CreatePrincipleRuleCommand.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/wiki/UpdatePrincipleRuleCommand.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/wiki/PrincipleRuleApplicationService.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/wiki/PrincipleRuleApplicationServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的 `PrincipleRule`/`PrincipleMetric`
- Produces: 端口 `PrincipleRuleRepository { List<PrincipleRule> findByUserId(Long userId); Optional<PrincipleRule> findByIdAndUserId(Long id, Long userId); PrincipleRule save(PrincipleRule rule); void deleteById(Long id); }`；服务 `rules(Long userId)` / `createRule(Long userId, CreatePrincipleRuleCommand cmd)` / `updateRule(Long userId, Long ruleId, UpdatePrincipleRuleCommand cmd)` / `deleteRule(Long userId, Long ruleId)`；`PrincipleRuleView(Long id, PrincipleMetric metric, BigDecimal threshold, boolean enabled, String description, Instant createdAt, Instant updatedAt)` + `from(PrincipleRule)`；Command：`CreatePrincipleRuleCommand(@NotNull PrincipleMetric metric, @NotNull BigDecimal threshold, boolean enabled, String description)` / `UpdatePrincipleRuleCommand(@NotNull BigDecimal threshold, boolean enabled, String description)`。UNIQUE 冲突翻译 `DUPLICATE_METRIC`（Task 6 控制器、Task 8 持久化依赖）。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrincipleRuleApplicationServiceTest {

    private final PrincipleRuleRepository repo = mock(PrincipleRuleRepository.class);
    private PrincipleRuleApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PrincipleRuleApplicationService(repo);
    }

    private static PrincipleRule rule(long id, PrincipleMetric metric) {
        return PrincipleRule.reconstitute(id, 1L, metric, new BigDecimal("0.20"), true, null,
                java.time.Instant.now(), java.time.Instant.now(), 0L);
    }

    @DisplayName("创建规则：保存返回视图")
    @Test
    void givenCommand_whenCreate_thenSaveAndReturn() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.createRule(1L, new CreatePrincipleRuleCommand(
                PrincipleMetric.SINGLE_POSITION_RATIO, new BigDecimal("0.20"), true, "单票≤20%"));
        assertThat(view.metric()).isEqualTo(PrincipleMetric.SINGLE_POSITION_RATIO);
        assertThat(view.threshold()).isEqualByComparingTo("0.20");
    }

    @DisplayName("UNIQUE 冲突翻译为 DUPLICATE_METRIC（DB 约束兜底路径）")
    @Test
    void givenDuplicateMetric_whenCreate_thenThrowDuplicateMetric() {
        when(repo.save(any())).thenThrow(new DataIntegrityViolationException("uk_principle_rule_user_metric"));
        assertThatThrownBy(() -> service.createRule(1L, new CreatePrincipleRuleCommand(
                PrincipleMetric.SINGLE_POSITION_RATIO, new BigDecimal("0.20"), true, null)))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.DUPLICATE_METRIC));
    }

    @DisplayName("更新规则：归属校验 + 启停切换")
    @Test
    void givenOwnedRule_whenUpdate_thenToggleAndSave() {
        when(repo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(rule(9L, PrincipleMetric.STOCK_PE_MAX)));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.updateRule(1L, 9L, new UpdatePrincipleRuleCommand(new BigDecimal("30"), false, "收紧"));
        assertThat(view.enabled()).isFalse();
        assertThat(view.threshold()).isEqualByComparingTo("30");
    }

    @DisplayName("他人规则更新/删除抛 NOT_FOUND")
    @Test
    void givenOthersRule_whenUpdateOrDelete_thenThrowNotFound() {
        when(repo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateRule(1L, 9L,
                new UpdatePrincipleRuleCommand(BigDecimal.ONE, true, null)))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.deleteRule(1L, 9L))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.NOT_FOUND));
    }

    @DisplayName("删除规则：先归属校验再删")
    @Test
    void givenOwnedRule_whenDelete_thenDeleted() {
        when(repo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(rule(9L, PrincipleMetric.STOCK_PB_MAX)));
        service.deleteRule(1L, 9L);
        verify(repo).deleteById(9L);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.wiki.PrincipleRuleApplicationServiceTest" --console=plain`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

`PrincipleRuleRepository.java`：

```java
package com.portfolio.invest.domain.wiki;

import java.util.List;
import java.util.Optional;

/** 原则纪律规则仓库端口。 */
public interface PrincipleRuleRepository {
    List<PrincipleRule> findByUserId(Long userId);
    Optional<PrincipleRule> findByIdAndUserId(Long id, Long userId);
    PrincipleRule save(PrincipleRule rule);
    void deleteById(Long id);
}
```

`PrincipleRuleView.java`：

```java
package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import java.math.BigDecimal;
import java.time.Instant;

public record PrincipleRuleView(Long id, PrincipleMetric metric, BigDecimal threshold, boolean enabled,
                                String description, Instant createdAt, Instant updatedAt) {

    public static PrincipleRuleView from(PrincipleRule r) {
        return new PrincipleRuleView(r.id(), r.metric(), r.threshold(), r.enabled(),
                r.description(), r.createdAt(), r.updatedAt());
    }
}
```

`CreatePrincipleRuleCommand.java` / `UpdatePrincipleRuleCommand.java`：

```java
package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreatePrincipleRuleCommand(
        @NotNull PrincipleMetric metric,
        @NotNull BigDecimal threshold,
        boolean enabled,
        String description
) {}
```

```java
package com.portfolio.invest.application.wiki;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpdatePrincipleRuleCommand(
        @NotNull BigDecimal threshold,
        boolean enabled,
        String description
) {}
```

`PrincipleRuleApplicationService.java`：

```java
package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrincipleRuleApplicationService {

    private final PrincipleRuleRepository repository;

    public PrincipleRuleApplicationService(PrincipleRuleRepository repository) {
        this.repository = repository;
    }

    public List<PrincipleRuleView> rules(Long userId) {
        return repository.findByUserId(userId).stream().map(PrincipleRuleView::from).toList();
    }

    @Transactional
    public PrincipleRuleView createRule(Long userId, CreatePrincipleRuleCommand cmd) {
        PrincipleRule rule = PrincipleRule.create(userId, cmd.metric(), cmd.threshold(),
                cmd.enabled(), cmd.description(), Instant.now());
        try {
            return PrincipleRuleView.from(repository.save(rule));
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(user_id, metric) 兜底：同指标已配置 → 友好冲突（409）
            throw new WikiException(WikiErrorCode.DUPLICATE_METRIC, "该指标已有规则，请直接编辑既有规则");
        }
    }

    @Transactional
    public PrincipleRuleView updateRule(Long userId, Long ruleId, UpdatePrincipleRuleCommand cmd) {
        PrincipleRule existing = requireRule(userId, ruleId);
        PrincipleRule updated = existing.update(cmd.threshold(), cmd.enabled(), cmd.description());
        return PrincipleRuleView.from(repository.save(updated));
    }

    @Transactional
    public void deleteRule(Long userId, Long ruleId) {
        requireRule(userId, ruleId);
        repository.deleteById(ruleId);
    }

    private PrincipleRule requireRule(Long userId, Long ruleId) {
        return repository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> new WikiException(WikiErrorCode.NOT_FOUND, "规则不存在"));
    }
}
```

> `PrincipleMetric` import 未用到可去掉（保持无未用 import，lint 会查）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.wiki.PrincipleRuleApplicationServiceTest" --console=plain`
Expected: PASS（5 tests）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/wiki/PrincipleRuleRepository.java backend/src/main/java/com/portfolio/invest/application/wiki/ backend/src/test/java/com/portfolio/invest/application/wiki/PrincipleRuleApplicationServiceTest.java
git commit -m "feat(wiki): 规则仓库端口与应用服务（UNIQUE 冲突翻译 DUPLICATE_METRIC）"
```

---

### Task 6: WikiController 与 GlobalExceptionHandler 扩展

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/web/WikiController.java`
- Modify: `backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java`（追加 wiki handler）
- Test: `backend/src/test/java/com/portfolio/invest/web/WikiControllerTest.java`

**Interfaces:**
- Consumes: Task 3/4/5 的两服务与 DTO
- Produces: REST 端点（前端 P2 的 `lib/wikiApi.ts` 契约）——`GET /api/wiki/entries?type=` / `POST /api/wiki/entries`（201）/ `GET|PUT|DELETE /api/wiki/entries/{entryId}` / `GET /api/wiki/rules` / `POST /api/wiki/rules`（201）/ `PUT|DELETE /api/wiki/rules/{ruleId}`；错误映射 NOT_FOUND→404、DUPLICATE_METRIC→409、其余→400。

- [ ] **Step 1: 写失败测试**（MockMvc standalone，照 `JournalControllerTest`）

```java
package com.portfolio.invest.web;

import com.portfolio.invest.application.wiki.CreatePrincipleRuleCommand;
import com.portfolio.invest.application.wiki.CreateWikiEntryCommand;
import com.portfolio.invest.application.wiki.PrincipleRuleApplicationService;
import com.portfolio.invest.application.wiki.PrincipleRuleView;
import com.portfolio.invest.application.wiki.WikiApplicationService;
import com.portfolio.invest.application.wiki.WikiEntryView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WikiControllerTest {

    private final WikiApplicationService wikiService = mock(WikiApplicationService.class);
    private final PrincipleRuleApplicationService ruleService = mock(PrincipleRuleApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WikiController(wikiService, ruleService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private org.springframework.security.core.Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    @DisplayName("创建条目返回201")
    @Test
    void givenValidCommand_whenCreateEntry_thenReturn201() throws Exception {
        when(wikiService.createEntry(eq(1L), any(CreateWikiEntryCommand.class)))
                .thenReturn(new WikiEntryView(5L, WikiEntryType.BOOK_NOTE, "笔记", "内容",
                        null, null, Instant.now(), Instant.now()));
        mvc.perform(post("/api/wiki/entries").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BOOK_NOTE\",\"title\":\"笔记\",\"content\":\"内容\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("笔记"));
    }

    @DisplayName("列表按类型过滤返回200")
    @Test
    void givenTypeFilter_whenListEntries_thenReturn200() throws Exception {
        when(wikiService.entries(1L, WikiEntryType.CONCEPT)).thenReturn(List.of(
                new WikiEntryView(5L, WikiEntryType.CONCEPT, "护城河", "解释", "质量", null,
                        Instant.now(), Instant.now())));
        mvc.perform(get("/api/wiki/entries").principal(auth()).param("type", "CONCEPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CONCEPT"))
                .andExpect(jsonPath("$[0].category").value("质量"));
    }

    @DisplayName("他人条目映射404")
    @Test
    void givenOthersEntry_whenGetEntry_thenReturn404() throws Exception {
        when(wikiService.getEntry(1L, 99L)).thenThrow(new WikiException(WikiErrorCode.NOT_FOUND, "条目不存在"));
        mvc.perform(get("/api/wiki/entries/99").principal(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @DisplayName("创建规则返回201")
    @Test
    void givenValidRule_whenCreateRule_thenReturn201() throws Exception {
        when(ruleService.createRule(eq(1L), any(CreatePrincipleRuleCommand.class)))
                .thenReturn(new PrincipleRuleView(9L, PrincipleMetric.SINGLE_POSITION_RATIO,
                        new BigDecimal("0.20"), true, null, Instant.now(), Instant.now()));
        mvc.perform(post("/api/wiki/rules").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metric\":\"SINGLE_POSITION_RATIO\",\"threshold\":0.20,\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metric").value("SINGLE_POSITION_RATIO"));
    }

    @DisplayName("同指标重复规则映射409 DUPLICATE_METRIC")
    @Test
    void givenDuplicateMetric_whenCreateRule_thenReturn409() throws Exception {
        when(ruleService.createRule(eq(1L), any(CreatePrincipleRuleCommand.class)))
                .thenThrow(new WikiException(WikiErrorCode.DUPLICATE_METRIC, "该指标已有规则，请直接编辑既有规则"));
        mvc.perform(post("/api/wiki/rules").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metric\":\"SINGLE_POSITION_RATIO\",\"threshold\":0.20,\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_METRIC"));
    }

    @DisplayName("规则列表返回200")
    @Test
    void whenListRules_thenReturn200() throws Exception {
        when(ruleService.rules(1L)).thenReturn(List.of(
                new PrincipleRuleView(9L, PrincipleMetric.STOCK_PE_MAX, new BigDecimal("40"),
                        true, "高估值不买", Instant.now(), Instant.now())));
        mvc.perform(get("/api/wiki/rules").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].metric").value("STOCK_PE_MAX"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.web.WikiControllerTest" --console=plain`
Expected: 编译失败（`WikiController` 不存在）

- [ ] **Step 3: 最小实现**

`WikiController.java`（照 `JournalController`）：

```java
package com.portfolio.invest.web;

import com.portfolio.invest.application.wiki.CreatePrincipleRuleCommand;
import com.portfolio.invest.application.wiki.CreateWikiEntryCommand;
import com.portfolio.invest.application.wiki.PrincipleRuleApplicationService;
import com.portfolio.invest.application.wiki.PrincipleRuleView;
import com.portfolio.invest.application.wiki.UpdatePrincipleRuleCommand;
import com.portfolio.invest.application.wiki.UpdateWikiEntryCommand;
import com.portfolio.invest.application.wiki.WikiApplicationService;
import com.portfolio.invest.application.wiki.WikiEntryView;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wiki")
public class WikiController {

    private final WikiApplicationService wikiService;
    private final PrincipleRuleApplicationService ruleService;

    public WikiController(WikiApplicationService wikiService, PrincipleRuleApplicationService ruleService) {
        this.wikiService = wikiService;
        this.ruleService = ruleService;
    }

    @GetMapping("/entries")
    public List<WikiEntryView> entries(Authentication auth,
                                       @RequestParam(required = false) WikiEntryType type) {
        return wikiService.entries(currentUserId(auth), type);
    }

    @PostMapping("/entries")
    public ResponseEntity<WikiEntryView> createEntry(Authentication auth,
                                                     @Valid @RequestBody CreateWikiEntryCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wikiService.createEntry(currentUserId(auth), cmd));
    }

    @GetMapping("/entries/{entryId}")
    public WikiEntryView getEntry(Authentication auth, @PathVariable Long entryId) {
        return wikiService.getEntry(currentUserId(auth), entryId);
    }

    @PutMapping("/entries/{entryId}")
    public WikiEntryView updateEntry(Authentication auth, @PathVariable Long entryId,
                                     @Valid @RequestBody UpdateWikiEntryCommand cmd) {
        return wikiService.updateEntry(currentUserId(auth), entryId, cmd);
    }

    @DeleteMapping("/entries/{entryId}")
    public ResponseEntity<Void> deleteEntry(Authentication auth, @PathVariable Long entryId) {
        wikiService.deleteEntry(currentUserId(auth), entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rules")
    public List<PrincipleRuleView> rules(Authentication auth) {
        return ruleService.rules(currentUserId(auth));
    }

    @PostMapping("/rules")
    public ResponseEntity<PrincipleRuleView> createRule(Authentication auth,
                                                        @Valid @RequestBody CreatePrincipleRuleCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.createRule(currentUserId(auth), cmd));
    }

    @PutMapping("/rules/{ruleId}")
    public PrincipleRuleView updateRule(Authentication auth, @PathVariable Long ruleId,
                                        @Valid @RequestBody UpdatePrincipleRuleCommand cmd) {
        return ruleService.updateRule(currentUserId(auth), ruleId, cmd);
    }

    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(Authentication auth, @PathVariable Long ruleId) {
        ruleService.deleteRule(currentUserId(auth), ruleId);
        return ResponseEntity.noContent().build();
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
```

`GlobalExceptionHandler.java` 在 `journal` handler 后追加：

```java
    @ExceptionHandler(com.portfolio.invest.domain.wiki.WikiException.class)
    public ResponseEntity<ApiError> wiki(com.portfolio.invest.domain.wiki.WikiException e) {
        HttpStatus status = switch (e.code()) {
            case com.portfolio.invest.domain.wiki.WikiErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case com.portfolio.invest.domain.wiki.WikiErrorCode.DUPLICATE_METRIC -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage()));
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.web.WikiControllerTest" --console=plain`
Expected: PASS（6 tests）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/web/WikiController.java backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java backend/src/test/java/com/portfolio/invest/web/WikiControllerTest.java
git commit -m "feat(wiki): /api/wiki REST 端点与全局异常映射（404/409/400）"
```

---

### Task 7: Flyway V16 迁移与 wiki_entry/wiki_seed_state 持久化

**Files:**
- Create: `backend/src/main/resources/db/migration/V16__wiki.sql`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/WikiEntryJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/WikiEntryJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/WikiEntryRepositoryImpl.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/WikiSeedStateJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/WikiSeedStateJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/WikiSeedStateRepositoryImpl.java`
- Test: `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/WikiEntryRepositoryImplTest.java`

**Interfaces:**
- Consumes: Task 3/4 端口（`WikiEntryRepository`/`WikiSeedStateRepository`）
- Produces: `WikiEntryJpaRepository extends JpaRepository<WikiEntryJpaEntity, Long>`（派生查询 `findByUserIdOrderByUpdatedAtDesc` / `findByUserIdAndTypeOrderByUpdatedAtDesc` / `findByIdAndUserId`）；V16 三表。集成测试以 `@DataJpaTest` + Testcontainers 真实应用 V16 迁移验证表结构。

- [ ] **Step 1: 写失败集成测试**（照 `WatchlistRepositoryImplTest` 范式）

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiSeedStateRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({WikiEntryRepositoryImpl.class, WikiSeedStateRepositoryImpl.class})
class WikiEntryRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private WikiEntryRepository repository;

    @Autowired
    private WikiSeedStateRepository seedStateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "wiki_" + System.nanoTime());
    }

    @DisplayName("保存与按用户/类型查询（updatedAt 倒序）、归属过滤、删除")
    @Test
    @Transactional
    void givenEntries_whenFindDelete_thenBehave() {
        Long user = seedUser();
        Instant early = Instant.parse("2026-09-21T00:00:00Z");
        Instant late = Instant.parse("2026-09-22T00:00:00Z");
        WikiEntry saved1 = repository.save(WikiEntry.create(user, WikiEntryType.CONCEPT,
                "护城河", "解释", "质量", null, early));
        WikiEntry saved2 = repository.save(WikiEntry.create(user, WikiEntryType.BOOK_NOTE,
                "笔记", "内容", null, null, late));
        assertThat(saved1.id()).isNotNull();
        assertThat(saved2.id()).isNotNull();

        assertThat(repository.findByUserId(user, null))
                .extracting(WikiEntry::title).containsExactly("笔记", "护城河"); // updatedAt 倒序
        assertThat(repository.findByUserId(user, WikiEntryType.CONCEPT))
                .extracting(WikiEntry::title).containsExactly("护城河");
        assertThat(repository.findByUserId(user + 1, null)).isEmpty(); // 用户隔离

        assertThat(repository.findByIdAndUserId(saved1.id(), user)).isPresent();
        assertThat(repository.findByIdAndUserId(saved1.id(), user + 1)).isEmpty();

        // update 语义：改标题重新 save，updatedAt 变化（version 乐观锁由 JPA @Version 维护）
        WikiEntry updated = repository.save(saved1.update("护城河（修订）", "解释2", "质量", null));
        assertThat(updated.title()).isEqualTo("护城河（修订）");

        repository.deleteById(saved2.id());
        assertThat(repository.findByIdAndUserId(saved2.id(), user)).isEmpty();
    }

    @DisplayName("seed 标记：插入后可查存在，重复 insert 走 PK 约束由服务层幂等语义规避")
    @Test
    @Transactional
    void givenSeedMarker_whenExists_thenTrue() {
        Long user = seedUser();
        assertThat(seedStateRepository.existsByUserId(user)).isFalse();
        seedStateRepository.insert(user);
        assertThat(seedStateRepository.existsByUserId(user)).isTrue();
        assertThat(seedStateRepository.existsByUserId(user + 1)).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.infrastructure.persistence.WikiEntryRepositoryImplTest" --console=plain`
Expected: 编译失败（JPA 实体不存在）

- [ ] **Step 3: 最小实现**

`V16__wiki.sql`：

```sql
-- 投资知识库：三类内容条目（单表，类型特有字段可空）+ 原则纪律规则 + 概念预置幂等标记
-- principle_rule 每用户每指标至多一条（UNIQUE），三期 MS-15 预警消费。

CREATE TABLE wiki_entry (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id),
    type          VARCHAR(16)  NOT NULL,            -- BOOK_NOTE / CONCEPT / RESEARCH_NOTE
    title         VARCHAR(200) NOT NULL,            -- 概念词条即术语名本身，不另设 term 列
    content       TEXT NOT NULL,                    -- Markdown
    category      VARCHAR(50),                      -- CONCEPT 专用：分类
    industry_code VARCHAR(16),                      -- RESEARCH_NOTE 专用：申万一级行业代码
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wiki_entry_user_type ON wiki_entry(user_id, type, updated_at DESC);

CREATE TABLE principle_rule (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    metric      VARCHAR(32) NOT NULL,               -- PrincipleMetric 枚举
    threshold   NUMERIC(12,4) NOT NULL,             -- 单位由枚举语义约定（ratio (0,1] / 倍数 >0）
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),
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

`WikiEntryJpaEntity.java`（照 `JournalEntryJpaEntity`）：

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "wiki_entry")
public class WikiEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WikiEntryType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 50)
    private String category;

    @Column(name = "industry_code", length = 16)
    private String industryCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected WikiEntryJpaEntity() {}

    public static WikiEntryJpaEntity fromDomain(WikiEntry e) {
        WikiEntryJpaEntity entity = new WikiEntryJpaEntity();
        entity.id = e.id();
        entity.userId = e.userId();
        entity.type = e.type();
        entity.title = e.title();
        entity.content = e.content();
        entity.category = e.category();
        entity.industryCode = e.industryCode();
        entity.createdAt = e.createdAt();
        entity.updatedAt = e.updatedAt();
        entity.version = e.version();
        return entity;
    }

    public WikiEntry toDomain() {
        return WikiEntry.reconstitute(id, userId, type, title, content, category, industryCode,
                createdAt, updatedAt, version);
    }
}
```

`WikiEntryJpaRepository.java`：

```java
package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiEntryJpaRepository extends JpaRepository<WikiEntryJpaEntity, Long> {
    List<WikiEntryJpaEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    List<WikiEntryJpaEntity> findByUserIdAndTypeOrderByUpdatedAtDesc(Long userId, WikiEntryType type);
    Optional<WikiEntryJpaEntity> findByIdAndUserId(Long id, Long userId);
}
```

`WikiEntryRepositoryImpl.java`（事务边界在 application 层，本类不挂 @Transactional）：

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class WikiEntryRepositoryImpl implements WikiEntryRepository {

    private final WikiEntryJpaRepository jpa;

    public WikiEntryRepositoryImpl(WikiEntryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<WikiEntry> findByUserId(Long userId, WikiEntryType type) {
        List<WikiEntryJpaEntity> entities = type == null
                ? jpa.findByUserIdOrderByUpdatedAtDesc(userId)
                : jpa.findByUserIdAndTypeOrderByUpdatedAtDesc(userId, type);
        return entities.stream().map(WikiEntryJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<WikiEntry> findByIdAndUserId(Long id, Long userId) {
        return jpa.findByIdAndUserId(id, userId).map(WikiEntryJpaEntity::toDomain);
    }

    @Override
    public WikiEntry save(WikiEntry entry) {
        return jpa.save(WikiEntryJpaEntity.fromDomain(entry)).toDomain();
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }
}
```

`WikiSeedStateJpaEntity.java`：

```java
package com.portfolio.invest.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "wiki_seed_state")
public class WikiSeedStateJpaEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "seeded_at", nullable = false)
    private Instant seededAt;

    protected WikiSeedStateJpaEntity() {}

    public static WikiSeedStateJpaEntity of(Long userId) {
        WikiSeedStateJpaEntity e = new WikiSeedStateJpaEntity();
        e.userId = userId;
        e.seededAt = Instant.now();
        return e;
    }
}
```

`WikiSeedStateJpaRepository.java`：

```java
package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiSeedStateJpaRepository extends JpaRepository<WikiSeedStateJpaEntity, Long> {
    boolean existsByUserId(Long userId);
}
```

`WikiSeedStateRepositoryImpl.java`：

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.WikiSeedStateRepository;
import org.springframework.stereotype.Repository;

@Repository
public class WikiSeedStateRepositoryImpl implements WikiSeedStateRepository {

    private final WikiSeedStateJpaRepository jpa;

    public WikiSeedStateRepositoryImpl(WikiSeedStateJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existsByUserId(Long userId) {
        return jpa.existsByUserId(userId);
    }

    @Override
    public void insert(Long userId) {
        jpa.saveAndFlush(WikiSeedStateJpaEntity.of(userId));
    }
}
```

> `saveAndFlush`：让并发下第二个事务的 PK 冲突在服务层事务内即时显现（而非提交时），语义与「标记表 PK 幂等」设计一致。

- [ ] **Step 4: 跑集成测试确认通过**（Testcontainers 会真实应用 V16，一并验证迁移）

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.infrastructure.persistence.WikiEntryRepositoryImplTest" --console=plain`
Expected: PASS（2 tests）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V16__wiki.sql backend/src/main/java/com/portfolio/invest/infrastructure/persistence/Wiki* backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/WikiEntryRepositoryImplTest.java
git commit -m "feat(wiki): Flyway V16 三表与 wiki_entry/seed_state JPA 持久化"
```

---

### Task 8: principle_rule 持久化与 UNIQUE 约束集成验证

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/PrincipleRuleJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/PrincipleRuleJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/PrincipleRuleRepositoryImpl.java`
- Test: `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/PrincipleRuleRepositoryImplTest.java`

**Interfaces:**
- Consumes: Task 5 端口 `PrincipleRuleRepository`；Task 7 的 V16（`principle_rule` 表已在同一迁移建好）
- Produces: `PrincipleRuleJpaRepository extends JpaRepository<PrincipleRuleJpaEntity, Long>`（`findByUserIdOrderByMetricAsc` / `findByIdAndUserId`）；DB 级 UNIQUE(user_id, metric) 真实约束验证。

- [ ] **Step 1: 写失败集成测试**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(PrincipleRuleRepositoryImpl.class)
class PrincipleRuleRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private PrincipleRuleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "pr_" + System.nanoTime());
    }

    @DisplayName("保存/按用户查询/归属过滤/删除")
    @Test
    @Transactional
    void givenRules_whenFindDelete_thenBehave() {
        Long user = seedUser();
        PrincipleRule saved = repository.save(PrincipleRule.create(user, PrincipleMetric.SINGLE_POSITION_RATIO,
                new BigDecimal("0.20"), true, "单票≤20%", java.time.Instant.now()));
        assertThat(saved.id()).isNotNull();

        assertThat(repository.findByUserId(user)).hasSize(1);
        assertThat(repository.findByUserId(user + 1)).isEmpty(); // 用户隔离
        assertThat(repository.findByIdAndUserId(saved.id(), user)).isPresent();
        assertThat(repository.findByIdAndUserId(saved.id(), user + 1)).isEmpty();

        repository.deleteById(saved.id());
        assertThat(repository.findByUserId(user)).isEmpty();
    }

    @DisplayName("UNIQUE(user_id, metric)：同用户同指标第二条被 DB 拒绝")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void givenDuplicateMetric_whenSave_thenRejectedByDb() {
        Long user = seedUser();
        repository.save(PrincipleRule.create(user, PrincipleMetric.STOCK_PE_MAX,
                new BigDecimal("40"), true, null, java.time.Instant.now()));
        // 需先落库（NOT_SUPPORTED 下无事务包裹，save 即提交），再插第二条触发约束
        assertThatThrownBy(() -> {
            repository.save(PrincipleRule.create(user, PrincipleMetric.STOCK_PE_MAX,
                    new BigDecimal("35"), true, null, java.time.Instant.now()));
            // 无外层事务时约束违例在 save/flush 时抛出；若时序未触发，下面显式查数断言兜底
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

> 注：约束触发时机依赖 flush。若实现后该用例因 flush 延迟不抛，给第二条 save 后补 `jdbcTemplate.getDataSource()` 查询或改用 `saveAndFlush` 语义验证——以**真实抛出 DataIntegrityViolationException** 为准（应用层 Task 5 的翻译路径依赖它）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.infrastructure.persistence.PrincipleRuleRepositoryImplTest" --console=plain`
Expected: 编译失败

- [ ] **Step 3: 最小实现**

`PrincipleRuleJpaEntity.java`：

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "principle_rule")
public class PrincipleRuleJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PrincipleMetric metric;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal threshold;

    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected PrincipleRuleJpaEntity() {}

    public static PrincipleRuleJpaEntity fromDomain(PrincipleRule r) {
        PrincipleRuleJpaEntity entity = new PrincipleRuleJpaEntity();
        entity.id = r.id();
        entity.userId = r.userId();
        entity.metric = r.metric();
        entity.threshold = r.threshold();
        entity.enabled = r.enabled();
        entity.description = r.description();
        entity.createdAt = r.createdAt();
        entity.updatedAt = r.updatedAt();
        entity.version = r.version();
        return entity;
    }

    public PrincipleRule toDomain() {
        return PrincipleRule.reconstitute(id, userId, metric, threshold, enabled, description,
                createdAt, updatedAt, version);
    }
}
```

`PrincipleRuleJpaRepository.java`：

```java
package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrincipleRuleJpaRepository extends JpaRepository<PrincipleRuleJpaEntity, Long> {
    List<PrincipleRuleJpaEntity> findByUserIdOrderByMetricAsc(Long userId);
    Optional<PrincipleRuleJpaEntity> findByIdAndUserId(Long id, Long userId);
}
```

`PrincipleRuleRepositoryImpl.java`：

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class PrincipleRuleRepositoryImpl implements PrincipleRuleRepository {

    private final PrincipleRuleJpaRepository jpa;

    public PrincipleRuleRepositoryImpl(PrincipleRuleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<PrincipleRule> findByUserId(Long userId) {
        return jpa.findByUserIdOrderByMetricAsc(userId).stream().map(PrincipleRuleJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<PrincipleRule> findByIdAndUserId(Long id, Long userId) {
        return jpa.findByIdAndUserId(id, userId).map(PrincipleRuleJpaEntity::toDomain);
    }

    @Override
    public PrincipleRule save(PrincipleRule rule) {
        return jpa.saveAndFlush(PrincipleRuleJpaEntity.fromDomain(rule)).toDomain();
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }
}
```

> 规则用 `saveAndFlush`：让 UNIQUE 冲突在事务内即时抛出（Task 5 的 `DataIntegrityViolationException` 翻译路径依赖此行为）。

- [ ] **Step 4: 跑集成测试确认通过**

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.infrastructure.persistence.PrincipleRuleRepositoryImplTest" --console=plain`
Expected: PASS（2 tests）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/infrastructure/persistence/PrincipleRule* backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/PrincipleRuleRepositoryImplTest.java
git commit -m "feat(wiki): principle_rule JPA 持久化（saveAndFlush 即时暴露 UNIQUE 冲突）"
```

---

### Task 9: DDD 分包规范文档更新与 P1 检查点

**Files:**
- Modify: `docs/technology/conventions/01-后端DDD分包规范.md`（域清单 12→13）

**Interfaces:**
- Consumes: Task 1~8 全部产物
- Produces: P1 完成状态（P2/P3 的后端依赖就绪）

- [ ] **Step 1: 更新 DDD 规范文档域清单**

在《01-后端DDD分包规范》的域清单（现 12 个域：allocation, analytics, conversation, industry, journal, market, mcp, portfolio, screening, skill, user, valuation）中追加 `wiki`（投资知识库：条目 + 原则规则聚合），按文档既有行格式登记；说明一句「MS-11 交付，详见 features/wiki-knowledge-base/」。

- [ ] **Step 2: 全量检查点**

Run: `make test-backend && make test-backend-integration`
Expected: 全绿（`gradlew check` 含单测/ArchUnit/JaCoCo ≥80%；integrationTest 含新 4 个集成用例）。ArchUnit 无需登记（层通配），若意外红了按报错的规则名修正包依赖方向，不放宽白名单。

- [ ] **Step 3: 提交**

```bash
git add docs/technology/conventions/01-后端DDD分包规范.md
git commit -m "docs(wiki): DDD 分包规范域清单登记 wiki 域（12→13）"
```
