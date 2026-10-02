package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订阅用例单测（F16）：内存假仓库驱动——get 无行物化缺省视图（不落库）、get 已存行
 * 映射视图（行业/标的稳定排序）、update 全量替换语义（基于当前聚合构建新聚合保存，
 * null 集合归一空集）。
 */
class SubscriptionServiceTest {

    private final FakeRepository repository = new FakeRepository();
    private final SubscriptionService service = new SubscriptionService(repository);

    @Test
    @DisplayName("给定无落库行，when get，then返回缺省视图（pushEnabled=true 空集）且不触发保存")
    void givenNoRow_whenGet_thenDefaultViewWithoutSave() {
        var view = service.get(42L);

        assertThat(view.pushEnabled()).isTrue();
        assertThat(view.industries()).isEmpty();
        assertThat(view.stocks()).isEmpty();
        assertThat(view.updatedAt()).isNull();
        assertThat(repository.savedAggregates).as("读路径不落库").isEmpty();
    }

    @Test
    @DisplayName("给定已存订阅行，when get，then映射视图且行业与标的按码稳定排序")
    void givenPersistedRow_whenGet_thenMappedViewWithStableOrdering() {
        repository.stored = IntelligenceSubscription.reconstitute(42L, false,
                List.of("801780", "801010"),
                List.of(new SubscriptionStock("300750", "宁德时代"),
                        new SubscriptionStock("600519", "贵州茅台")),
                Instant.parse("2026-10-01T12:00:00Z"));

        var view = service.get(42L);

        assertThat(view.pushEnabled()).isFalse();
        assertThat(view.industries()).containsExactly("801010", "801780");
        // 标的按码字典序（六位数字码上与数值序一致）：300750 在 600519 前
        assertThat(view.stocks()).extracting(s -> s.code())
                .containsExactly("300750", "600519");
        assertThat(view.stocks()).extracting(s -> s.name())
                .containsExactly("宁德时代", "贵州茅台");
        assertThat(view.updatedAt()).isEqualTo("2026-10-01T12:00:00Z");
    }

    @Test
    @DisplayName("给定缺省状态与全量命令，when update，then整体替换保存并回读保存视图")
    void givenDefaultStateAndFullCommand_whenUpdate_thenReplacedAggregateSaved() {
        var cmd = new SubscriptionService.UpdateSubscriptionCommand(false,
                List.of("801010"),
                List.of(new SubscriptionService.UpdateSubscriptionCommand.StockItem("600519", "贵州茅台")));

        var view = service.update(42L, cmd);

        assertThat(repository.savedAggregates).hasSize(1);
        IntelligenceSubscription saved = repository.savedAggregates.getFirst();
        assertThat(saved.userId()).isEqualTo(42L);
        assertThat(saved.pushEnabled()).isFalse();
        assertThat(saved.industries()).containsExactly("801010");
        assertThat(saved.stocks()).containsExactly(new SubscriptionStock("600519", "贵州茅台"));
        assertThat(view.pushEnabled()).isFalse();
        assertThat(view.industries()).containsExactly("801010");
        assertThat(view.stocks()).extracting(s -> s.code()).containsExactly("600519");
        assertThat(view.updatedAt()).as("保存后视图带打点时间").isNotNull();
    }

    @Test
    @DisplayName("给定命令缺 industries/stocks 字段，when update，then归一为空集保存（全量替换语义）")
    void givenCommandWithNullCollections_whenUpdate_thenNormalizedToEmpty() {
        repository.stored = IntelligenceSubscription.reconstitute(42L, true,
                List.of("801010"),
                List.of(new SubscriptionStock("600519", "贵州茅台")),
                Instant.parse("2026-09-01T00:00:00Z"));

        service.update(42L, new SubscriptionService.UpdateSubscriptionCommand(true, null, null));

        IntelligenceSubscription saved = repository.savedAggregates.getFirst();
        assertThat(saved.industries()).as("null 整体替换为空集").isEmpty();
        assertThat(saved.stocks()).isEmpty();
    }

    /** 内存假仓库：记录 save 入参供断言，get 读单值槽位。 */
    private static final class FakeRepository implements SubscriptionRepository {
        private IntelligenceSubscription stored;
        private final List<IntelligenceSubscription> savedAggregates = new ArrayList<>();

        @Override
        public Optional<IntelligenceSubscription> findByUserId(Long userId) {
            return Optional.ofNullable(stored);
        }

        @Override
        public IntelligenceSubscription save(IntelligenceSubscription subscription) {
            savedAggregates.add(subscription);
            stored = subscription;
            return subscription;
        }

        @Override
        public List<Long> findUserIdsWithPushEnabled() {
            return List.of();
        }

        @Override
        public List<IntelligenceSubscription> findAllWithStock(String stockCode) {
            return List.of();
        }
    }
}
