package com.portfolio.invest.industry;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.industry.CurationImportResult;
import com.portfolio.invest.application.industry.IndustryCurationImportService;
import com.portfolio.invest.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 策展/融资 CSV 导入真实 PG 全链实证（照 CsvImportIntegrationTest 基座）：应用服务入口 →
 * 解析/白名单/键内预检 → 逐条 upsert。断言三态——首导 inserted=N、同文件重导 updated=N
 * 且非键字段更新、错误文件 all-or-nothing 两表计数不变（upsert 与 MS-14 纯插入语义的关键
 * 差异实证，设计规格 §九#2）。
 *
 * <p>隔离：existsIndustry 查 shenwan_industry_mapping（测试容器内为空）——BeforeEach 种
 * 一行哨兵映射（stock_code=ITEST01 全表 UNIQUE 未占用段）；数据行 company_name 统一
 * 「集成测试」前缀，AfterEach 按前缀清账，不动 V19 种子。
 */
@SpringBootTest
class CurationImportIntegrationTest extends PostgresTestSupport {

    private static final String INDUSTRY = "801770";
    private static final String SENTINEL_STOCK = "ITEST01";

    /** 三行策展企业标准文件（表头与解析器/模板样例同源；行数据用哨兵前缀）。 */
    private static final String THREE_COMPANY_CSV = """
            industry_code,company_name,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note
            801770,集成测试A公司,动力电池,B,2026-01-15,120.50,简介A,来源A
            801770,集成测试B公司,光伏,A_PLUS,2026-02-20,,简介B,
            801770,集成测试C公司,,UNKNOWN,,,,
            """;

    /** 同键不同值重导（A 行改轮次/金额/赛道；幂等键 industry_code+company_name 不变）。 */
    private static final String THREE_COMPANY_REIMPORT_CSV = """
            industry_code,company_name,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note
            801770,集成测试A公司,储能,D,2026-06-30,200.00,简介A改,来源A改
            801770,集成测试B公司,光伏,A_PLUS,2026-02-20,,简介B,
            801770,集成测试C公司,,UNKNOWN,,,,
            """;

    /** 第 2 行合法 + 第 3 行轮次非法：L2 捕获，all-or-nothing 全量拒绝。 */
    private static final String BAD_ROUND_CSV = """
            industry_code,company_name,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note
            801770,集成测试A公司,动力电池,B,2026-01-15,120.50,简介A,来源A
            801770,集成测试坏行公司,赛道,X轮,2026-01-16,1.00,简介,来源
            """;

    private static final String TWO_EVENT_CSV = """
            event_date,company_name,round,amount_yi,investors,industry_code,segment,source_title,source_url
            2026-03-15,集成测试事件公司,B,8.50,深创投,801770,动力电池,集成测试月报,
            2026-04-15,集成测试事件公司,A,3.00,,801770,,集成测试公开报道,https://example.com/it
            """;

    /** 同键（同日同企同轮）不同金额重导：updated 单计数 + amount_yi 改写。 */
    private static final String TWO_EVENT_REIMPORT_CSV = """
            event_date,company_name,round,amount_yi,investors,industry_code,segment,source_title,source_url
            2026-03-15,集成测试事件公司,B,9.99,高瓴,801770,动力电池,集成测试月报改,https://example.com/it2
            2026-04-15,集成测试事件公司,A,3.00,,801770,,集成测试公开报道,https://example.com/it
            """;

    /** 文件内幂等键重复（第 3 行与第 2 行同日同企同轮）：L5 拒绝零落库。 */
    private static final String DUP_KEY_EVENT_CSV = """
            event_date,company_name,round,amount_yi,investors,industry_code,segment,source_title,source_url
            2026-03-15,集成测试事件公司,B,8.50,深创投,801770,动力电池,集成测试月报,
            2026-03-15,集成测试事件公司,B,9.99,高瓴,801770,动力电池,更正来源,
            """;

    @Autowired
    private IndustryCurationImportService importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedIndustryMapping() {
        jdbcTemplate.update("DELETE FROM shenwan_industry_mapping WHERE stock_code = ?", SENTINEL_STOCK);
        jdbcTemplate.update(
                "INSERT INTO shenwan_industry_mapping(stock_code, stock_name, industry_code, industry_name) "
                        + "VALUES (?, ?, ?, ?)",
                SENTINEL_STOCK, "集成测试哨兵", INDUSTRY, "集成测试行业");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM industry_unlisted_company WHERE company_name LIKE '集成测试%'");
        jdbcTemplate.update("DELETE FROM industry_funding_event WHERE company_name LIKE '集成测试%'");
        jdbcTemplate.update("DELETE FROM shenwan_industry_mapping WHERE stock_code = ?", SENTINEL_STOCK);
    }

    /** 两表哨兵行计数快照（all-or-nothing 零写入对拍用）。 */
    private Map<String, Integer> twoTableCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("industry_unlisted_company", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM industry_unlisted_company WHERE company_name LIKE '集成测试%'",
                Integer.class));
        counts.put("industry_funding_event", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM industry_funding_event WHERE company_name LIKE '集成测试%'",
                Integer.class));
        return counts;
    }

    @Test
    @DisplayName("策展企业首导：inserted=3 落库逐列对拍；重导同键：updated=3 非键字段改写")
    void givenCompanyFiles_whenImportTwice_thenInsertThenUpsertUpdate() {
        CurationImportResult first = importService.importCompanies(THREE_COMPANY_CSV);

        assertThat(first.insertedCount()).isEqualTo(3);
        assertThat(first.updatedCount()).isZero();
        assertThat(first.rowErrors()).isEmpty();
        assertThat(twoTableCounts()).containsOnly(
                Map.entry("industry_unlisted_company", 3), Map.entry("industry_funding_event", 0));
        Map<String, Object> rowA = jdbcTemplate.queryForMap(
                "SELECT segment, latest_round, total_funding_yi FROM industry_unlisted_company "
                        + "WHERE company_name = '集成测试A公司'");
        assertThat(rowA.get("latest_round")).isEqualTo("B");
        assertThat((BigDecimal) rowA.get("total_funding_yi")).isEqualByComparingTo("120.50");

        CurationImportResult second = importService.importCompanies(THREE_COMPANY_REIMPORT_CSV);

        assertThat(second.insertedCount()).isZero();
        assertThat(second.updatedCount()).isEqualTo(3);
        assertThat(twoTableCounts()).containsOnly(
                Map.entry("industry_unlisted_company", 3), Map.entry("industry_funding_event", 0)); // 不新增行
        Map<String, Object> rowA2 = jdbcTemplate.queryForMap(
                "SELECT segment, latest_round, total_funding_yi FROM industry_unlisted_company "
                        + "WHERE company_name = '集成测试A公司'");
        assertThat(rowA2.get("latest_round")).isEqualTo("D"); // 非键字段改写生效
        assertThat(rowA2.get("segment")).isEqualTo("储能");
        assertThat((BigDecimal) rowA2.get("total_funding_yi")).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("轮次非法文件：行错误（行号 3）双计数归零，两表计数不变（all-or-nothing）")
    void givenBadRoundFile_whenImportCompanies_thenRejectedWithZeroWrites() {
        Map<String, Integer> before = twoTableCounts();

        CurationImportResult result = importService.importCompanies(BAD_ROUND_CSV);

        assertThat(result.insertedCount()).isZero();
        assertThat(result.updatedCount()).isZero();
        assertThat(result.rowErrors()).hasSize(1);
        assertThat(result.rowErrors().get(0).row()).isEqualTo(3);
        assertThat(result.rowErrors().get(0).reason()).contains("轮次无效");
        assertThat(twoTableCounts()).isEqualTo(before); // 零写入
    }

    @Test
    @DisplayName("融资事件首导 inserted=2；同键重导 updated=2 金额改写；键内重复文件零落库")
    void givenEventFiles_whenImport_thenInsertUpdateAndRejectDuplicateKeys() {
        CurationImportResult first = importService.importFundingEvents(TWO_EVENT_CSV);
        assertThat(first.insertedCount()).isEqualTo(2);
        assertThat(first.updatedCount()).isZero();

        CurationImportResult second = importService.importFundingEvents(TWO_EVENT_REIMPORT_CSV);
        assertThat(second.insertedCount()).isZero();
        assertThat(second.updatedCount()).isEqualTo(2);
        assertThat(twoTableCounts()).containsOnly(
                Map.entry("industry_unlisted_company", 0), Map.entry("industry_funding_event", 2));
        Map<String, Object> rowB = jdbcTemplate.queryForMap(
                "SELECT amount_yi, investors, source_url FROM industry_funding_event "
                        + "WHERE company_name = '集成测试事件公司' AND round = 'B'");
        assertThat((BigDecimal) rowB.get("amount_yi")).isEqualByComparingTo("9.99");
        assertThat(rowB.get("investors")).isEqualTo("高瓴");
        assertThat(rowB.get("source_url")).isEqualTo("https://example.com/it2");

        Map<String, Integer> before = twoTableCounts();
        CurationImportResult rejected = importService.importFundingEvents(DUP_KEY_EVENT_CSV);
        assertThat(rejected.insertedCount()).isZero();
        assertThat(rejected.updatedCount()).isZero();
        assertThat(rejected.rowErrors()).hasSize(1);
        assertThat(rejected.rowErrors().get(0).row()).isEqualTo(3);
        assertThat(rejected.rowErrors().get(0).reason()).contains("幂等键重复");
        assertThat(twoTableCounts()).isEqualTo(before); // L5 拒绝零落库
    }

    @Test
    @DisplayName("行业码白名单（L3）：未种子的行业码整文件行错误零落库")
    void givenUnmappedIndustryCode_whenImportCompanies_thenRowErrorsPerRow() {
        String csv = THREE_COMPANY_CSV.replace("801770", "999999");

        CurationImportResult result = importService.importCompanies(csv);

        assertThat(result.insertedCount()).isZero();
        assertThat(result.rowErrors()).hasSize(3);
        assertThat(result.rowErrors().get(0).reason()).contains("行业代码不存在").contains("999999");
        assertThat(twoTableCounts()).containsOnly(
                Map.entry("industry_unlisted_company", 0), Map.entry("industry_funding_event", 0));
    }
}
