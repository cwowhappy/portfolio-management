# P2 journal 用例与接口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `application/journal` 实现用例编排（记录 CRUD + tradeId 联动校验 + 时间线聚合），暴露 `/api/journal/**` 接口，并把 `JournalException` 映射到 HTTP 状态。

**Architecture:** `JournalApplicationService` 注入 `JournalEntryRepository`（P1）与 `PortfolioRepository`（domain.portfolio 读端口）。`tradeId` 联动通过 trade → position → portfolio → user 多跳校验归属并回查 `stockCode`/`stockName`；时间线把 journal 记录（`eventDate`）与 M08 交易/分红（`tradeDate`/`exDate`）合并为按事件日倒序的 7 类事件流。`application.journal → domain.portfolio` 落在 ArchUnit `application..` 白名单内，无需改 `PackageConventionsTest`。

**Tech Stack:** Java 21 · Spring Boot 4 · Spring MVC · Mockito/AssertJ · Cucumber（zh-CN）

**Spec:** `features/journal/01-requirement/需求规格说明.md`

## Global Constraints

- 只读方法不挂 `@Transactional`；变更方法（create/update/delete）挂 `@Transactional`。
- 按用户隔离：所有按 id 查询都用 `findByIdAndUserId`，非本人抛 `JournalException(NOT_FOUND)`（不泄露存在性）；非本人交易抛 `JournalException(TRADE_NOT_FOUND)`（同样 404，不泄露存在性）。
- Bean Validation 只做结构校验（type/title/content/eventDate 非空），业务校验交给领域（P1 `validate`）。
- 时间线为读侧聚合，不挂事务、不做分页（扁平倒序 + `from`/`to` 过滤）。
- 覆盖率 ≥80%；`/api/journal/**` 由 `SecurityConfig` 的 `anyRequest().authenticated()` 自动保护，无需改安全配置。

---

### Task 1: 视图 DTO 与命令

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/journal/JournalEntryView.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/journal/CreateJournalEntryCommand.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/journal/UpdateJournalEntryCommand.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/journal/TimelineEventType.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/journal/TimelineEventView.java`

**Interfaces:**
- Consumes: `JournalEntry`/`JournalEntryType`/`PeriodType`（P1）。
- Produces: 各 record 及 `from` 工厂（供服务层与控制器用）。

- [ ] **Step 1: 写 DTO 与命令**

`JournalEntryView.java`:
```java
package com.portfolio.invest.application.journal;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.PeriodType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record JournalEntryView(
        Long id, JournalEntryType type, String stockCode, String stockName, Long tradeId,
        String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
        PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
        LocalDate eventDate, Instant createdAt, Instant updatedAt) {

    public static JournalEntryView from(JournalEntry e) {
        return new JournalEntryView(e.id(), e.type(), e.stockCode(), e.stockName(), e.tradeId(),
                e.title(), e.content(), e.targetPrice(), e.stopLoss(),
                e.periodType(), e.periodStart(), e.periodEnd(),
                e.eventDate(), e.createdAt(), e.updatedAt());
    }
}
```

`CreateJournalEntryCommand.java`:
```java
package com.portfolio.invest.application.journal;

import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.PeriodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateJournalEntryCommand(
        @NotNull JournalEntryType type,
        String stockCode,
        String stockName,
        Long tradeId,
        @NotBlank String title,
        @NotBlank String content,
        BigDecimal targetPrice,
        BigDecimal stopLoss,
        PeriodType periodType,
        LocalDate periodStart,
        LocalDate periodEnd,
        @NotNull LocalDate eventDate
) {}
```

`UpdateJournalEntryCommand.java`:
```java
package com.portfolio.invest.application.journal;

import com.portfolio.invest.domain.journal.PeriodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateJournalEntryCommand(
        String stockCode,
        String stockName,
        Long tradeId,
        @NotBlank String title,
        @NotBlank String content,
        BigDecimal targetPrice,
        BigDecimal stopLoss,
        PeriodType periodType,
        LocalDate periodStart,
        LocalDate periodEnd,
        @NotNull LocalDate eventDate
) {}
```

`TimelineEventType.java`:
```java
package com.portfolio.invest.application.journal;

/** 时间线事件类型：M08 三类（买/卖/分红）+ journal 四类。 */
public enum TimelineEventType {
    BUY, SELL, DIVIDEND, BUY_MEMO, SELL_MEMO, RESEARCH_NOTE, REVIEW
}
```

`TimelineEventView.java`:
```java
package com.portfolio.invest.application.journal;

import java.time.LocalDate;

public record TimelineEventView(
        TimelineEventType type,
        LocalDate date,
        String title,
        String description,
        String stockCode,
        String stockName,
        Long refId,
        String refType
) {}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/application/journal/
git commit -m "feat(journal): 记录视图 DTO 与命令"
```

---

### Task 2: 记录用例服务（CRUD + 联动 + 时间线）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/journal/JournalApplicationService.java`

**Interfaces:**
- Consumes: `JournalEntryRepository`（P1）、`PortfolioRepository`（domain.portfolio，已有）。
- Produces: `entries(userId, type)`、`createEntry(userId, cmd)`、`getEntry(userId, id)`、`updateEntry(userId, id, cmd)`、`deleteEntry(userId, id)`、`timeline(userId, from, to)`。

- [ ] **Step 1: 实现**

```java
package com.portfolio.invest.application.journal;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.JournalErrorCode;
import com.portfolio.invest.domain.journal.JournalException;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JournalApplicationService {

    private final JournalEntryRepository repository;
    private final PortfolioRepository portfolioRepository;

    public JournalApplicationService(JournalEntryRepository repository,
                                     PortfolioRepository portfolioRepository) {
        this.repository = repository;
        this.portfolioRepository = portfolioRepository;
    }

    public List<JournalEntryView> entries(Long userId, JournalEntryType type) {
        return repository.findByUserId(userId, type).stream().map(JournalEntryView::from).toList();
    }

    @Transactional
    public JournalEntryView createEntry(Long userId, CreateJournalEntryCommand cmd) {
        ResolvedStock resolved = resolveTrade(userId, cmd.tradeId(), cmd.stockCode(), cmd.stockName());
        JournalEntry entry = JournalEntry.create(userId, cmd.type(), resolved.stockCode(), resolved.stockName(),
                cmd.tradeId(), cmd.title().trim(), cmd.content(), cmd.targetPrice(), cmd.stopLoss(),
                cmd.periodType(), cmd.periodStart(), cmd.periodEnd(), cmd.eventDate(), Instant.now());
        return JournalEntryView.from(repository.save(entry));
    }

    public JournalEntryView getEntry(Long userId, Long entryId) {
        return JournalEntryView.from(requireEntry(userId, entryId));
    }

    @Transactional
    public JournalEntryView updateEntry(Long userId, Long entryId, UpdateJournalEntryCommand cmd) {
        JournalEntry existing = requireEntry(userId, entryId);
        ResolvedStock resolved = resolveTrade(userId, cmd.tradeId(), cmd.stockCode(), cmd.stockName());
        JournalEntry updated = existing.update(resolved.stockCode(), resolved.stockName(),
                cmd.tradeId(), cmd.title().trim(), cmd.content(), cmd.targetPrice(), cmd.stopLoss(),
                cmd.periodType(), cmd.periodStart(), cmd.periodEnd(), cmd.eventDate());
        return JournalEntryView.from(repository.save(updated));
    }

    @Transactional
    public void deleteEntry(Long userId, Long entryId) {
        requireEntry(userId, entryId);
        repository.deleteById(entryId);
    }

    public List<TimelineEventView> timeline(Long userId, LocalDate from, LocalDate to) {
        List<TimelineEventView> events = new ArrayList<>();

        for (JournalEntry e : repository.findByUserId(userId, null)) {
            if (inRange(e.eventDate(), from, to)) {
                events.add(journalEvent(e));
            }
        }

        var portfolio = portfolioRepository.findPortfolioByUserId(userId);
        if (portfolio.isPresent()) {
            for (Position pos : portfolioRepository.findPositionsByPortfolioId(portfolio.get().id())) {
                for (Trade t : portfolioRepository.findTradesByPositionId(pos.id())) {
                    if (inRange(t.tradeDate(), from, to)) {
                        events.add(tradeEvent(t, pos));
                    }
                }
                for (Dividend d : portfolioRepository.findDividendsByPositionId(pos.id())) {
                    if (inRange(d.exDate(), from, to)) {
                        events.add(dividendEvent(d, pos));
                    }
                }
            }
        }

        return events.stream()
                .sorted(Comparator.comparing(TimelineEventView::date).reversed())
                .toList();
    }

    private JournalEntry requireEntry(Long userId, Long entryId) {
        return repository.findByIdAndUserId(entryId, userId)
                .orElseThrow(() -> new JournalException(JournalErrorCode.NOT_FOUND, "记录不存在"));
    }

    /** 校验交易归属当前用户并回查股票；无 tradeId 时直接用客户端股票信息。 */
    private ResolvedStock resolveTrade(Long userId, Long tradeId, String clientStockCode, String clientStockName) {
        if (tradeId == null) {
            return new ResolvedStock(clientStockCode, clientStockName);
        }
        Portfolio portfolio = portfolioRepository.findPortfolioByUserId(userId)
                .orElseThrow(() -> new JournalException(JournalErrorCode.TRADE_NOT_FOUND, "关联交易不存在"));
        Trade trade = portfolioRepository.findTradeById(tradeId)
                .orElseThrow(() -> new JournalException(JournalErrorCode.TRADE_NOT_FOUND, "关联交易不存在"));
        Position position = portfolioRepository.findPositionByIdAndPortfolioId(trade.positionId(), portfolio.id())
                .orElseThrow(() -> new JournalException(JournalErrorCode.TRADE_NOT_FOUND, "关联交易不存在"));
        if (clientStockCode != null && !clientStockCode.equals(position.stockCode())) {
            throw new JournalException(JournalErrorCode.INVALID_INPUT, "股票代码与关联交易不一致");
        }
        return new ResolvedStock(position.stockCode(), position.stockName());
    }

    private static TimelineEventView journalEvent(JournalEntry e) {
        return new TimelineEventView(journalEventType(e.type()), e.eventDate(), e.title(),
                truncate(e.content(), 80), e.stockCode(), e.stockName(), e.id(), "JOURNAL");
    }

    private static TimelineEventView tradeEvent(Trade t, Position pos) {
        TimelineEventType type = t.type() == TradeType.BUY ? TimelineEventType.BUY : TimelineEventType.SELL;
        String action = t.type() == TradeType.BUY ? "买入" : "卖出";
        return new TimelineEventView(type, t.tradeDate(), pos.stockName(),
                action + " " + trimNum(t.quantity()) + " 股", pos.stockCode(), pos.stockName(), t.id(), "TRADE");
    }

    private static TimelineEventView dividendEvent(Dividend d, Position pos) {
        String desc = d.type() == DividendType.CASH
                ? "现金分红 " + trimNum(d.cashPerShare()) + " 元/股"
                : "送股 " + trimNum(d.stockRatio()) + " 股/股";
        return new TimelineEventView(TimelineEventType.DIVIDEND, d.exDate(), pos.stockName(),
                desc, pos.stockCode(), pos.stockName(), d.id(), "DIVIDEND");
    }

    private static TimelineEventType journalEventType(JournalEntryType t) {
        return switch (t) {
            case BUY_MEMO -> TimelineEventType.BUY_MEMO;
            case SELL_MEMO -> TimelineEventType.SELL_MEMO;
            case RESEARCH_NOTE -> TimelineEventType.RESEARCH_NOTE;
            case REVIEW -> TimelineEventType.REVIEW;
        };
    }

    private static boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        if (from != null && date.isBefore(from)) {
            return false;
        }
        if (to != null && date.isAfter(to)) {
            return false;
        }
        return true;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String trimNum(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private record ResolvedStock(String stockCode, String stockName) {}
}
```

- [ ] **Step 2: 编译确认**

Run: `cd backend && ./gradlew compileJava --console=plain`
Expected: 编译通过（依赖 `PortfolioRepository` 已存在）。

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/application/journal/JournalApplicationService.java
git commit -m "feat(journal): 记录用例服务（CRUD/tradeId 联动/时间线聚合）"
```

---

### Task 3: 控制器与全局异常映射

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/web/JournalController.java`
- Modify: `backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java`（新增 `JournalException` 处理器）

**Interfaces:**
- Consumes: `JournalApplicationService`（Task 2）。
- Produces: `JournalController`（`@RestController @RequestMapping("/api/journal")`）。

- [ ] **Step 1: 实现控制器**

```java
package com.portfolio.invest.web;

import com.portfolio.invest.application.journal.CreateJournalEntryCommand;
import com.portfolio.invest.application.journal.JournalApplicationService;
import com.portfolio.invest.application.journal.JournalEntryView;
import com.portfolio.invest.application.journal.TimelineEventView;
import com.portfolio.invest.application.journal.UpdateJournalEntryCommand;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
@RequestMapping("/api/journal")
public class JournalController {

    private final JournalApplicationService service;

    public JournalController(JournalApplicationService service) {
        this.service = service;
    }

    @GetMapping("/entries")
    public List<JournalEntryView> entries(Authentication auth,
                                          @RequestParam(required = false) JournalEntryType type) {
        return service.entries(currentUserId(auth), type);
    }

    @PostMapping("/entries")
    public ResponseEntity<JournalEntryView> createEntry(Authentication auth,
                                                        @Valid @RequestBody CreateJournalEntryCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEntry(currentUserId(auth), cmd));
    }

    @GetMapping("/entries/{entryId}")
    public JournalEntryView getEntry(Authentication auth, @PathVariable Long entryId) {
        return service.getEntry(currentUserId(auth), entryId);
    }

    @PutMapping("/entries/{entryId}")
    public JournalEntryView updateEntry(Authentication auth, @PathVariable Long entryId,
                                        @Valid @RequestBody UpdateJournalEntryCommand cmd) {
        return service.updateEntry(currentUserId(auth), entryId, cmd);
    }

    @DeleteMapping("/entries/{entryId}")
    public ResponseEntity<Void> deleteEntry(Authentication auth, @PathVariable Long entryId) {
        service.deleteEntry(currentUserId(auth), entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/timeline")
    public List<TimelineEventView> timeline(Authentication auth,
                                            @RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to) {
        return service.timeline(currentUserId(auth), from, to);
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
```

- [ ] **Step 2: 扩展全局异常映射**

在 `GlobalExceptionHandler` 的 `screening` 处理器后新增：

```java
@ExceptionHandler(com.portfolio.invest.domain.journal.JournalException.class)
public ResponseEntity<ApiError> journal(com.portfolio.invest.domain.journal.JournalException e) {
    HttpStatus status = switch (e.code()) {
        case com.portfolio.invest.domain.journal.JournalErrorCode.NOT_FOUND,
             com.portfolio.invest.domain.journal.JournalErrorCode.TRADE_NOT_FOUND -> HttpStatus.NOT_FOUND;
        default -> HttpStatus.BAD_REQUEST;
    };
    return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage()));
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/web/JournalController.java \
        backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java
git commit -m "feat(journal): 记录控制器与异常映射"
```

---

### Task 4: 服务单元测试

**Files:**
- Test: `backend/src/test/java/com/portfolio/invest/application/journal/JournalApplicationServiceTest.java`

**Interfaces:**
- Consumes: `JournalApplicationService`（Task 2）、`JournalEntry`（P1）、`Position`/`Trade`/`Dividend`/`Portfolio`（已有）。

- [ ] **Step 1: 写测试**

```java
package com.portfolio.invest.application.journal;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.JournalErrorCode;
import com.portfolio.invest.domain.journal.JournalException;
import com.portfolio.invest.domain.journal.PeriodType;
import com.portfolio.invest.domain.portfolio.CostMethod;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JournalApplicationServiceTest {

    private final JournalEntryRepository repo = mock(JournalEntryRepository.class);
    private final PortfolioRepository portfolioRepo = mock(PortfolioRepository.class);
    private JournalApplicationService service;

    @BeforeEach
    void setUp() {
        service = new JournalApplicationService(repo, portfolioRepo);
    }

    private static Position pos() {
        return Position.reconstitute(100L, 1L, 1L, "600519", "贵州茅台",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now());
    }

    private static Portfolio portfolio() {
        return Portfolio.reconstitute(1L, 1L, CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now());
    }

    private static Trade buyTrade() {
        return new Trade(10L, 100L, TradeType.BUY, LocalDate.of(2026, 8, 1),
                new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5"), Instant.now());
    }

    private static JournalEntry entry(long id) {
        return JournalEntry.reconstitute(id, 1L, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", 10L,
                "买入茅台", "理由", new BigDecimal("1800"), new BigDecimal("1400"),
                null, null, null, LocalDate.of(2026, 8, 2), Instant.now(), Instant.now());
    }

    @Test
    void 创建备忘关联交易时回查股票() {
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(portfolioRepo.findTradeById(10L)).thenReturn(Optional.of(buyTrade()));
        when(portfolioRepo.findPositionByIdAndPortfolioId(100L, 1L)).thenReturn(Optional.of(pos()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.createEntry(1L, new CreateJournalEntryCommand(JournalEntryType.BUY_MEMO,
                null, null, 10L, "买入茅台", "理由", null, null, null, null, null,
                LocalDate.of(2026, 8, 2)));

        assertThat(view.stockCode()).isEqualTo("600519");
        assertThat(view.stockName()).isEqualTo("贵州茅台");
        assertThat(view.tradeId()).isEqualTo(10L);
    }

    @Test
    void 关联非本人交易抛TRADE_NOT_FOUND() {
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(portfolioRepo.findTradeById(999L)).thenReturn(Optional.of(buyTrade()));
        when(portfolioRepo.findPositionByIdAndPortfolioId(100L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEntry(1L, new CreateJournalEntryCommand(JournalEntryType.BUY_MEMO,
                null, null, 999L, "标题", "内容", null, null, null, null, null, LocalDate.now())))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.TRADE_NOT_FOUND));
    }

    @Test
    void 客户端股票代码与交易不一致抛INVALID_INPUT() {
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(portfolioRepo.findTradeById(10L)).thenReturn(Optional.of(buyTrade()));
        when(portfolioRepo.findPositionByIdAndPortfolioId(100L, 1L)).thenReturn(Optional.of(pos()));

        assertThatThrownBy(() -> service.createEntry(1L, new CreateJournalEntryCommand(JournalEntryType.BUY_MEMO,
                "000001", null, 10L, "标题", "内容", null, null, null, null, null, LocalDate.now())))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 非本人记录抛NOT_FOUND() {
        when(repo.findByIdAndUserId(5L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteEntry(1L, 5L))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.NOT_FOUND));
    }

    @Test
    void 更新记录() {
        when(repo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(entry(7L)));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEntry(1L, 7L, new UpdateJournalEntryCommand(
                "600519", "贵州茅台", 10L, "新标题", "新内容", null, null, null, null, null,
                LocalDate.of(2026, 8, 3)));

        assertThat(view.title()).isEqualTo("新标题");
        assertThat(view.eventDate()).isEqualTo(LocalDate.of(2026, 8, 3));
    }

    @Test
    void 时间线合并journal与M08事件并按事件日倒序() {
        when(repo.findByUserId(1L, null)).thenReturn(List.of(
                entry(1L), // eventDate 2026-08-02
                JournalEntry.reconstitute(2L, 1L, JournalEntryType.REVIEW, null, null, null,
                        "复盘", "内容", null, null, PeriodType.QUARTERLY,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                        LocalDate.of(2026, 9, 30), Instant.now(), Instant.now())));
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(portfolioRepo.findPositionsByPortfolioId(1L)).thenReturn(List.of(pos()));
        when(portfolioRepo.findTradesByPositionId(100L)).thenReturn(List.of(
                new Trade(10L, 100L, TradeType.BUY, LocalDate.of(2026, 8, 1),
                        new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5"), Instant.now()),
                new Trade(11L, 100L, TradeType.SELL, LocalDate.of(2026, 8, 20),
                        new BigDecimal("1600"), new BigDecimal("50"), new BigDecimal("5"), Instant.now())));
        when(portfolioRepo.findDividendsByPositionId(100L)).thenReturn(List.of(
                new Dividend(20L, 100L, DividendType.CASH, LocalDate.of(2026, 8, 15),
                        new BigDecimal("1.5"), null, Instant.now())));

        var events = service.timeline(1L, null, null);

        assertThat(events).hasSize(5);
        assertThat(events).extracting(TimelineEventView::type).containsExactly(
                TimelineEventType.REVIEW, TimelineEventType.SELL,
                TimelineEventType.DIVIDEND, TimelineEventType.BUY_MEMO, TimelineEventType.BUY);
        assertThat(events.get(0).date()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void 时间线日期范围过滤() {
        when(repo.findByUserId(1L, null)).thenReturn(List.of(entry(1L)));
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.empty());

        assertThat(service.timeline(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1))).isEmpty();
        assertThat(service.timeline(1L, LocalDate.of(2026, 8, 2), null)).hasSize(1);
    }

    @Test
    void 删除记录() {
        when(repo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(entry(7L)));
        service.deleteEntry(1L, 7L);
        verify(repo).deleteById(7L);
    }
}
```

- [ ] **Step 2: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.journal.JournalApplicationServiceTest" --console=plain`
Expected: PASS。

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/portfolio/invest/application/journal/JournalApplicationServiceTest.java
git commit -m "test(journal): 记录用例服务单元测试"
```

---

### Task 5: 控制器切片测试

**Files:**
- Test: `backend/src/test/java/com/portfolio/invest/web/JournalControllerTest.java`

**Interfaces:**
- Consumes: `JournalController`（Task 3）。

- [ ] **Step 1: 写测试**

```java
package com.portfolio.invest.web;

import com.portfolio.invest.application.journal.CreateJournalEntryCommand;
import com.portfolio.invest.application.journal.JournalApplicationService;
import com.portfolio.invest.application.journal.JournalEntryView;
import com.portfolio.invest.application.journal.TimelineEventType;
import com.portfolio.invest.application.journal.TimelineEventView;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.JournalErrorCode;
import com.portfolio.invest.domain.journal.JournalException;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JournalControllerTest {

    private final JournalApplicationService service = mock(JournalApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new JournalController(service))
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

    @Test
    void 创建备忘返回201() throws Exception {
        when(service.createEntry(eq(1L), any(CreateJournalEntryCommand.class)))
                .thenReturn(new JournalEntryView(5L, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", null,
                        "买入茅台", "理由", null, null, null, null, null,
                        LocalDate.of(2026, 9, 2), Instant.now(), Instant.now()));
        mvc.perform(post("/api/journal/entries").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BUY_MEMO\",\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"买入茅台\",\"content\":\"理由\",\"eventDate\":\"2026-09-02\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("买入茅台"))
                .andExpect(jsonPath("$.stockCode").value("600519"));
    }

    @Test
    void 列表按类型过滤返回200() throws Exception {
        when(service.entries(1L, JournalEntryType.REVIEW)).thenReturn(List.of(
                new JournalEntryView(5L, JournalEntryType.REVIEW, null, null, null, "复盘", "内容",
                        null, null, null, null, null, LocalDate.now(), Instant.now(), Instant.now())));
        mvc.perform(get("/api/journal/entries").principal(auth()).param("type", "REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("REVIEW"));
    }

    @Test
    void 时间线返回200() throws Exception {
        when(service.timeline(1L, null, null)).thenReturn(List.of(
                new TimelineEventView(TimelineEventType.BUY, LocalDate.of(2026, 8, 1), "贵州茅台",
                        "买入 100 股", "600519", "贵州茅台", 10L, "TRADE")));
        mvc.perform(get("/api/journal/timeline").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("BUY"))
                .andExpect(jsonPath("$[0].stockCode").value("600519"));
    }

    @Test
    void 非本人记录映射404() throws Exception {
        when(service.getEntry(1L, 99L)).thenThrow(new JournalException(JournalErrorCode.NOT_FOUND, "记录不存在"));
        mvc.perform(get("/api/journal/entries/99").principal(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 关联交易不存在映射404() throws Exception {
        when(service.createEntry(eq(1L), any(CreateJournalEntryCommand.class)))
                .thenThrow(new JournalException(JournalErrorCode.TRADE_NOT_FOUND, "关联交易不存在"));
        mvc.perform(post("/api/journal/entries").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BUY_MEMO\",\"tradeId\":999,\"title\":\"x\",\"content\":\"y\",\"eventDate\":\"2026-09-02\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRADE_NOT_FOUND"));
    }
}
```

- [ ] **Step 2: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.web.JournalControllerTest" --console=plain`
Expected: PASS。

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/portfolio/invest/web/JournalControllerTest.java
git commit -m "test(journal): 记录控制器切片测试"
```

---

### Task 6: BDD 中文场景（备忘联动 + 时间线聚合）

**Files:**
- Create: `backend/src/bdd/resources/features/journal.feature`
- Create: `backend/src/bdd/java/com/portfolio/invest/bdd/steps/JournalSteps.java`
- Modify: `backend/src/bdd/java/com/portfolio/invest/bdd/steps/ScenarioContext.java`（新增 `journalEntryId` / `tradeId` getter/setter）

**Interfaces:**
- Consumes: `JournalApplicationService`（Task 2）、`PortfolioRepository`（读 tradeId）、`ScenarioContext`（已有）。
- 沿用 `CucumberSpringConfig` 的 `@MockitoBean` 行情 mock 与 `PostgresTestSupport`（真实 PG）；复用 `PortfolioSteps` 的 `已审核用户` / `创建账户分组` / `以...买入...` 步骤。

- [ ] **Step 1: 扩展 ScenarioContext**

在 `ScenarioContext` 中新增字段与 getter/setter（须经方法访问，scenario scope 代理拦截方法）：

```java
private Long journalEntryId;
private Long tradeId;

public Long getJournalEntryId() { return journalEntryId; }
public void setJournalEntryId(Long journalEntryId) { this.journalEntryId = journalEntryId; }
public Long getTradeId() { return tradeId; }
public void setTradeId(Long tradeId) { this.tradeId = tradeId; }
```

- [ ] **Step 2: 写 feature**

`journal.feature`:
```gherkin
# language: zh-CN
功能: 投资决策记录
  用户把买卖决策与复盘沉淀为结构化记录，备忘可关联持仓交易，时间线统一展示。

  场景: 创建买入备忘并关联交易
    假如 已审核用户 "bdd_journal"
    而且 创建账户分组 "A股"
    当 以 1500 元买入 100 股 "贵州茅台"（代码 600519，手续费 5 元）
    而且 记录该持仓最近一笔买入交易的 ID
    当 创建买入备忘 "买入茅台" 关联该交易，目标价 1800，止损价 1400
    那么 该用户应有 1 条记录
    而且 该备忘的股票代码为 600519，股票名为 贵州茅台
    而且 时间线应包含 BUY 与 BUY_MEMO 两类事件

  场景: 关联不存在的交易被拒绝
    假如 已审核用户 "bdd_journal2"
    当 创建买入备忘 "买入茅台" 关联交易 999999
    那么 应抛出 TRADE_NOT_FOUND 错误
```

- [ ] **Step 3: 写步骤**

```java
package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.application.journal.CreateJournalEntryCommand;
import com.portfolio.invest.application.journal.JournalApplicationService;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.JournalException;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;

public class JournalSteps {

    @Autowired
    JournalApplicationService journalService;

    @Autowired
    PortfolioRepository portfolioRepository;

    @Autowired
    ScenarioContext ctx;

    @当("记录该持仓最近一笔买入交易的 ID")
    public void 记录交易ID() {
        var trades = portfolioRepository.findTradesByPositionId(ctx.getPositionId());
        assertThat(trades).isNotEmpty();
        ctx.setTradeId(trades.get(0).id());
    }

    @当("创建买入备忘 {string} 关联该交易，目标价 {bigdecimal}，止损价 {bigdecimal}")
    public void 创建关联备忘(String title, BigDecimal targetPrice, BigDecimal stopLoss) {
        var view = journalService.createEntry(ctx.getUserId(), new CreateJournalEntryCommand(
                JournalEntryType.BUY_MEMO, null, null, ctx.getTradeId(), title, "理由",
                targetPrice, stopLoss, null, null, null, LocalDate.now()));
        ctx.setJournalEntryId(view.id());
    }

    @当("创建买入备忘 {string} 关联交易 {int}")
    public void 创建关联不存在交易(String title, int tradeId) {
        assertThatThrownBy(() -> journalService.createEntry(ctx.getUserId(), new CreateJournalEntryCommand(
                JournalEntryType.BUY_MEMO, null, null, (long) tradeId, title, "理由",
                null, null, null, null, null, LocalDate.now())))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> ctx.setJournalErrorCode(e.code()));
    }

    @那么("该用户应有 {int} 条记录")
    public void 记录数(int count) {
        assertThat(journalService.entries(ctx.getUserId(), null)).hasSize(count);
    }

    @那么("该备忘的股票代码为 {string}，股票名为 {string}")
    public void 备忘股票(String stockCode, String stockName) {
        var view = journalService.getEntry(ctx.getUserId(), ctx.getJournalEntryId());
        assertThat(view.stockCode()).isEqualTo(stockCode);
        assertThat(view.stockName()).isEqualTo(stockName);
    }

    @那么("时间线应包含 {string} 与 {string} 两类事件")
    public void 时间线包含(String t1, String t2) {
        var types = journalService.timeline(ctx.getUserId(), null, null).stream()
                .map(e -> e.type().name())
                .toList();
        assertThat(types).contains(t1, t2);
    }

    @那么("应抛出 {string} 错误")
    public void 应抛错误(String code) {
        assertThat(ctx.getJournalErrorCode()).isEqualTo(code);
    }
}
```

- [ ] **Step 4: 扩展 ScenarioContext（journalErrorCode 字段）**

在 Task 1 的 ScenarioContext 扩展基础上再加：

```java
private String journalErrorCode;

public String getJournalErrorCode() { return journalErrorCode; }
public void setJournalErrorCode(String journalErrorCode) { this.journalErrorCode = journalErrorCode; }
```

- [ ] **Step 5: 跑 BDD 确认通过**

Run: `cd backend && ./gradlew bdd --console=plain`（或 `make test-backend-bdd`）
Expected: `journal.feature` 两场景 PASS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/bdd/resources/features/journal.feature \
        backend/src/bdd/java/com/portfolio/invest/bdd/steps/JournalSteps.java \
        backend/src/bdd/java/com/portfolio/invest/bdd/steps/ScenarioContext.java
git commit -m "test(journal): 投资决策记录 BDD 中文场景"
```

---

## P2 完成验证

```bash
make test-backend-unit          # 服务单测 + 控制器切片 + 架构测试
make test-backend-integration   # P1 仓库集成测试仍绿
make test-backend-bdd           # journal.feature 通过
```

确认：`application.journal → domain.portfolio` 未触发 ArchUnit 反向依赖（`application..` 白名单内）。
