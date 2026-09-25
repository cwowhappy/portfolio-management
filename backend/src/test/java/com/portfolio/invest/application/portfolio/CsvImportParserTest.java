package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.ImportRow;
import com.portfolio.invest.domain.portfolio.ImportSimulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.BUY;
import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.CASH_DIVIDEND;
import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.DEPOSIT;
import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.SELL;
import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.STOCK_DIVIDEND;
import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.WITHDRAW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV 导入解析层（L1/L2/L4）纯 JUnit 测试：直接 new CsvImportParser，零 Spring 上下文。
 * 样例 CSV 内嵌文本块；表头与六类型示例行照设计规格 §1.1，其中 DEPOSIT/WITHDRAW 两行
 * 按同节列约束矩阵修正：金额写在第 8 列（费用/金额）、DEPOSIT 补必填分组名称——规格
 * 示例行原文把金额错位到数量/价格列（WITHDRAW 且缺第 9 列）、DEPOSIT 缺分组名，与本节
 * 矩阵（分组必填、金额&gt;0、价格/数量必空）矛盾，以矩阵为准（Task 2 勘误同类先例）。
 */
class CsvImportParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    private static final String HEADER_LINE = "日期,类型,证券代码,证券名称,分组名称,价格,数量,费用/金额,备注";

    /** 六类型标准样例（§1.1 示例行 + 上述两处矩阵对齐修正）。 */
    private static final String SIX_TYPE_CSV = """
            日期,类型,证券代码,证券名称,分组名称,价格,数量,费用/金额,备注
            2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,首次建仓
            2024-06-20,SELL,600519,贵州茅台,主账户,1750.50,50,5.00,
            2024-07-01,CASH_DIVIDEND,600519,贵州茅台,主账户,25.63,,,
            2024-07-01,STOCK_DIVIDEND,600519,贵州茅台,主账户,0.05,,,
            2024-01-03,DEPOSIT,,,主账户,,,200000.00,初始入金
            2024-08-10,WITHDRAW,,,主账户,,,10000.00,出金买房
            """;

    private final CsvImportParser parser = new CsvImportParser();

    @Test
    @DisplayName("L1：标准文件解析出六行 ImportRow 且类型映射正确")
    void givenWellFormedCsvWhenParseThenSixRows() {
        var out = parser.parse(SIX_TYPE_CSV, TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).hasSize(6);
        assertThat(out.rows()).extracting(ImportRow::type)
                .containsExactly(BUY, SELL, CASH_DIVIDEND, STOCK_DIVIDEND, DEPOSIT, WITHDRAW);
        assertThat(out.rows()).extracting(ImportRow::rowNumber).containsExactly(2, 3, 4, 5, 6, 7);
        assertThat(out.rows()).extracting(ImportRow::groupName).containsOnly("主账户");
        assertThat(out.rows()).extracting(ImportRow::groupId).containsOnlyNulls();

        ImportRow buy = out.rows().get(0);
        assertThat(buy.date()).isEqualTo(LocalDate.of(2024, 1, 5));
        assertThat(buy.stockCode()).isEqualTo("600519");
        assertThat(buy.stockName()).isEqualTo("贵州茅台");
        assertThat(buy.price()).isEqualTo(new BigDecimal("1680.00"));
        assertThat(buy.quantity()).isEqualTo(new BigDecimal("100"));
        assertThat(buy.fee()).isEqualTo(new BigDecimal("5.00"));
        assertThat(buy.amount()).isNull();
        assertThat(buy.note()).isEqualTo("首次建仓");

        ImportRow sell = out.rows().get(1);
        assertThat(sell.fee()).isEqualTo(new BigDecimal("5.00"));
        assertThat(sell.note()).isNull();

        ImportRow cashDividend = out.rows().get(2);
        assertThat(cashDividend.price()).isEqualTo(new BigDecimal("25.63"));
        assertThat(cashDividend.quantity()).isNull();
        assertThat(cashDividend.fee()).isNull();
        assertThat(cashDividend.amount()).isNull();

        ImportRow deposit = out.rows().get(4);
        assertThat(deposit.stockCode()).isNull();
        assertThat(deposit.stockName()).isNull();
        assertThat(deposit.price()).isNull();
        assertThat(deposit.quantity()).isNull();
        assertThat(deposit.amount()).isEqualByComparingTo("200000.00");
        assertThat(deposit.note()).isEqualTo("初始入金");

        ImportRow withdraw = out.rows().get(5);
        assertThat(withdraw.amount()).isEqualByComparingTo("10000.00");
        assertThat(withdraw.note()).isEqualTo("出金买房");
    }

    @Test
    @DisplayName("L1：UTF-8 BOM 前缀不破坏表头匹配")
    void givenBomHeaderWhenParseThenOk() {
        var out = parser.parse("\uFEFF" + SIX_TYPE_CSV, TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).hasSize(6);
    }

    @Test
    @DisplayName("L1：列数不符返回行号错误")
    void givenEightColumnsWhenParseThenRowError() {
        var out = parser.parse(csv(
                "2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,5.00",   // 8 列（缺末列空备注）
                "2024-01-06,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,"),  // 合法行，仍被逐行校验
                TODAY);

        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isEqualTo(2);
        assertThat(out.errors().get(0).reason()).contains("期望 9 列").contains("实际 8 列");
        assertThat(out.rows()).isEmpty();
    }

    @Test
    @DisplayName("L1：表头与模板不符返回文件级错误（row=0）")
    void givenWrongHeaderWhenParseThenFileError() {
        String content = "日期,类型,代码,名称,分组,价格,数量,费用,备注\n"
                + "2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,首次建仓\n";

        var out = parser.parse(content, TODAY);

        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isZero();
        assertThat(out.errors().get(0).reason()).contains("表头");
        assertThat(out.rows()).isEmpty();
    }

    @Test
    @DisplayName("L1：空内容与仅表头均返回文件级「无数据行」错误")
    void givenEmptyOrHeaderOnlyContentWhenParseThenFileError() {
        for (String content : new String[] {"", "   \n\n", HEADER_LINE + "\n"}) {
            var out = parser.parse(content, TODAY);

            assertThat(out.errors()).as("内容 [%s] 应报无数据行", content).hasSize(1);
            assertThat(out.errors().get(0).row()).isZero();
            assertThat(out.errors().get(0).reason()).contains("无数据行");
            assertThat(out.rows()).isEmpty();
        }
    }

    @Test
    @DisplayName("L1：超 2000 数据行返回文件级 ParseOutcome 错误（row=0）")
    void givenOverLimitRowsWhenParseThenFileError() {
        var out = parser.parse(depositRows(2001), TODAY);

        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isZero();
        assertThat(out.errors().get(0).reason()).contains("最多 2000 行");
        assertThat(out.rows()).isEmpty();
    }

    @Test
    @DisplayName("L1：恰好 2000 数据行在上限内正常解析")
    void givenExactlyLimitRowsWhenParseThenOk() {
        var out = parser.parse(depositRows(2000), TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).hasSize(2000);
    }

    @Test
    @DisplayName("L2：类型枚举外值返回行错误")
    void givenInvalidTypeWhenParseThenRowError() {
        var out = parser.parse(csv("2024-01-05,FOO,600519,贵州茅台,主账户,1680.00,100,5.00,"), TODAY);

        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isEqualTo(2);
        assertThat(out.errors().get(0).reason())
                .contains("类型无效 FOO")
                .contains("允许：BUY/SELL/CASH_DIVIDEND/STOCK_DIVIDEND/DEPOSIT/WITHDRAW");
        assertThat(out.rows()).isEmpty();
    }

    @Test
    @DisplayName("L2：BUY 负价格/零价格返回行错误；DEPOSIT 金额≤0 返回行错误")
    void givenNonPositiveNumbersWhenParseThenRowError() {
        var out = parser.parse(csv(
                "2024-01-05,BUY,600519,贵州茅台,主账户,-1,100,5.00,",
                "2024-01-05,BUY,600519,贵州茅台,主账户,0,100,5.00,",
                "2024-01-03,DEPOSIT,,,主账户,,,0,"), TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(3);
        assertThat(out.errors()).extracting(ImportSimulator.RowError::row).containsExactly(2, 3, 4);
        assertThat(out.errors().get(0).reason()).contains("价格必须大于 0");
        assertThat(out.errors().get(1).reason()).contains("价格必须大于 0");
        assertThat(out.errors().get(2).reason()).contains("金额必须大于 0");
    }

    @Test
    @DisplayName("L2：数值列不可解析返回行错误")
    void givenNonNumericFieldsWhenParseThenRowError() {
        var out = parser.parse(csv(
                "2024-01-05,BUY,600519,贵州茅台,主账户,abc,100,5.00,",
                "2024-01-03,DEPOSIT,,,主账户,,,xyz,"), TODAY);

        assertThat(out.errors()).hasSize(2);
        assertThat(out.errors().get(0).row()).isEqualTo(2);
        assertThat(out.errors().get(0).reason()).contains("数值无效").contains("价格").contains("abc");
        assertThat(out.errors().get(1).row()).isEqualTo(3);
        assertThat(out.errors().get(1).reason()).contains("数值无效").contains("金额").contains("xyz");
        assertThat(out.rows()).isEmpty();
    }

    @Test
    @DisplayName("L2：日期不可解析返回行错误")
    void givenMalformedDateWhenParseThenRowError() {
        var out = parser.parse(csv("2024/01/05,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,"), TODAY);

        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isEqualTo(2);
        assertThat(out.errors().get(0).reason()).contains("日期不可解析").contains("2024/01/05");
        assertThat(out.rows()).isEmpty();
    }

    @Test
    @DisplayName("L2：类型相关列空值约束（BUY 缺数量、DEPOSIT 带证券代码均报错）")
    void givenTypeColumnMismatchWhenParseThenRowError() {
        var out = parser.parse(csv(
                "2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,,5.00,",        // BUY 缺数量
                "2024-01-03,DEPOSIT,600519,,主账户,,,100.00,",                  // DEPOSIT 带证券代码
                "2024-01-03,DEPOSIT,,,主账户,100.00,,100.00,",                  // DEPOSIT 带价格
                "2024-07-01,CASH_DIVIDEND,600519,贵州茅台,主账户,25.63,100,,",  // 分红数量必空
                "2024-07-01,CASH_DIVIDEND,600519,贵州茅台,主账户,25.63,,5.00,", // 分红费用/金额必空
                "2024-01-05,BUY,600519,贵州茅台,,1680.00,100,5.00,"),           // BUY 缺分组名称
                TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(6);
        assertThat(out.errors()).extracting(ImportSimulator.RowError::row)
                .containsExactly(2, 3, 4, 5, 6, 7);
        assertThat(out.errors().get(0).reason()).contains("数量必须大于 0");
        assertThat(out.errors().get(1).reason()).contains("证券代码列必须为空");
        assertThat(out.errors().get(2).reason()).contains("价格列必须为空");
        assertThat(out.errors().get(3).reason()).contains("数量列必须为空");
        assertThat(out.errors().get(4).reason()).contains("费用/金额列必须为空");
        assertThat(out.errors().get(5).reason()).contains("分组名称不能为空");
    }

    @Test
    @DisplayName("L2：BUY 负费用返回行错误")
    void givenNegativeBuyFeeWhenParseThenRowError() {
        var out = parser.parse(csv("2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,-5.00,"), TODAY);

        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isEqualTo(2);
        assertThat(out.errors().get(0).reason()).contains("费用不能为负数");
        assertThat(out.rows()).isEmpty();
    }

    @Test
    @DisplayName("L2：BUY 费用列可空默认为 0")
    void givenBuyWithEmptyFeeWhenParseThenFeeDefaultsToZero() {
        var out = parser.parse(csv("2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,,"), TODAY);

        assertThat(out.errors()).isEmpty();
        assertThat(out.rows()).hasSize(1);
        assertThat(out.rows().get(0).fee()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("L2：卖出费用不低于卖出金额（fee≥价格×数量）返回行错误")
    void givenSellFeeNotLessThanProceedsWhenParseThenRowError() {
        // 上游评审补充校验：fee 恰等于 价格×数量（10×100=1000）也在拒绝之列（≥ 即错）
        var out = parser.parse(csv(
                "2024-06-20,SELL,600519,贵州茅台,主账户,10.00,100,1000.00,",
                "2024-06-20,SELL,600519,贵州茅台,主账户,10.00,100,1000.01,"), TODAY);

        assertThat(out.rows()).isEmpty();
        assertThat(out.errors()).hasSize(2);
        assertThat(out.errors().get(0).row()).isEqualTo(2);
        assertThat(out.errors().get(0).reason()).contains("卖出费用不能超过卖出金额");
        assertThat(out.errors().get(1).row()).isEqualTo(3);

        // fee 严格小于卖出金额则通过
        var ok = parser.parse(csv("2024-06-20,SELL,600519,贵州茅台,主账户,10.00,100,999.99,"), TODAY);
        assertThat(ok.errors()).isEmpty();
        assertThat(ok.rows()).hasSize(1);
    }

    @Test
    @DisplayName("L4：日期晚于 today 参数返回行错误")
    void givenFutureDateWhenParseThenRowError() {
        var out = parser.parse(csv("2026-09-26,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,"), TODAY);

        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isEqualTo(2);
        assertThat(out.errors().get(0).reason()).contains("日期不能晚于今日");
        assertThat(out.rows()).isEmpty();

        // 边界：恰等于 today 不算未来（isAfter 严格比较），历史日期亦通过
        var boundary = parser.parse(csv(
                "2026-09-25,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,",
                "2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,"), TODAY);
        assertThat(boundary.errors()).isEmpty();
        assertThat(boundary.rows()).hasSize(2);
    }

    @Test
    @DisplayName("L2：备注超过 255 字符返回行错误")
    void givenOversizedNoteWhenParseThenRowError() {
        String longNote = "长".repeat(256);
        var out = parser.parse(csv("2024-01-03,DEPOSIT,,,主账户,,,100.00," + longNote), TODAY);

        assertThat(out.errors()).hasSize(1);
        assertThat(out.errors().get(0).row()).isEqualTo(2);
        assertThat(out.errors().get(0).reason()).contains("备注").contains("255");
        assertThat(out.rows()).isEmpty();
    }

    /** 标准表头 + 逐行拼接数据行。 */
    private static String csv(String... dataRows) {
        StringBuilder sb = new StringBuilder(HEADER_LINE).append('\n');
        for (String row : dataRows) {
            sb.append(row).append('\n');
        }
        return sb.toString();
    }

    /** 生成 count 行合法 DEPOSIT 数据（行数上限边界用例）。 */
    private static String depositRows(int count) {
        StringBuilder sb = new StringBuilder(HEADER_LINE).append('\n');
        for (int i = 0; i < count; i++) {
            sb.append("2024-01-03,DEPOSIT,,,主账户,,,100.00,").append('\n');
        }
        return sb.toString();
    }
}
