package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F10/F12 检查留痕（NFR-2 append-only）：OVERRIDDEN 必填理由（空白即拒）、CONFIRMED 理由置 null、
 * 无任何变更方法（反射断言）、items 不可修改。
 */
class CheckRecordTest {

    private static List<CheckItemResult> sampleItems() {
        return List.of(
                new CheckItemResult(DisciplineCheckService.METRIC_STOCK_PE_MAX,
                        new BigDecimal("30"), new BigDecimal("35"), CheckOutcome.HIT),
                new CheckItemResult(DisciplineCheckService.F01_BUY_CONDITIONS,
                        null, null, CheckOutcome.PASS));
    }

    @DisplayName("OVERRIDDEN 缺理由（null/空串/空白）→ OVERRIDE_REASON_REQUIRED")
    @Test
    void givenOverriddenWithoutReason_whenCreate_thenThrowOverrideReasonRequired() {
        for (String blank : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> CheckRecord.create(1L, CheckType.BUY, sampleItems(),
                    CheckResult.OVERRIDDEN, blank))
                    .as("理由=%s", blank)
                    .isInstanceOfSatisfying(ResearchException.class,
                            e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.OVERRIDE_REASON_REQUIRED));
        }
    }

    @DisplayName("OVERRIDDEN 带理由 → 留痕成功且理由原样保留")
    @Test
    void givenOverriddenWithReason_whenCreate_thenReasonKept() {
        var record = CheckRecord.create(1L, CheckType.SELL, sampleItems(),
                CheckResult.OVERRIDDEN, "板块逻辑变化，减仓观察");
        assertThat(record.id()).isNull();
        assertThat(record.projectId()).isEqualTo(1L);
        assertThat(record.checkType()).isEqualTo(CheckType.SELL);
        assertThat(record.result()).isEqualTo(CheckResult.OVERRIDDEN);
        assertThat(record.overrideReason()).isEqualTo("板块逻辑变化，减仓观察");
        assertThat(record.createdAt()).isNotNull();
        assertThat(record.items()).hasSize(2);
    }

    @DisplayName("CONFIRMED 时传入的理由忽略置 null")
    @Test
    void givenConfirmedWithReason_whenCreate_thenReasonNulled() {
        var record = CheckRecord.create(1L, CheckType.BUY, sampleItems(),
                CheckResult.CONFIRMED, "多余的理由");
        assertThat(record.result()).isEqualTo(CheckResult.CONFIRMED);
        assertThat(record.overrideReason()).isNull();
    }

    @DisplayName("OVERRIDDEN 理由超 500 字 → OVERRIDE_REASON_REQUIRED（DB VARCHAR(500) 前置防线）")
    @Test
    void givenOverriddenReasonTooLong_whenCreate_thenThrow() {
        assertThatThrownBy(() -> CheckRecord.create(1L, CheckType.BUY, sampleItems(),
                CheckResult.OVERRIDDEN, "长".repeat(501)))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.OVERRIDE_REASON_REQUIRED));
    }

    @DisplayName("必填缺失：项目/类型/结论/检查项 → 对应错误码")
    @Test
    void givenMissingRequiredParts_whenCreate_thenThrow() {
        assertThatThrownBy(() -> CheckRecord.create(null, CheckType.BUY, sampleItems(), CheckResult.CONFIRMED, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.PROJECT_REQUIRED));
        assertThatThrownBy(() -> CheckRecord.create(1L, null, sampleItems(), CheckResult.CONFIRMED, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CHECK_TYPE_REQUIRED));
        assertThatThrownBy(() -> CheckRecord.create(1L, CheckType.BUY, null, CheckResult.CONFIRMED, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CHECK_ITEMS_REQUIRED));
        assertThatThrownBy(() -> CheckRecord.create(1L, CheckType.BUY, List.of(), CheckResult.CONFIRMED, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CHECK_ITEMS_REQUIRED));
        assertThatThrownBy(() -> CheckRecord.create(1L, CheckType.BUY, sampleItems(), null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CHECK_RESULT_REQUIRED));
    }

    @DisplayName("append-only：类上无 set/update/replace 开头方法，items 快照不可修改")
    @Test
    void givenCheckRecordClass_whenInspect_thenNoMutatorPath() {
        var mutators = Arrays.stream(CheckRecord.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set") || name.startsWith("update") || name.startsWith("replace"))
                .toList();
        assertThat(mutators).isEmpty();

        var record = CheckRecord.create(1L, CheckType.BUY, sampleItems(), CheckResult.CONFIRMED, null);
        assertThatThrownBy(() -> record.items().add(new CheckItemResult("x", null, null, CheckOutcome.UNSET)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @DisplayName("reconstitute 持久化还原：全字段原样（含 OVERRIDDEN 理由）")
    @Test
    void givenPersistedRow_whenReconstitute_thenFieldsPreserved() {
        Instant created = Instant.parse("2026-09-28T09:30:00Z");
        var record = CheckRecord.reconstitute(7L, 1L, CheckType.REDUCE, sampleItems(),
                CheckResult.OVERRIDDEN, "越线但行业景气拐点确认", created);
        assertThat(record.id()).isEqualTo(7L);
        assertThat(record.projectId()).isEqualTo(1L);
        assertThat(record.checkType()).isEqualTo(CheckType.REDUCE);
        assertThat(record.items()).hasSize(2);
        assertThat(record.result()).isEqualTo(CheckResult.OVERRIDDEN);
        assertThat(record.overrideReason()).isEqualTo("越线但行业景气拐点确认");
        assertThat(record.createdAt()).isEqualTo(created);
    }
}
