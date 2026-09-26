package com.portfolio.invest.application.industry;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.industry.FundingRound;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 融资事件 CSV 解析层（L1/L2）纯 JUnit 测试：直接 new 解析器，零 Spring 上下文。
 * 样例与模板常量/集成测试样例三处同源（设计规格 §五）。样例行轮次写 B_PLUS——规格示例
 * 原文「B+」与 FundingRound 枚举 CSV 输入格式（下划线大写，§三）矛盾，以枚举格式为准
 * （CsvImportParserTest 矩阵勘误同类先例）。
 */
class FundingEventCsvParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    private static final String HEADER_LINE =
            "event_date,company_name,round,amount_yi,investors,industry_code,segment,source_title,source_url";

    /** 九列标准样例（与模板常量正文逐行同源）。 */
    private static final String NINE_COLUMN_CSV = """
            event_date,company_name,round,amount_yi,investors,industry_code,segment,source_title,source_url
            2026-08-15,某生物科技公司,B_PLUS,3.20,高瓴·红杉,801150,CXO,睿兽分析2026-08月报,
            """;

    private final FundingEventCsvParser parser = new FundingEventCsvParser();

    @Test
    @DisplayName("L1/L2：标准九列文件解析出 ParsedFundingEvent 且字段逐项对齐")
    void givenWellFormedCsv_whenParse_thenOneRowWithAllFields() {
        var out = parser.parse(NINE_COLUMN_CSV, TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).hasSize(1);
        var row = out.rows().get(0);
        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.eventDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(row.companyName()).isEqualTo("某生物科技公司");
        assertThat(row.round()).isEqualTo(FundingRound.B_PLUS);
        assertThat(row.amountYi()).isEqualByComparingTo("3.20");
        assertThat(row.investors()).isEqualTo("高瓴·红杉");
        assertThat(row.industryCode()).isEqualTo("801150");
        assertThat(row.segment()).isEqualTo("CXO");
        assertThat(row.sourceTitle()).isEqualTo("睿兽分析2026-08月报");
        assertThat(row.sourceUrl()).isNull(); // 月报类无逐条 URL，空落 null
    }

    @Test
    @DisplayName("L2：可选列空白落 null（金额/投资方/赛道/URL 可缺省，来源标题必填）")
    void givenBlankOptionalColumns_whenParse_thenNulls() {
        var out = parser.parse(HEADER_LINE + "\n2026-08-15,某公司,B,,,801150,,公开报道标题,\n", TODAY);

        assertThat(out.errors()).isEmpty();
        var row = out.rows().get(0);
        assertThat(row.amountYi()).isNull();
        assertThat(row.investors()).isNull();
        assertThat(row.segment()).isNull();
        assertThat(row.sourceUrl()).isNull();
    }

    @Test
    @DisplayName("L1：BOM 头剥离后正常解析（写模板带 BOM、读侧容忍）")
    void givenBomPrefixedContent_whenParse_thenStrippedAndParsed() {
        var out = parser.parse("﻿" + NINE_COLUMN_CSV, TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).hasSize(1);
        assertThat(out.rows().get(0).companyName()).isEqualTo("某生物科技公司");
    }

    @Test
    @DisplayName("L1：表头列序错为文件级错误（行号 0）")
    void givenWrongHeaderOrder_whenParse_thenFileLevelError() {
        String swapped = "company_name,event_date,round,amount_yi,investors,industry_code,segment,source_title,source_url"
                + "\n2026-08-15,某生物科技公司,B_PLUS,3.20,高瓴·红杉,801150,CXO,睿兽分析月报,\n";

        var out = parser.parse(swapped, TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isZero();
        assertThat(out.errors().get(0).reason()).contains("表头与模板不符").contains(HEADER_LINE);
    }

    @Test
    @DisplayName("L1：仅表头为文件级错误（无数据行）")
    void givenHeaderOnly_whenParse_thenNoDataRowError() {
        var out = parser.parse(HEADER_LINE + "\n", TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).reason()).contains("无数据行");
    }

    @Test
    @DisplayName("L1：数据行超 2000 上限为文件级错误")
    void givenTooManyDataRows_whenParse_thenFileLevelError() {
        StringBuilder sb = new StringBuilder(HEADER_LINE).append('\n');
        for (int i = 0; i < 2001; i++) {
            sb.append("2026-08-15,某公司").append(i).append(",B,1.00,投资方,801150,赛道,来源标题,\n");
        }

        var out = parser.parse(sb.toString(), TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).reason()).contains("数据行超过上限").contains("2001");
    }

    @Test
    @DisplayName("L2 行级错误聚齐：五个必填空/轮次非法/日期晚于今日/金额非法一次全报")
    void givenRowLevelProblems_whenParse_thenAllErrorsAggregatedWithRowNumbers() {
        String csv = HEADER_LINE + "\n" + """
                2026-08-15,某甲公司,B_PLUS,3.20,投资方,801150,赛道,来源标题,https://example.com/a
                ,某乙公司,B_PLUS,abc,投资方,,赛道,来源标题,
                2026-08-15,,X轮,3.20,投资方,801150,赛道,,
                2099-01-01,某丙公司,B_PLUS,3.20,投资方,801150,赛道,来源标题,
                """;

        var out = parser.parse(csv, TODAY);

        assertThat(out.rows()).isEmpty(); // rows 与 errors 互斥
        assertThat(out.errors()).extracting(CurationImportResult.RowError::row)
                .containsExactly(3, 3, 3, 4, 4, 4, 5);
        assertThat(out.errors()).extracting(CurationImportResult.RowError::reason)
                .anySatisfy(r -> assertThat(r).contains("事件日期不能为空"))
                .anySatisfy(r -> assertThat(r).contains("行业代码不能为空"))
                .anySatisfy(r -> assertThat(r).contains("金额").contains("abc"))
                .anySatisfy(r -> assertThat(r).contains("企业名称不能为空"))
                .anySatisfy(r -> assertThat(r).contains("轮次无效").contains("X轮"))
                .anySatisfy(r -> assertThat(r).contains("来源标题不能为空"))
                .anySatisfy(r -> assertThat(r).contains("不能晚于今日"));
    }

    @Test
    @DisplayName("L2：日期格式非法记行错误（yyyy-MM-dd 严格）")
    void givenMalformedDate_whenParse_thenRowError() {
        var out = parser.parse(HEADER_LINE + "\n2026/08/15,某公司,B,1.00,投资方,801150,赛道,来源,\n", TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).reason()).contains("日期不可解析").contains("2026/08/15");
    }

    @Test
    @DisplayName("L2：合法边界日期（=今日）通过、晚一日拒绝")
    void givenBoundaryDates_whenParse_thenTodayOkAndTomorrowRejected() {
        var ok = parser.parse(HEADER_LINE + "\n2026-09-26,某公司,B,1.00,投资方,801150,赛道,来源,\n", TODAY);
        assertThat(ok.errors()).isEmpty();

        var rejected = parser.parse(HEADER_LINE + "\n2026-09-27,某公司,B,1.00,投资方,801150,赛道,来源,\n", TODAY);
        assertThat(rejected.rows()).isEmpty();
        assertThat(rejected.errors()).hasSize(1);
    }
}
