package com.portfolio.invest.application.portfolio;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** wire 命令 Bean Validation：手续费非负、证券代码/名称对齐 DB VARCHAR(16/64)。 */
class TradeCommandValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static LocalDate day() {
        return LocalDate.of(2026, 8, 29);
    }

    private static boolean hasViolationOn(Set<? extends ConstraintViolation<?>> violations, String path) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(path));
    }

    @Test
    void 买入负手续费被拒() {
        var cmd = new BuyCommand(1L, "600519", "贵州茅台", day(),
                new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("-5"));
        assertThat(hasViolationOn(validator.validate(cmd), "fee")).isTrue();
    }

    @Test
    void 买入超长股票代码被拒() {
        var cmd = new BuyCommand(1L, "60051900000000000", "贵州茅台", day(),
                new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("0"));
        assertThat(hasViolationOn(validator.validate(cmd), "stockCode")).isTrue();
    }

    @Test
    void 买入超长股票名称被拒() {
        var cmd = new BuyCommand(1L, "600519", "贵".repeat(65), day(),
                new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("0"));
        assertThat(hasViolationOn(validator.validate(cmd), "stockName")).isTrue();
    }

    @Test
    void 卖出负手续费被拒() {
        var cmd = new SellCommand(1L, day(), new BigDecimal("120"), new BigDecimal("10"), new BigDecimal("-1"));
        assertThat(hasViolationOn(validator.validate(cmd), "fee")).isTrue();
    }

    @Test
    void 编辑交易负手续费被拒() {
        var cmd = new EditTradeCommand(day(), new BigDecimal("120"), new BigDecimal("10"), new BigDecimal("-1"), 1L);
        assertThat(hasViolationOn(validator.validate(cmd), "fee")).isTrue();
    }
}
