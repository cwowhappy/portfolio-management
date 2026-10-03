package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BindingCodeRepository;
import com.portfolio.invest.domain.intelligence.FeishuBindingRepository;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订阅用例单测（F16 + P4 绑定数据面 D8）：get 无行物化缺省视图（不落库）、get 已存行
 * 映射视图（行业/标的稳定排序）、update 全量替换语义；generateCode（6 位数字 / TTL
 * 取 invest.intelligence.binding-code-ttl-minutes / PK 冲突重生成 ≤3 / 超限抛出）、
 * unbind 委托仓库删除、findOpenId 委托仓库查询。内存假仓库 + 固定时钟驱动。
 */
class SubscriptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T09:00:00Z");

    private final FakeRepository repository = new FakeRepository();
    private final FakeBindingCodeRepository bindingCodes = new FakeBindingCodeRepository();
    private final FakeFeishuBindingRepository feishuBindings = new FakeFeishuBindingRepository();

    private SubscriptionService service(InvestProperties props) {
        return new SubscriptionService(repository, bindingCodes, feishuBindings, props,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** TTL=10 分钟的缺省配置（InvestProperties 出厂值同参——显式 set 钉死口径）。 */
    private static InvestProperties propsWithTtl(int ttlMinutes) {
        InvestProperties props = new InvestProperties();
        props.getIntelligence().setBindingCodeTtlMinutes(ttlMinutes);
        return props;
    }

    @Test
    @DisplayName("给定无落库行，when get，then返回缺省视图（pushEnabled=true 空集）且不触发保存")
    void givenNoRow_whenGet_thenDefaultViewWithoutSave() {
        var view = service(propsWithTtl(10)).get(42L);

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

        var view = service(propsWithTtl(10)).get(42L);

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

        var view = service(propsWithTtl(10)).update(42L, cmd);

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

        service(propsWithTtl(10)).update(42L,
                new SubscriptionService.UpdateSubscriptionCommand(true, null, null));

        IntelligenceSubscription saved = repository.savedAggregates.getFirst();
        assertThat(saved.industries()).as("null 整体替换为空集").isEmpty();
        assertThat(saved.stocks()).isEmpty();
    }

    @Test
    @DisplayName("给定首次不冲突，when generateCode，then落 6 位数字码且失效时刻=now+TTL（D8）")
    void givenNoConflict_whenGenerateCode_thenSixDigitCodeWithTtlExpiry() {
        var view = service(propsWithTtl(10)).generateCode(42L);

        assertThat(view.code()).matches("\\d{6}");
        assertThat(view.expiresAt())
                .as("TTL 10 分钟（invest.intelligence.binding-code-ttl-minutes）")
                .isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(bindingCodes.saved).hasSize(1);
        assertThat(bindingCodes.saved.getFirst().userId()).isEqualTo(42L);
        assertThat(bindingCodes.saved.getFirst().expiresAt()).isEqualTo(view.expiresAt());
        assertThat(bindingCodes.saved.getFirst().code()).isEqualTo(view.code());
    }

    @Test
    @DisplayName("给定 TTL 配置 5 分钟，when generateCode，then失效时刻按配置折算（yml 可调）")
    void givenTtlFiveMinutes_whenGenerateCode_thenExpiryMatchesConfig() {
        var view = service(propsWithTtl(5)).generateCode(42L);

        assertThat(view.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("给定首次 PK 冲突，when generateCode，then换码重生成成功（≤3 次内）")
    void givenConflictOnce_whenGenerateCode_thenRegeneratesAndSucceeds() {
        bindingCodes.failFirstAttempts = 1;

        var view = service(propsWithTtl(10)).generateCode(42L);

        assertThat(bindingCodes.attempts).as("冲突一次后第二次成功").isEqualTo(2);
        assertThat(view.code()).matches("\\d{6}");
        assertThat(view.code()).isEqualTo(bindingCodes.saved.getFirst().code());
    }

    @Test
    @DisplayName("给定连续 3 次冲突，when generateCode，then抛非受检异常（不静默返回旧码）")
    void givenPersistentConflict_whenGenerateCode_thenThrowsAfterThreeAttempts() {
        bindingCodes.failFirstAttempts = 99;

        assertThatThrownBy(() -> service(propsWithTtl(10)).generateCode(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重试超限");
        assertThat(bindingCodes.attempts).as("至多 3 次").isEqualTo(3);
        assertThat(bindingCodes.saved).isEmpty();
    }

    @Test
    @DisplayName("when unbind，then委托仓库删除该用户绑定行")
    void whenUnbind_thenDeletesByUserId() {
        service(propsWithTtl(10)).unbind(42L);

        assertThat(feishuBindings.deletedUserIds).containsExactly(42L);
    }

    @Test
    @DisplayName("given 已绑定 open_id，when findOpenId，then透传仓库查询值")
    void givenBoundOpenId_whenFindOpenId_thenDelegates() {
        feishuBindings.bind(42L, "ou-abc");

        assertThat(service(propsWithTtl(10)).findOpenId(42L)).contains("ou-abc");
        assertThat(service(propsWithTtl(10)).findOpenId(7L)).as("他人不串号").isEmpty();
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

    /** 内存假绑定码仓库：前 failFirstAttempts 次 trySave 返回 false（模拟 PK 冲突）。 */
    private static final class FakeBindingCodeRepository implements BindingCodeRepository {
        private record Saved(String code, Long userId, Instant expiresAt) {}

        private int failFirstAttempts;
        private int attempts;
        private final List<Saved> saved = new ArrayList<>();

        @Override
        public boolean trySave(String code, Long userId, Instant expiresAt) {
            attempts++;
            if (attempts <= failFirstAttempts) {
                return false;
            }
            saved.add(new Saved(code, userId, expiresAt));
            return true;
        }

        @Override
        public int deleteExpiredBefore(Instant cutoff) {
            return 0;
        }
    }

    /** 内存假飞书绑定仓库：按 userId 槽位存 open_id，记录 delete 调用。 */
    private static final class FakeFeishuBindingRepository implements FeishuBindingRepository {
        private final java.util.Map<Long, String> storedByUser = new java.util.HashMap<>();
        private final List<Long> deletedUserIds = new ArrayList<>();

        void bind(Long userId, String openId) {
            storedByUser.put(userId, openId);
        }

        @Override
        public Optional<String> findOpenIdByUserId(Long userId) {
            return Optional.ofNullable(storedByUser.get(userId));
        }

        @Override
        public boolean deleteByUserId(Long userId) {
            deletedUserIds.add(userId);
            return storedByUser.remove(userId) != null;
        }
    }
}
