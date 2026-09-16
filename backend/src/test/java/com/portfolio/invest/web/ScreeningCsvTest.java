package com.portfolio.invest.web;

import com.portfolio.invest.domain.screening.StockScreeningResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ScreeningCsvTest {

    private static StockScreeningResult row(String code, String name, String pe, String mv) {
        return new StockScreeningResult(code, name, "801780", "银行",
                pe == null ? null : new BigDecimal(pe), new BigDecimal("0.62"), new BigDecimal("5.4"),
                null, null, null, null, null, null, null,
                mv == null ? null : new BigDecimal(mv), null);
    }

    @DisplayName("BOM 首字节 + 中文表头 + CRLF + 总市值换算亿元")
    @Test
    void givenRows_whenToCsv_thenBomHeaderAndConvertedValues() {
        byte[] csv = ScreeningCsv.toCsv(List.of(row("601398", "工商银行", "5.6", "10000000000")));
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(csv[0]).isEqualTo((byte) 0xEF); // BOM EF BB BF
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
        assertThat(text).contains("代码,名称,PE-TTM,PB,股息率,ROE,ROA,毛利率,资产负债率,流动比率,营收增速,净利增速,总市值(亿),换手率");
        assertThat(text).contains("601398,工商银行,5.6,0.62,5.4");
        assertThat(text).contains(",100.00,"); // 10000000000 元 → 100.00 亿
        assertThat(text).contains("\r\n");
    }

    @DisplayName("名称含逗号/引号按 RFC 4180 转义；null 输出空串")
    @Test
    void givenSpecialCharsAndNulls_whenToCsv_thenEscapedAndEmpty() {
        byte[] csv = ScreeningCsv.toCsv(List.of(row("600519", "A\"B,C公司", null, null)));
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("\"A\"\"B,C公司\"");
        // PE 为 null → 名称后紧跟空列；pb/股息率为固定值跟随其后；其余 null 全空
        assertThat(text).contains("600519,\"A\"\"B,C公司\",,0.62,5.4,");
    }

    @DisplayName("空结果仅表头行")
    @Test
    void givenNoRows_whenToCsv_thenHeaderOnly() {
        String text = new String(ScreeningCsv.toCsv(List.of()), StandardCharsets.UTF_8);
        // 去掉 BOM 后应恰为表头一行
        assertThat(text.substring(1).stripTrailing())
                .isEqualTo("代码,名称,PE-TTM,PB,股息率,ROE,ROA,毛利率,资产负债率,流动比率,营收增速,净利增速,总市值(亿),换手率");
    }
}
