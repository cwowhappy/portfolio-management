package com.portfolio.invest.application.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.allocation.AllocationErrorCode;
import com.portfolio.invest.domain.allocation.AllocationException;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.PlanSource;
import com.portfolio.invest.support.ConcurrencyTestSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MS-28 P2-B6 并发激活回归：activatePlan 的 deactivateAll→save(activate) 存在竞态窗口，
 * 两请求同时为同用户激活不同方案时由唯一偏索引 ux_allocation_plan_user_active 兜底。
 * 修复前兜底异常经 DataIntegrityViolation 全局分支冒 400 INVALID_DATA；修复后须在
 * 事务内转译为 AllocationException(CONFLICT) → 409。真实 PG 端到端验证（照
 * RegistrationConcurrencyIntegrationTest 先例，单元侧 mock 见 AllocationApplicationServiceTest）。
 */
@SpringBootTest
class AllocationConcurrencyIntegrationTest extends ConcurrencyTestSupport {

    private static final long USER_ID = SENTINEL_ID_9003;

    @Autowired
    private AllocationApplicationService service;

    private List<Long> planIds;

    /** allocation_plan.user_id 外键引用 app_user(id)，先植入提交态用户行；再造两份待激活方案。 */
    @BeforeEach
    void seedUserAndPlans() {
        insertUser(USER_ID, "allocation-concurrency");
        planIds = new ArrayList<>(List.of(
                service.createPlan(USER_ID, new CreatePlanCommand("稳健", PlanSource.CUSTOM, weights(), null)).id(),
                service.createPlan(USER_ID, new CreatePlanCommand("进取", PlanSource.CUSTOM, weights(), null)).id()));
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM allocation_plan WHERE user_id = ?", USER_ID); // 权重 ON DELETE CASCADE
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", USER_ID);
    }

    @DisplayName("同用户并发激活两方案恰一成功，另一方收到CONFLICT")
    @Test
    void givenTwoInactivePlans_whenConcurrentActivate_thenExactlyOneSucceedsOtherConflicts() throws Exception {
        AtomicInteger successes = new AtomicInteger();
        List<AllocationException> conflicts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger seq = new AtomicInteger();

        // race 任一 Callable 抛非 AllocationException（如未转译的约束违例）会在 future.get 原样炸测试
        race(2, () -> {
            Long planId = planIds.get(seq.getAndIncrement());
            try {
                service.activatePlan(USER_ID, planId);
                successes.incrementAndGet();
            } catch (AllocationException e) {
                conflicts.add(e);
            }
            return null;
        });

        assertThat(successes.get()).as("并发激活恰一成功").isEqualTo(1);
        assertThat(conflicts).as("败者收到域冲突异常").hasSize(1);
        assertThat(conflicts.get(0).code()).isEqualTo(AllocationErrorCode.CONFLICT);

        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM allocation_plan WHERE user_id = ? AND active", Integer.class, USER_ID);
        assertThat(activeCount).as("终态每用户至多一个生效方案").isEqualTo(1);
    }

    private static List<WeightInput> weights() {
        return List.of(new WeightInput(AssetClass.STOCK, new BigDecimal("60")),
                new WeightInput(AssetClass.BOND, new BigDecimal("40")));
    }
}
