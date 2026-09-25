package com.portfolio.invest.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.portfolio.CreateGroupCommand;
import com.portfolio.invest.application.portfolio.ImportResult;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioImportService;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CSV 导入真实 PG 全链对拍（MS-14 P1 收口）：应用服务入口 → 五层校验 → 实体演化落库，
 * 六行六类型文件以手算已知答案逐列核对（模拟器与执行演化一致性由同一组数值背书）。
 *
 * <p>手算口径（与 {@code ImportSimulator}/{@code Position#applySell} 同构）：
 * SELL 现金流入 = 价×量 − 费（87525−5=87520，费用不重复扣），
 * 现金分红总额 = 每股 × 当时持仓（25.63×50=1281.5）。
 *
 * <p>隔离：每用例独立哨兵用户（Testcontainers 单例库内直插），AfterEach 级联清账；
 * 600519 须先进最新估值快照（L3 代码存在性）——空容器库直插当日一行即成唯一最新快照，
 * 用例后删净以防抬高 max(trading_day) 污染他测的「最新快照」口径。
 */
@SpringBootTest
class CsvImportIntegrationTest extends PostgresTestSupport {

    /** 高位哨兵 id（9001/9003 已被他测占用），每用例各一，互不共享。 */
    private static final long USER_ID_SUCCESS = 9107L;
    private static final long USER_ID_REJECT = 9108L;
    private static final String GROUP_NAME = "导入测试组";

    /** 六行六类型标准文件（与 CsvImportParserTest.SIX_TYPE_CSV 同源，按日期升序排布）。 */
    private static final String SIX_ROW_CSV = """
            日期,类型,证券代码,证券名称,分组名称,价格,数量,费用/金额,备注
            2024-01-03,DEPOSIT,,,导入测试组,,,200000.00,初始入金
            2024-01-05,BUY,600519,贵州茅台,导入测试组,1680.00,100,5.00,首次建仓
            2024-06-20,SELL,600519,贵州茅台,导入测试组,1750.50,50,5.00,减仓
            2024-07-01,CASH_DIVIDEND,600519,贵州茅台,导入测试组,25.63,,,
            2024-07-01,STOCK_DIVIDEND,600519,贵州茅台,导入测试组,0.05,,,
            2024-08-10,WITHDRAW,,,导入测试组,,,1000.00,出金
            """;

    /** 同文件但 SELL 行（物理行 4）数量 50→999：L5 模拟捕获超卖，all-or-nothing 全量拒绝。 */
    private static final String OVERSELL_CSV = SIX_ROW_CSV.replace("1750.50,50,5.00", "1750.50,999,5.00");

    @Autowired
    private PortfolioImportService importService;

    @Autowired
    private PortfolioApplicationService portfolioService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 哨兵用户 + ACCOUNT 分组 + 600519 当日快照行，返回该用户 portfolio id。 */
    private long seedUserWithGroup(long userId, String username) {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, 'h', 'USER', 'PENDING')",
                userId, username);
        portfolioService.createGroup(userId, new CreateGroupCommand(GROUP_NAME, GroupType.ACCOUNT));
        // 幂等防重（UNIQUE(trading_day, stock_code)）：先删当日 600519 再插
        jdbcTemplate.update("DELETE FROM stock_valuation_daily WHERE trading_day = ? AND stock_code = '600519'",
                Date.valueOf(LocalDate.now()));
        jdbcTemplate.update(
                "INSERT INTO stock_valuation_daily(trading_day, stock_code, stock_name, pe_ttm, pb, "
                        + "dividend_yield, total_mv, circ_mv, turnover_rate) VALUES (?,?,?,?,?,?,?,?,?)",
                Date.valueOf(LocalDate.now()), "600519", "贵州茅台", new BigDecimal("22.50"),
                new BigDecimal("7.80"), new BigDecimal("2.10"), new BigDecimal("2100000000000"),
                new BigDecimal("2100000000000"), new BigDecimal("0.35"));
        return jdbcTemplate.queryForObject("SELECT id FROM portfolio WHERE user_id = ?", Long.class, userId);
    }

    /** 级联清账：portfolio 一删（ON DELETE CASCADE）带走 holding_group/cash_transaction 与 position→trade/dividend。 */
    private void cleanupUser(long userId) {
        jdbcTemplate.update("DELETE FROM stock_valuation_daily WHERE trading_day = ? AND stock_code = '600519'",
                Date.valueOf(LocalDate.now()));
        jdbcTemplate.update("DELETE FROM portfolio WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    /** 该用户在持仓域六张表的行数快照（all-or-nothing 零写入对拍用）。 */
    private Map<String, Integer> sixTableCounts(long userId) {
        Long pid = jdbcTemplate.queryForObject("SELECT id FROM portfolio WHERE user_id = ?", Long.class, userId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("portfolio", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolio WHERE id = ?", Integer.class, pid));
        counts.put("holding_group", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM holding_group WHERE portfolio_id = ?", Integer.class, pid));
        counts.put("position", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM position WHERE portfolio_id = ?", Integer.class, pid));
        counts.put("trade", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade t JOIN position p ON t.position_id = p.id "
                        + "WHERE p.portfolio_id = ?", Integer.class, pid));
        counts.put("dividend", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dividend d JOIN position p ON d.position_id = p.id "
                        + "WHERE p.portfolio_id = ?", Integer.class, pid));
        counts.put("cash_transaction", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cash_transaction c JOIN holding_group g ON c.group_id = g.id "
                        + "WHERE g.portfolio_id = ?", Integer.class, pid));
        return counts;
    }

    @Test
    @DisplayName("六行六类型标准文件成功导入：现金/持仓/三账本逐列手算对拍")
    void givenSixRowFile_whenImport_thenHandCalculatedStatePersisted() {
        long pid = seedUserWithGroup(USER_ID_SUCCESS, "csv-import-success");
        Long groupId = jdbcTemplate.queryForObject(
                "SELECT id FROM holding_group WHERE portfolio_id = ? AND name = ?", Long.class, pid, GROUP_NAME);

        ImportResult result = importService.importCsv(USER_ID_SUCCESS, SIX_ROW_CSV);

        assertThat(result.importedCount()).isEqualTo(6);
        assertThat(result.rowErrors()).isEmpty();

        // 持仓：数量 (100−50)×1.05 = 52.5；净现金流 −168005 + 87520 + 1281.5 = −79203.5
        Map<String, Object> position = jdbcTemplate.queryForMap(
                "SELECT stock_code, stock_name, quantity, net_cash_flow FROM position WHERE group_id = ?",
                groupId);
        assertThat(position.get("stock_code")).isEqualTo("600519");
        assertThat(position.get("stock_name")).isEqualTo("贵州茅台");
        assertThat((BigDecimal) position.get("quantity")).isEqualByComparingTo("52.5");
        assertThat((BigDecimal) position.get("net_cash_flow")).isEqualByComparingTo("-79203.5");

        // 组现金（读侧同构 SQL：Σ持仓净现金流 + Σ转入−转出）
        // = 200000 − 168005 + 87520 + 1281.5 − 1000 = 119796.5（87520 已含卖出费，不重复扣 5）
        BigDecimal cash = jdbcTemplate.queryForObject(
                "SELECT COALESCE((SELECT SUM(net_cash_flow) FROM position WHERE group_id = ?), 0) "
                        + "+ COALESCE((SELECT SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE -amount END) "
                        + "FROM cash_transaction WHERE group_id = ?), 0)",
                BigDecimal.class, groupId, groupId);
        assertThat(cash).isEqualByComparingTo("119796.5");

        // 三账本行数与形态：trade 2（BUY+SELL）、dividend 2（CASH+STOCK）、cash_transaction 2（DEPOSIT+WITHDRAW）
        assertThat(sixTableCounts(USER_ID_SUCCESS)).containsOnly(
                Map.entry("portfolio", 1), Map.entry("holding_group", 1), Map.entry("position", 1),
                Map.entry("trade", 2), Map.entry("dividend", 2), Map.entry("cash_transaction", 2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade t JOIN position p ON t.position_id = p.id "
                        + "WHERE p.group_id = ? AND t.type = 'BUY'", Integer.class, groupId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade t JOIN position p ON t.position_id = p.id "
                        + "WHERE p.group_id = ? AND t.type = 'SELL'", Integer.class, groupId)).isEqualTo(1);
        // 分红写形态：每股现金 25.63 与送股比例 0.05 原值落库（总额乘算发生在持仓演化侧）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cash_per_share FROM dividend d JOIN position p ON d.position_id = p.id "
                        + "WHERE p.group_id = ? AND d.type = 'CASH'", BigDecimal.class, groupId))
                .isEqualByComparingTo("25.63");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_ratio FROM dividend d JOIN position p ON d.position_id = p.id "
                        + "WHERE p.group_id = ? AND d.type = 'STOCK'", BigDecimal.class, groupId))
                .isEqualByComparingTo("0.05");
        // 备注列全链 round-trip（DEPOSIT 行「初始入金」）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT note FROM cash_transaction WHERE group_id = ? AND type = 'DEPOSIT'",
                String.class, groupId)).isEqualTo("初始入金");
    }

    @Test
    @DisplayName("SELL 超持仓数量：行错误含物理行号与原因，六张表零写入")
    void givenOversellFile_whenImport_thenRejectedWithZeroWrites() {
        seedUserWithGroup(USER_ID_REJECT, "csv-import-reject");
        Map<String, Integer> before = sixTableCounts(USER_ID_REJECT);

        ImportResult result = importService.importCsv(USER_ID_REJECT, OVERSELL_CSV);

        // 拒绝形态（§1.3 契约）：importedCount=0 + 行错误清单（SELL 物理行 4）
        assertThat(result.importedCount()).isZero();
        assertThat(result.rowErrors()).hasSize(1);
        assertThat(result.rowErrors().get(0).row()).isEqualTo(4);
        assertThat(result.rowErrors().get(0).reason()).contains("卖出数量超过持仓").contains("999");

        // all-or-nothing：六张表导入前后行数完全一致（零写入）
        assertThat(sixTableCounts(USER_ID_REJECT)).isEqualTo(before);
    }

    @AfterEach
    void cleanup() {
        cleanupUser(USER_ID_SUCCESS);
        cleanupUser(USER_ID_REJECT);
    }
}
