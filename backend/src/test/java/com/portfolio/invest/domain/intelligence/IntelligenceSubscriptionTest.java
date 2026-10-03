package com.portfolio.invest.domain.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订阅聚合根单测（F16，决策 #18）：缺省实例语义（无行 = pushEnabled=true 空集，不落库
 * 由调用方保证）、wither 不可变（变更返回新实例、原实例不被篡改）、reconstitute 保真、
 * 归属用户缺失守卫。
 */
class IntelligenceSubscriptionTest {

    @Test
    @DisplayName("给定任意用户，when构造缺省实例，then pushEnabled=true 且行业/标的均为空集、无更新时间")
    void givenAnyUser_whenDefaults_thenPushEnabledWithEmptyCollections() {
        IntelligenceSubscription defaults = IntelligenceSubscription.defaults(42L);

        assertThat(defaults.userId()).isEqualTo(42L);
        assertThat(defaults.pushEnabled()).isTrue();
        assertThat(defaults.industries()).isEmpty();
        assertThat(defaults.stocks()).isEmpty();
        assertThat(defaults.updatedAt()).as("缺省实例从未落库，无更新时间").isNull();
    }

    @Test
    @DisplayName("给定缺省实例，when切换推送开关，then返回新实例且原实例不变")
    void givenDefaults_whenTogglePush_thenNewInstanceAndOriginalUntouched() {
        IntelligenceSubscription original = IntelligenceSubscription.defaults(42L);

        IntelligenceSubscription disabled = original.togglePush(false);

        assertThat(disabled.pushEnabled()).isFalse();
        assertThat(disabled).isNotSameAs(original);
        assertThat(original.pushEnabled()).as("wither 不可变：原实例保持缺省").isTrue();
        assertThat(disabled.updatedAt()).as("变更打点更新时间").isNotNull();
    }

    @Test
    @DisplayName("给定已是目标开关状态的实例，when再次togglePush同值，then原样返回自身（幂等）")
    void givenAlreadyDisabled_whenTogglePushSameValue_thenSameInstance() {
        IntelligenceSubscription disabled = IntelligenceSubscription.defaults(42L).togglePush(false);

        assertThat(disabled.togglePush(false)).isSameAs(disabled);
    }

    @Test
    @DisplayName("给定缺省实例，when替换行业与标的集合，then新实例持有新集合且原实例集合不变")
    void givenDefaults_whenWithIndustriesAndStocks_thenNewInstanceWithReplacedCollections() {
        IntelligenceSubscription original = IntelligenceSubscription.defaults(42L);

        IntelligenceSubscription updated = original
                .withIndustries(List.of("801010", "801780"))
                .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台"),
                        new SubscriptionStock("300750", "宁德时代")));

        assertThat(updated.industries()).containsExactly("801010", "801780");
        assertThat(updated.stocks()).extracting(SubscriptionStock::stockCode)
                .containsExactly("600519", "300750");
        assertThat(updated.stocks()).extracting(SubscriptionStock::stockName)
                .containsExactly("贵州茅台", "宁德时代");
        assertThat(original.industries()).as("原实例行业集不变").isEmpty();
        assertThat(original.stocks()).as("原实例标的集不变").isEmpty();
    }

    @Test
    @DisplayName("给定已替换集合的实例，when空集合整体替换，then清空对应集合（全量替换语义）")
    void givenPopulatedInstance_whenReplaceWithEmpty_thenCollectionsCleared() {
        IntelligenceSubscription populated = IntelligenceSubscription.defaults(42L)
                .withIndustries(List.of("801010"))
                .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台")));

        IntelligenceSubscription emptied = populated.withIndustries(List.of()).withStocks(List.of());

        assertThat(emptied.industries()).isEmpty();
        assertThat(emptied.stocks()).isEmpty();
    }

    @Test
    @DisplayName("给定null集合入参，when替换，then归一为空集而非抛错（缺字段宽容）")
    void givenNullCollections_whenReplace_thenNormalizedToEmpty() {
        IntelligenceSubscription updated = IntelligenceSubscription.defaults(42L)
                .withIndustries(null).withStocks(null);

        assertThat(updated.industries()).isEmpty();
        assertThat(updated.stocks()).isEmpty();
    }

    @Test
    @DisplayName("给定聚合外获得的集合引用，when据此替换后修改该引用，then聚合内集合不被篡改（防御性拷贝）")
    void givenExternalCollectionReference_whenMutateAfterReplace_thenAggregateUnaffected() {
        Set<String> industries = new LinkedHashSet<>(List.of("801010"));
        Set<SubscriptionStock> stocks =
                new LinkedHashSet<>(List.of(new SubscriptionStock("600519", "贵州茅台")));
        IntelligenceSubscription updated = IntelligenceSubscription.defaults(42L)
                .withIndustries(industries).withStocks(stocks);

        industries.add("801780");
        stocks.add(new SubscriptionStock("300750", "宁德时代"));

        assertThat(updated.industries()).containsExactly("801010");
        assertThat(updated.stocks()).extracting(SubscriptionStock::stockCode)
                .containsExactly("600519");
    }

    @Test
    @DisplayName("给定落库行字段，when reconstitute，then字段保真往返")
    void givenPersistedRowFields_whenReconstitute_thenFaithfulRoundTrip() {
        Instant updatedAt = Instant.parse("2026-10-01T12:00:00Z");

        IntelligenceSubscription reconstituted = IntelligenceSubscription.reconstitute(
                42L, false, List.of("801010", "801780"),
                List.of(new SubscriptionStock("600519", "贵州茅台")), updatedAt);

        assertThat(reconstituted.userId()).isEqualTo(42L);
        assertThat(reconstituted.pushEnabled()).isFalse();
        assertThat(reconstituted.industries()).containsExactly("801010", "801780");
        assertThat(reconstituted.stocks()).containsExactly(new SubscriptionStock("600519", "贵州茅台"));
        assertThat(reconstituted.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("给定null归属用户，when构造缺省实例或reconstitute，then拒绝（IllegalArgumentException）")
    void givenNullUser_whenConstruct_thenReject() {
        assertThatThrownBy(() -> IntelligenceSubscription.defaults(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntelligenceSubscription.reconstitute(
                null, true, List.of(), List.of(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
