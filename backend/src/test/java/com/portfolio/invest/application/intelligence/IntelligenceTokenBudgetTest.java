package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.config.InvestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 情报域当日 input token 护栏（D16，进程内累计、跨日重置）：限额内放行、超限拒批、
 * 恰达限不超、已超后不再累计、Asia/Shanghai 自然日重置（P2 公告/P3 政策抽取共享本组件）。
 */
class IntelligenceTokenBudgetTest {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    @Test
    @DisplayName("给定累计未达限额，when连续tryAcquire，then全部放行且usedToday累加")
    void givenUnderLimit_whenTryAcquire_thenAllGrantedAndAccumulated() {
        IntelligenceTokenBudget budget = new IntelligenceTokenBudget(1000, clockAt("2026-09-29T01:00:00Z"));

        assertThat(budget.tryAcquire(300)).isTrue();
        assertThat(budget.tryAcquire(300)).isTrue();
        assertThat(budget.usedToday()).isEqualTo(600);
        assertThat(budget.exhausted()).isFalse();
    }

    @Test
    @DisplayName("给定一笔使累计越过限额，whenTryAcquire，then该笔计入后拒批且exhausted")
    void givenAcquisitionCrossesLimit_whenTryAcquire_thenCountedThenRejected() {
        IntelligenceTokenBudget budget = new IntelligenceTokenBudget(1000, clockAt("2026-09-29T01:00:00Z"));

        assertThat(budget.tryAcquire(600)).isTrue();
        assertThat(budget.tryAcquire(500)).isFalse(); // 1100 > 1000：计入（真实已消费）但停批
        assertThat(budget.usedToday()).isEqualTo(1100);
        assertThat(budget.exhausted()).isTrue();
    }

    @Test
    @DisplayName("给定累计恰达限额，whenTryAcquire，then不视为超限（超=严格大于）")
    void givenExactlyAtLimit_whenTryAcquire_thenNotExhausted() {
        IntelligenceTokenBudget budget = new IntelligenceTokenBudget(1000, clockAt("2026-09-29T01:00:00Z"));

        assertThat(budget.tryAcquire(1000)).isTrue();
        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.tryAcquire(1)).isFalse();
        assertThat(budget.exhausted()).isTrue();
    }

    @Test
    @DisplayName("给定已超限，when继续tryAcquire，then恒拒且不再累计")
    void givenAlreadyExhausted_whenTryAcquireAgain_thenRejectedWithoutAccumulation() {
        IntelligenceTokenBudget budget = new IntelligenceTokenBudget(1000, clockAt("2026-09-29T01:00:00Z"));
        budget.tryAcquire(1500);

        assertThat(budget.tryAcquire(1000)).isFalse();
        assertThat(budget.usedToday()).isEqualTo(1500);
    }

    @Test
    @DisplayName("给定上海自然日翻转，when跨日tryAcquire，then计数重置重新放行")
    void givenShanghaiDayRollover_whenTryAcquire_thenCounterResets() {
        MutableClock clock = MutableClock.at("2026-09-29T15:59:00Z"); // 上海 29 日 23:59
        IntelligenceTokenBudget budget = new IntelligenceTokenBudget(1000, clock);
        budget.tryAcquire(1500);
        assertThat(budget.exhausted()).isTrue();

        clock.set("2026-09-29T16:01:00Z"); // 上海 30 日 00:01：跨日

        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.tryAcquire(50)).isTrue();
        assertThat(budget.usedToday()).isEqualTo(50);
    }

    @Test
    @DisplayName("给定默认配置装配，when经InvestProperties构造，then护栏2,000,000生效")
    void givenDefaultProperties_whenConstructed_thenTwoMillionGuardrailApplies() {
        InvestProperties props = new InvestProperties();
        assertThat(props.getIntelligence().getDailyTokenGuardrail()).isEqualTo(2_000_000L);
        assertThat(props.getIntelligence().getExtractBatchSize()).isEqualTo(15);
        assertThat(props.getIntelligence().getMajorThreshold()).isEqualTo(80);
        assertThat(props.getIntelligence().getWatchThreshold()).isEqualTo(50);

        IntelligenceTokenBudget budget = new IntelligenceTokenBudget(props);
        assertThat(budget.tryAcquire(2_000_000L)).isTrue();  // 恰达限不超
        assertThat(budget.tryAcquire(1)).isFalse();          // 超限停批
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    private static Clock clockAt(String instantLiteral) {
        return Clock.fixed(Instant.parse(instantLiteral), CST);
    }

    /** 可拨动时钟（跨日重置测试）。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        static MutableClock at(String literal) {
            return new MutableClock(Instant.parse(literal));
        }

        void set(String literal) {
            this.instant = Instant.parse(literal);
        }

        @Override
        public ZoneId getZone() {
            return CST;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }
    }
}
