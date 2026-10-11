package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.research.ReportTokenQuota;
import com.portfolio.invest.domain.research.ReportTokenQuotaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 研报 token 配额服务单测（MS-32 B10，设计规格 §3.9，内存假仓库 + 固定时钟）：
 * 发起软检查边界（used &lt; budget 放行 / == 与 &gt; 均拒且 remaining=0——恰等也拒）、
 * settle 按 quota_date=LocalDate.now(clock) 正累计与负数拒（不触碰仓库）、
 * 时钟跨日按新 quota_date 全额（昨日行不动）、remainingToday 无行全额与超支钳 0。
 * upsert 并发不丢增量与他人隔离由 B3 集成测试承担（ReportRepositoryIntegrationTest）。
 */
class ReportTokenQuotaServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY1 = LocalDate.of(2026, 10, 11);
    private static final LocalDate DAY2 = LocalDate.of(2026, 10, 12);
    private static final Instant DAY1_NOON = Instant.parse("2026-10-11T04:00:00Z"); // 上海 12:00
    private static final Instant DAY2_NOON = Instant.parse("2026-10-12T04:00:00Z");
    private static final long USER = 42L;

    private final FakeQuotaRepository repository = new FakeQuotaRepository();

    /** budget=1000 的配置（显式 set 钉死口径；perTaskCap 本服务不消费——归 B11 单任务兜底）。 */
    private static InvestProperties propsWithBudget(long dailyBudget) {
        InvestProperties props = new InvestProperties();
        props.getReport().setQuota(new InvestProperties.Report.Quota(dailyBudget, 100L));
        return props;
    }

    private ReportTokenQuotaService serviceAt(Instant instant) {
        return new ReportTokenQuotaService(repository, propsWithBudget(1000), Clock.fixed(instant, ZONE));
    }

    @Test
    @DisplayName("给定当日已用 900/预算 1000，when assertCanStart，then放行（余量足够）")
    void givenUsedBelowBudget_whenAssertCanStart_thenAllowed() {
        repository.seed(USER, DAY1, 900L);

        assertThatCode(() -> serviceAt(DAY1_NOON).assertCanStart(USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("给定当日累计恰等预算（used==budget），when assertCanStart，then拒且 remaining=0（恰等也拒）")
    void givenUsedEqualsBudget_whenAssertCanStart_thenRejectedWithZeroRemaining() {
        repository.seed(USER, DAY1, 1000L);

        assertThatThrownBy(() -> serviceAt(DAY1_NOON).assertCanStart(USER))
                .isInstanceOfSatisfying(ReportQuotaExceededException.class, e -> {
                    assertThat(e.getRemaining()).as("发起闸门拒批时余量恒 0").isZero();
                    assertThat(e.getMessage()).contains("1000");
                });
    }

    @Test
    @DisplayName("给定当日累计已超预算（used>budget），when assertCanStart，then仍拒（>=口径）")
    void givenUsedAboveBudget_whenAssertCanStart_thenRejected() {
        repository.seed(USER, DAY1, 1200L);

        assertThatThrownBy(() -> serviceAt(DAY1_NOON).assertCanStart(USER))
                .isInstanceOf(ReportQuotaExceededException.class);
    }

    @Test
    @DisplayName("when settle 同日双调，then按 quota_date=LocalDate.now(clock) 累计入库且余量联动")
    void givenTwoSettlesSameDay_whenSettle_thenAccumulatesOnToday() {
        ReportTokenQuotaService service = serviceAt(DAY1_NOON);

        service.settle(USER, 600L);
        service.settle(USER, 300L);

        assertThat(repository.usedBy(new Key(USER, DAY1))).as("同日双调 upsert 累加").isEqualTo(900L);
        assertThat(service.remainingToday(USER)).isEqualTo(100L);
    }

    @Test
    @DisplayName("given 负数 tokens，when settle，then抛 IllegalArgumentException 且不触碰仓库")
    void givenNegativeTokens_whenSettle_thenRejectedWithoutTouchingRepo() {
        assertThatThrownBy(() -> serviceAt(DAY1_NOON).settle(USER, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
        assertThat(repository.usedBy(new Key(USER, DAY1))).as("防御拒绝不落行").isNull();
    }

    @Test
    @DisplayName("given 昨日已结算 400，when 次日（时钟跨日）查余量与发起，then按新 quota_date 全额放行（跨日新行）")
    void givenYesterdaySettled_whenNextDay_thenFullBudgetOnNewDate() {
        serviceAt(DAY1_NOON).settle(USER, 400L);
        ReportTokenQuotaService nextDay = serviceAt(DAY2_NOON);

        assertThat(nextDay.remainingToday(USER)).as("新日无行=预算全额").isEqualTo(1000L);
        assertThatCode(() -> nextDay.assertCanStart(USER)).doesNotThrowAnyException();
        assertThat(repository.usedBy(new Key(USER, DAY1))).as("昨日行不动").isEqualTo(400L);
    }

    @Test
    @DisplayName("给定当日无行，when remainingToday，then返回预算全额")
    void givenNoRow_whenRemainingToday_thenFullBudget() {
        assertThat(serviceAt(DAY1_NOON).remainingToday(USER)).isEqualTo(1000L);
    }

    @Test
    @DisplayName("给定当日超支（used 1200 > budget 1000），when remainingToday，then钳 0 不为负")
    void givenOverSpent_whenRemainingToday_thenClampedToZero() {
        repository.seed(USER, DAY1, 1200L);

        assertThat(serviceAt(DAY1_NOON).remainingToday(USER)).isZero();
    }

    /** 复合键（userId, quotaDate）。 */
    private record Key(Long userId, LocalDate date) {}

    /** 内存假仓库：settle 按 (userId, quotaDate) 累加（复刻单语句 upsert 语义），读按复合键直取。 */
    private static final class FakeQuotaRepository implements ReportTokenQuotaRepository {

        private final Map<Key, Long> used = new HashMap<>();

        @Override
        public Optional<ReportTokenQuota> findByUserIdAndDate(Long userId, LocalDate date) {
            Long tokens = used.get(new Key(userId, date));
            return tokens == null ? Optional.empty()
                    : Optional.of(new ReportTokenQuota(userId, date, tokens));
        }

        @Override
        public void settle(Long userId, LocalDate date, long delta) {
            used.merge(new Key(userId, date), delta, Long::sum);
        }

        void seed(Long userId, LocalDate date, long tokens) {
            used.put(new Key(userId, date), tokens);
        }

        Long usedBy(Key key) {
            return used.get(key);
        }
    }
}
