package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.AssetClass;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 权重 wire 层小数位限制：对齐 DB NUMERIC(18,4)，避免入库静默四舍五入破坏和=100 不变量。 */
class WeightInputValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static boolean hasViolationOn(Set<? extends jakarta.validation.ConstraintViolation<?>> violations, String path) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(path));
    }

    @Test
    void 超过四位小数的权重被拒() {
        var w = new WeightInput(AssetClass.STOCK, new BigDecimal("33.33333"));
        assertThat(hasViolationOn(validator.validate(w), "weight")).isTrue();
    }

    @Test
    void 四位小数以内的权重合法() {
        var w = new WeightInput(AssetClass.STOCK, new BigDecimal("33.3333"));
        assertThat(hasViolationOn(validator.validate(w), "weight")).isFalse();
    }
}
