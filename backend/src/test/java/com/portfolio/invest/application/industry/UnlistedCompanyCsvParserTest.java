package com.portfolio.invest.application.industry;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.industry.FundingRound;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 策展企业 CSV 解析层（L1/L2）纯 JUnit 测试：直接 new 解析器，零 Spring 上下文。
 * 样例与模板常量/集成测试样例三处同源（设计规格 §五，PortfolioImportTemplate 先例）。
 */
class UnlistedCompanyCsvParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    private static final String HEADER_LINE =
            "industry_code,company_name,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note";

    /** 八列标准样例（与模板常量正文逐行同源）。 */
    private static final String EIGHT_COLUMN_CSV = """
            industry_code,company_name,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note
            801730,示例电池科技,动力电池,B,2026-08-15,120.50,动力电池新锐,爱企查人工核对
            """;

    private final UnlistedCompanyCsvParser parser = new UnlistedCompanyCsvParser();

    @Test
    @DisplayName("L1/L2：标准八列文件解析出 ParsedCompany 且字段逐项对齐")
    void givenWellFormedCsv_whenParse_thenOneRowWithAllFields() {
        var out = parser.parse(EIGHT_COLUMN_CSV, TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).hasSize(1);
        var row = out.rows().get(0);
        assertThat(row.rowNumber()).isEqualTo(2); // 行号 = CSV 记录序号（表头为 1）
        assertThat(row.industryCode()).isEqualTo("801730");
        assertThat(row.companyName()).isEqualTo("示例电池科技");
        assertThat(row.segment()).isEqualTo("动力电池");
        assertThat(row.latestRound()).isEqualTo(FundingRound.B);
        assertThat(row.lastFundingDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(row.totalFundingYi()).isEqualByComparingTo("120.50");
        assertThat(row.summary()).isEqualTo("动力电池新锐");
        assertThat(row.sourceNote()).isEqualTo("爱企查人工核对");
    }

    @Test
    @DisplayName("L2：可选列空白落 null（日期/金额/赛道/简介/来源全可缺省）")
    void givenBlankOptionalColumns_whenParse_thenNulls() {
        var out = parser.parse(HEADER_LINE + "\n801730,示例电池科技,,UNKNOWN,,,,\n", TODAY);

        assertThat(out.errors()).isEmpty();
        var row = out.rows().get(0);
        assertThat(row.segment()).isNull();
        assertThat(row.lastFundingDate()).isNull();
        assertThat(row.totalFundingYi()).isNull();
        assertThat(row.summary()).isNull();
        assertThat(row.sourceNote()).isNull();
    }

    @Test
    @DisplayName("L1：BOM 头剥离后正常解析（写模板带 BOM、读侧容忍）")
    void givenBomPrefixedContent_whenParse_thenStrippedAndParsed() {
        var out = parser.parse("﻿" + EIGHT_COLUMN_CSV, TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).hasSize(1);
        assertThat(out.rows().get(0).companyName()).isEqualTo("示例电池科技");
    }

    @Test
    @DisplayName("L1：表头列序错为文件级错误（行号 0），rows 互斥为空")
    void givenWrongHeaderOrder_whenParse_thenFileLevelError() {
        String swapped = "company_name,industry_code,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note"
                + "\n801730,示例电池科技,动力电池,B,2026-08-15,120.50,动力电池新锐,爱企查人工核对\n";

        var out = parser.parse(swapped, TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isZero();
        assertThat(out.errors().get(0).reason()).contains("表头与模板不符").contains(HEADER_LINE);
    }

    @Test
    @DisplayName("L1：空内容/仅表头为文件级错误（无数据行）")
    void givenHeaderOnly_whenParse_thenNoDataRowError() {
        var out = parser.parse(HEADER_LINE + "\n", TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isZero();
        assertThat(out.errors().get(0).reason()).contains("无数据行");
    }

    @Test
    @DisplayName("L1：数据行超 2000 上限为文件级错误")
    void givenTooManyDataRows_whenParse_thenFileLevelError() {
        StringBuilder sb = new StringBuilder(HEADER_LINE).append('\n');
        for (int i = 0; i < 2001; i++) {
            sb.append("801730,示例公司").append(i).append(",赛道,B,2026-01-01,1.00,简介,来源\n");
        }

        var out = parser.parse(sb.toString(), TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isZero();
        assertThat(out.errors().get(0).reason()).contains("数据行超过上限").contains("2001");
    }

    @Test
    @DisplayName("L2 行级错误聚齐：列数不符/必填空/轮次非法/日期晚于今日/金额非法一次全报")
    void givenRowLevelProblems_whenParse_thenAllErrorsAggregatedWithRowNumbers() {
        String csv = HEADER_LINE + "\n" + """
                801730,示例甲公司,赛道甲,X轮,2026-01-01,1.00,简介,来源
                ,示例乙公司,赛道乙,B,2099-01-01,abc,简介,来源
                801730,,赛道丙,B,2026-01-01,1.00,简介,来源
                801730,示例丁公司,赛道丁,,2026-01-01,1.00,简介,来源,多余列
                """;

        var out = parser.parse(csv, TODAY);

        assertThat(out.rows()).isEmpty(); // rows 与 errors 互斥
        assertThat(out.errors()).extracting(CurationImportResult.RowError::row)
                .containsExactly(2, 3, 3, 3, 4, 5);
        assertThat(out.errors()).extracting(CurationImportResult.RowError::reason)
                .anySatisfy(r -> assertThat(r).contains("轮次无效").contains("X轮"))
                .anySatisfy(r -> assertThat(r).contains("行业代码不能为空"))
                .anySatisfy(r -> assertThat(r).contains("不能晚于今日"))
                .anySatisfy(r -> assertThat(r).contains("累计融资额").contains("abc"))
                .anySatisfy(r -> assertThat(r).contains("企业名称不能为空"))
                .anySatisfy(r -> assertThat(r).contains("列数不符"));
    }

    @Test
    @DisplayName("L2：日期格式非法记行错误（yyyy-MM-dd 严格）")
    void givenMalformedDate_whenParse_thenRowError() {
        var out = parser.parse(HEADER_LINE + "\n801730,示例公司,赛道,B,2026/08/15,1.00,简介,来源\n", TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isEqualTo(2);
        assertThat(out.errors().get(0).reason()).contains("日期不可解析").contains("2026/08/15");
    }

    @Test
    @DisplayName("L2：合法边界日期（=今日）通过、晚一日拒绝")
    void givenBoundaryDates_whenParse_thenTodayOkAndTomorrowRejected() {
        var ok = parser.parse(HEADER_LINE + "\n801730,示例公司,赛道,B,2026-09-26,1.00,简介,来源\n", TODAY);
        assertThat(ok.errors()).isEmpty();

        var rejected = parser.parse(HEADER_LINE + "\n801730,示例公司,赛道,B,2026-09-27,1.00,简介,来源\n", TODAY);
        assertThat(rejected.rows()).isEmpty();
        assertThat(rejected.errors()).hasSize(1);
    }

    @Test
    @DisplayName("L2：文本列恰好 V19 上限长度通过、超一字符报「超长」行错误")
    void givenColumnLengthBoundaries_whenParse_thenExactLimitPassesAndOneOverFails() {
        assertLengthBoundary(0, "行业代码", 16);      // industry_code VARCHAR(16)
        assertLengthBoundary(1, "企业名称", 128);     // company_name VARCHAR(128)
        assertLengthBoundary(2, "细分赛道", 64);      // segment VARCHAR(64)
        assertLengthBoundary(6, "简介", 256);         // summary VARCHAR(256)
        assertLengthBoundary(7, "来源标注", 128);     // source_note VARCHAR(128)
    }

    /** 边界断言：恰好 max 字符通过、max+1 报「XX超长（≤max 字符）」（照 CsvImportParserTest 备注超长先例）。 */
    private void assertLengthBoundary(int colIndex, String label, int max) {
        var exact = parser.parse(companyRowWith(colIndex, "长".repeat(max)), TODAY);
        assertThat(exact.errors()).as("%s 恰好 %d 字符应通过", label, max).isEmpty();
        assertThat(exact.rows()).hasSize(1);

        var over = parser.parse(companyRowWith(colIndex, "长".repeat(max + 1)), TODAY);
        assertThat(over.rows()).as("%s 超限应全量拒绝", label).isEmpty();
        assertThat(over.errors()).hasSize(1);
        assertThat(over.errors().get(0).row()).isEqualTo(2);
        assertThat(over.errors().get(0).reason())
                .contains(label + "超长").contains("≤" + max + " 字符");
    }

    @Test
    @DisplayName("L2：累计融资额 NUMERIC(14,2) 边界——满精度 12 位整数通过、≥10^12 报行错误")
    void givenTotalFundingBoundaries_whenParse_thenFullPrecisionPassesAndOverflowFails() {
        var ok = parser.parse(companyRowWith(5, "999999999999.99"), TODAY); // NUMERIC(14,2) 满精度
        assertThat(ok.errors()).isEmpty();
        assertThat(ok.rows().get(0).totalFundingYi()).isEqualByComparingTo("999999999999.99");

        var over = parser.parse(companyRowWith(5, "1000000000000"), TODAY); // 整数位 13 位 ≥10^12 超限
        assertThat(over.rows()).isEmpty();
        assertThat(over.errors()).hasSize(1);
        assertThat(over.errors().get(0).row()).isEqualTo(2);
        assertThat(over.errors().get(0).reason()).contains("累计融资额").contains("NUMERIC(14,2)");
    }

    @Test
    @DisplayName("L1/L2：CRLF 行尾内容正常解析且行尾 CR 不残留字段值（RFC 4180）")
    void givenCrlfLineEndings_whenParse_thenRowsParsed() {
        var out = parser.parse(HEADER_LINE
                + "\r\n801730,示例电池科技,动力电池,B,2026-08-15,120.50,动力电池新锐,爱企查人工核对\r\n", TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).hasSize(1);
        assertThat(out.rows().get(0).companyName()).isEqualTo("示例电池科技");
        assertThat(out.rows().get(0).sourceNote()).isEqualTo("爱企查人工核对");
    }

    @Test
    @DisplayName("L2：数据行间夹空行——空行不计、行号连续（ignoreEmptyLines 口径）")
    void givenBlankLineBetweenDataRows_whenParse_thenRowNumbersContinuous() {
        String csv = HEADER_LINE + "\n"
                + "801730,示例甲公司,赛道,B,2026-01-01,1.00,简介,来源\n"
                + "\n"
                + "801730,示例乙公司,赛道,B,2026-01-02,2.00,简介,来源\n";

        var out = parser.parse(csv, TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).extracting(UnlistedCompanyCsvParser.ParsedCompany::rowNumber)
                .containsExactly(2, 3); // 空行被忽略且不占行号，行号连续
    }

    @Test
    @DisplayName("L1：空串与纯空白输入为文件级「无数据行」错误")
    void givenEmptyOrWhitespaceContent_whenParse_thenFileLevelError() {
        for (String content : new String[] {"", "   \n\n"}) {
            var out = parser.parse(content, TODAY);

            assertThat(out.errors()).as("内容 [%s] 应报无数据行", content).hasSize(1);
            assertThat(out.errors().get(0).row()).isZero();
            assertThat(out.errors().get(0).reason()).contains("无数据行");
            assertThat(out.rows()).isEmpty();
        }
    }

    /** 标准八列样例行中替换第 colIndex 列为 value 后拼成完整 CSV（列长/数值边界用例）。 */
    private static String companyRowWith(int colIndex, String value) {
        String[] cols = {"801730", "示例电池科技", "动力电池", "B", "2026-08-15",
                "120.50", "动力电池新锐", "爱企查人工核对"};
        cols[colIndex] = value;
        return HEADER_LINE + "\n" + String.join(",", cols) + "\n";
    }
}
