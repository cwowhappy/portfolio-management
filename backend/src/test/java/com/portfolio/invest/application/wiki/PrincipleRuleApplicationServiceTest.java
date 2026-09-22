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
