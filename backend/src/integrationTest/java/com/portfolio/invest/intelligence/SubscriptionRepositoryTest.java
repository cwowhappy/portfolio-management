package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 订阅仓库真库契约（Testcontainers PG16 + V3 迁移，F16 决策 #18）：无行 = Optional.empty
 * 且读路径不落占位行（缺省实例由 domain 表达）、save 主表 upsert + 子表差集同步
 * （新增/删除/改名；保留标的 added_at 不变）、industries JSONB 往返、
 * findUserIdsWithPushEnabled 推送收件人口径、findAllWithStock 定向触达反查
 * （持有该标的 ∧ push_enabled，聚合带全量 stocks）。fixture 需 app_user 行（FK），
 * 照 IntelligenceCleanupTest 的 insertUser 先例；@BeforeEach/@AfterEach 双向清空
 * （app_user 行数被 IntelligenceMigrationTest 断言，兄弟类残留须清）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionRepositoryTest extends PostgresTestSupport {

    @Autowired
    SubscriptionRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        doCleanTables();
    }

    @AfterEach
    void cleanUpForSiblingClasses() {
        doCleanTables();
    }

    private void doCleanTables() {
        // stock 行经 FK ON DELETE CASCADE 随 subscription 清除；subscription 先于 app_user 删（FK）
        jdbc.update("DELETE FROM intelligence_subscription");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'sub_it%'");
    }

    @Test
    @DisplayName("findByUserId：无行返回 empty 且读路径不落占位行（缺省实例只在内存）")
    void givenNoRowForUser_whenFindByUserId_thenEmptyAndNoRowCreated() {
        Long userId = insertUser("sub_it_none");

        assertThat(repository.findByUserId(userId)).isEmpty();
        assertThat(countSubscriptions(userId)).as("读路径不落库").isZero();
    }

    @Test
    @DisplayName("save 首存：主表与子表落库，findByUserId 全量往返（开关/JSONB 行业/标的对）")
    void givenFreshAggregate_whenSave_thenRoundTripsThroughFind() {
        Long userId = insertUser("sub_it_save");

        repository.save(IntelligenceSubscription.defaults(userId)
                .togglePush(false)
                .withIndustries(List.of("801010", "801780"))
                .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台"),
                        new SubscriptionStock("300750", "宁德时代"))));

        IntelligenceSubscription loaded = repository.findByUserId(userId).orElseThrow();
        assertThat(loaded.userId()).isEqualTo(userId);
        assertThat(loaded.pushEnabled()).isFalse();
        assertThat(loaded.industries()).as("JSONB 行业码往返").containsExactlyInAnyOrder("801010", "801780");
        assertThat(loaded.stocks()).extracting(SubscriptionStock::stockCode)
                .containsExactlyInAnyOrder("600519", "300750");
        assertThat(loaded.stocks()).extracting(SubscriptionStock::stockName)
                .containsExactlyInAnyOrder("贵州茅台", "宁德时代");
        assertThat(loaded.updatedAt()).as("保存打点 updated_at").isNotNull();
    }

    @Test
    @DisplayName("save 差集同步：保留标的 added_at 不变，改名走更新，移除的删行，新增的插行")
    void givenExistingStocks_whenSaveReplacedSet_thenDiffSyncedAndKeptAddedAtPreserved() {
        Long userId = insertUser("sub_it_diff");
        repository.save(IntelligenceSubscription.defaults(userId).withStocks(List.of(
                new SubscriptionStock("600519", "茅台"),
                new SubscriptionStock("300750", "宁德时代"),
                new SubscriptionStock("000001", "平安银行"))));
        OffsetDateTime keptAddedAt = stockAddedAt(userId, "600519");

        repository.save(IntelligenceSubscription.defaults(userId).withStocks(List.of(
                new SubscriptionStock("600519", "贵州茅台"),   // 保留 + 改名
                new SubscriptionStock("000001", "平安银行"),   // 原样保留
                new SubscriptionStock("601318", "中国平安")))); // 新增；300750 移除

        assertThat(stockCodesOf(userId)).containsExactlyInAnyOrder("600519", "000001", "601318");
        assertThat(stockNameOf(userId, "600519")).isEqualTo("贵州茅台");
        assertThat(stockNameOf(userId, "000001")).as("原样保留标的不被改名").isEqualTo("平安银行");
        assertThat(stockAddedAt(userId, "600519")).as("保留标的 added_at 不变")
                .isEqualTo(keptAddedAt);
        assertThat(stockAddedAt(userId, "601318")).as("新增标的打点 added_at").isNotNull();
    }

    @Test
    @DisplayName("save 空标的集：子表差集清空（主表行保留，全量替换语义）")
    void givenExistingStocks_whenSaveEmptySet_thenChildRowsAllDeleted() {
        Long userId = insertUser("sub_it_clear");
        repository.save(IntelligenceSubscription.defaults(userId).withStocks(List.of(
                new SubscriptionStock("600519", "贵州茅台"))));

        repository.save(IntelligenceSubscription.defaults(userId).withStocks(List.of()));

        assertThat(stockCodesOf(userId)).isEmpty();
        assertThat(countSubscriptions(userId)).as("主表行保留").isEqualTo(1);
    }

    @Test
    @DisplayName("findUserIdsWithPushEnabled：无行（默认开）与显式开命中、显式关不命中（#27 总开关默认开口径）")
    void givenEnabledDisabledAndAbsentUsers_whenFindUserIdsWithPushEnabled_thenDefaultOnAndEnabledHit() {
        Long enabled = insertUser("sub_it_en");
        Long disabled = insertUser("sub_it_dis");
        Long absent = insertUser("sub_it_absent");
        repository.save(IntelligenceSubscription.defaults(enabled));           // 行：显式开
        repository.save(IntelligenceSubscription.defaults(disabled).togglePush(false)); // 行：显式关

        assertThat(repository.findUserIdsWithPushEnabled())
                .as("无行=默认开（LEFT JOIN 口径）与行开都在受众，仅显式关被排除"
                        + "（contains 而非 exactly：共享容器可能有兄弟类/admin 残留用户，同为默认开属预期）")
                .contains(enabled, absent)
                .doesNotContain(disabled);
    }

    @Test
    @DisplayName("findAllWithStock：持有该标的 ∧ push_enabled 命中且聚合带全量 stocks；关推送或未持有不命中")
    void givenUsersWithVariousHoldingsAndSwitches_whenFindAllWithStock_thenOnlyEnabledHoldersHit() {
        Long holder = insertUser("sub_it_hold");
        Long mutedHolder = insertUser("sub_it_mute");
        Long otherStockHolder = insertUser("sub_it_other");
        repository.save(IntelligenceSubscription.defaults(holder)
                .withIndustries(List.of("801010"))
                .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台"),
                        new SubscriptionStock("300750", "宁德时代"))));
        repository.save(IntelligenceSubscription.defaults(mutedHolder).togglePush(false)
                .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台"))));
        repository.save(IntelligenceSubscription.defaults(otherStockHolder)
                .withStocks(List.of(new SubscriptionStock("300750", "宁德时代"))));

        List<IntelligenceSubscription> matched = repository.findAllWithStock("600519");

        assertThat(matched).extracting(IntelligenceSubscription::userId).containsExactly(holder);
        IntelligenceSubscription aggregate = matched.getFirst();
        assertThat(aggregate.pushEnabled()).isTrue();
        assertThat(aggregate.industries()).as("命中聚合带行业集").containsExactly("801010");
        assertThat(aggregate.stocks()).as("命中聚合带全量标的而非仅命中标的")
                .extracting(SubscriptionStock::stockCode)
                .containsExactlyInAnyOrder("600519", "300750");
        assertThat(repository.findAllWithStock("688981")).as("无人持有则空").isEmpty();
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 照 IntelligenceCleanupTest 的 insertUser 先例（app_user 为 subscription 的 FK 目标）。 */
    private Long insertUser(String username) {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
                + " VALUES(?, 'x', 'USER', 'APPROVED')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    private int countSubscriptions(Long userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_subscription WHERE user_id=?",
                Integer.class, userId);
    }

    private List<String> stockCodesOf(Long userId) {
        return jdbc.queryForList(
                "SELECT stock_code FROM intelligence_subscription_stock WHERE user_id=? ORDER BY stock_code",
                String.class, userId);
    }

    private String stockNameOf(Long userId, String stockCode) {
        return jdbc.queryForObject(
                "SELECT stock_name FROM intelligence_subscription_stock WHERE user_id=? AND stock_code=?",
                String.class, userId, stockCode);
    }

    private OffsetDateTime stockAddedAt(Long userId, String stockCode) {
        return jdbc.queryForObject(
                "SELECT added_at FROM intelligence_subscription_stock WHERE user_id=? AND stock_code=?",
                OffsetDateTime.class, userId, stockCode);
    }

}
