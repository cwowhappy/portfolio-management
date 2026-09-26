package com.portfolio.invest.domain.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.BUY;
import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.CASH_DIVIDEND;
import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.SELL;
import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.STOCK_DIVIDEND;
import static com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType.WITHDRAW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV 导入 L5 模拟重放器纯函数测试：全部直调、零 mock。
 * 样例数据约定：组 1/组 2，股 600519。
 * 手算基准：现金 1000000；BUY 100 股 ×1680+费 5 = 168005 → 余 831995
 * （brief 原文"现金 100000"与"余 831995"自相矛盾，以已知答案 831995 为准，起点取 1000000；
 * 100000 专用于第 2 例"现金不足"的负例，见该用例）。
 */
class ImportSimulatorTest {

    @Test
    @DisplayName("买入扣现金加持仓，卖出回现金减持仓")
    void givenBuyThenSellWhenSimulateThenNoError() {
        var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("1000000")), Map.of());
        var rows = List.of(
                row(2, BUY, "2024-01-05", "600519", 1L, "1680.00", "100", "5.00", null),
                row(3, SELL, "2024-01-10", "600519", 1L, "1750.50", "50", "5.00", null));
        assertThat(ImportSimulator.simulate(start, rows)).isEmpty();
    }

    @Test
    @DisplayName("买入现金不足返回行错误并停该组")
    void givenInsufficientCashWhenSimulateThenRowError() {
        var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("100000")), Map.of());
        var rows = List.of(row(2, BUY, "2024-01-05", "600519", 1L, "1680.00", "100", "5.00", null));
        var errors = ImportSimulator.simulate(start, rows);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).row()).isEqualTo(2);
        assertThat(errors.get(0).reason()).contains("现金不足").contains("100000").contains("168005");
    }

    @Test
    @DisplayName("转出后现金为负返回行错误")
    void givenWithdrawExceedsCashWhenSimulateThenRowError() {
        var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("100000")), Map.of());
        var rows = List.of(row(2, WITHDRAW, "2024-01-05", null, 1L, null, null, null, "150000"));
        var errors = ImportSimulator.simulate(start, rows);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).row()).isEqualTo(2);
        assertThat(errors.get(0).reason())
                .contains("转出后分组现金为负")
                .contains("100000")
                .contains("150000");
    }

    @Test
    @DisplayName("卖出超过重放后持仓返回行错误")
    void givenSellExceedsHoldingWhenSimulateThenRowError() {
        // 买 100 卖 200 → 第 3 行 SELL_EXCEEDS 语义错误，文案含"超过持仓"，可用/卖出数均入文案
        var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("1000000")), Map.of());
        var rows = List.of(
                row(2, BUY, "2024-01-05", "600519", 1L, "1680.00", "100", "5.00", null),
                row(3, SELL, "2024-01-10", "600519", 1L, "1750.50", "200", "5.00", null));
        var errors = ImportSimulator.simulate(start, rows);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).row()).isEqualTo(3);
        assertThat(errors.get(0).reason())
                .contains("卖出数量超过持仓")
                .contains("100")
                .contains("200");
    }

    @Test
    @DisplayName("同日多行按文件行序稳定排序")
    void givenSameDayRowsWhenSimulateThenRowNumberBreaksTie() {
        // 行 2=SELL 100（无持仓）、行 3=BUY 100：同日同组——行序排后 SELL 在前 → 行 2 报错
        var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("1000000")), Map.of());
        var sellFirst = List.of(
                row(2, SELL, "2024-01-05", "600519", 1L, "1750.50", "100", "5.00", null),
                row(3, BUY, "2024-01-05", "600519", 1L, "1680.00", "100", "5.00", null));
        var errors = ImportSimulator.simulate(start, sellFirst);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).row()).isEqualTo(2);
        assertThat(errors.get(0).reason()).contains("卖出数量超过持仓");

        // 交换行号（BUY 行号小）则通过——两次调用共用同一 start 也顺带钉住纯函数不改入参
        var buyFirst = List.of(
                row(2, BUY, "2024-01-05", "600519", 1L, "1680.00", "100", "5.00", null),
                row(3, SELL, "2024-01-05", "600519", 1L, "1750.50", "100", "5.00", null));
        assertThat(ImportSimulator.simulate(start, buyFirst)).isEmpty();
    }

    @Test
    @DisplayName("现金分红按当时持仓计现金额，零持仓报行错误")
    void givenCashDividendWithZeroHoldingWhenSimulateThenRowError() {
        // 零持仓：现金分红无股可派 → 行错误
        var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("100000")), Map.of());
        var rows = List.of(row(2, CASH_DIVIDEND, "2024-01-05", "600519", 1L, "2.50", null, null, null));
        var errors = ImportSimulator.simulate(start, rows);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).row()).isEqualTo(2);
        assertThat(errors.get(0).reason()).contains("分红时该标的持仓为 0");

        // 金额 = price × 当时持仓：买 100 股 @10（费 0）→ 现金 1000000-1000=999000；
        // 分红 2.50×100=250 → 999250；随后等额转出 999250 恰好不报错——
        // 分红金额漏计或多计都会使该转出"现金为负"而失败，从错误口观察现金演化
        var holding = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("1000000")), Map.of());
        var replay = List.of(
                row(3, BUY, "2024-01-05", "600519", 1L, "10.00", "100", "0.00", null),
                row(4, CASH_DIVIDEND, "2024-01-06", "600519", 1L, "2.50", null, null, null),
                row(5, WITHDRAW, "2024-01-07", null, 1L, null, null, null, "999250"));
        assertThat(ImportSimulator.simulate(holding, replay)).isEmpty();
    }

    @Test
    @DisplayName("送股按比例放大持仓数量")
    void givenStockDividendWhenSimulateThenQuantityMultiplied() {
        var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("1000000")), Map.of());
        // 买 100 → 送股比例 0.05 → 持仓 100×(1+0.05)=105；SELL 105 通过
        var sell105 = List.of(
                row(2, BUY, "2024-01-05", "600519", 1L, "10.00", "100", "0.00", null),
                row(3, STOCK_DIVIDEND, "2024-01-06", "600519", 1L, "0.05", null, null, null),
                row(4, SELL, "2024-01-07", "600519", 1L, "10.00", "105", "0.00", null));
        assertThat(ImportSimulator.simulate(start, sell105)).isEmpty();

        // SELL 106 报错且可用恰为 105：与上一断言夹住比例计算（未生效则 SELL 105 已失败）
        var sell106 = List.of(
                row(2, BUY, "2024-01-05", "600519", 1L, "10.00", "100", "0.00", null),
                row(3, STOCK_DIVIDEND, "2024-01-06", "600519", 1L, "0.05", null, null, null),
                row(4, SELL, "2024-01-07", "600519", 1L, "10.00", "106", "0.00", null));
        var errors = ImportSimulator.simulate(start, sell106);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).row()).isEqualTo(4);
        assertThat(errors.get(0).reason())
                .contains("卖出数量超过持仓")
                .contains("105")
                .contains("106");
    }

    @Test
    @DisplayName("组内首错即停，其他组不受影响")
    void givenErrorInGroup1WhenSimulateThenGroup2StillChecked() {
        // 组 1 第 2 行现金不足（100000 < 168005）→ 停组 1；组 1 第 5 行若被模拟，
        // 0 持仓卖出会报第三个错——hasSize(2) 同时钉住"首错即停该组"
        var start = new ImportSimulator.StartState(
                Map.of(1L, new BigDecimal("100000"), 2L, new BigDecimal("1000000")), Map.of());
        var rows = List.of(
                row(2, BUY, "2024-01-05", "600519", 1L, "1680.00", "100", "5.00", null),
                row(5, SELL, "2024-01-06", "600519", 1L, "1750.50", "10", "5.00", null),
                row(4, BUY, "2024-01-05", "600519", 2L, "1680.00", "100", "5.00", null),
                row(3, SELL, "2024-01-10", "600519", 2L, "1750.50", "200", "5.00", null));
        var errors = ImportSimulator.simulate(start, rows);
        assertThat(errors).hasSize(2);
        // 错误清单按排序后处理序（groupId, date, rowNumber）输出：组 1 在前
        assertThat(errors.get(0).row()).isEqualTo(2);
        assertThat(errors.get(0).reason()).contains("现金不足");
        assertThat(errors.get(1).row()).isEqualTo(3);
        assertThat(errors.get(1).reason()).contains("卖出数量超过持仓");
    }

    @Test
    @DisplayName("乱序输入按日期排序后演化（历史文件倒序写入场景）")
    void givenUnsortedRowsWhenSimulateThenSortedByDate() {
        // 文件行序：先 2024-06 SELL 50 再 2024-01 BUY 100——排序后先买后卖，无错误；
        // 若按文件行序直放，SELL 先于建仓即报"超过持仓"
        var start = new ImportSimulator.StartState(Map.of(1L, new BigDecimal("1000000")), Map.of());
        var rows = List.of(
                row(2, SELL, "2024-06-01", "600519", 1L, "1750.50", "50", "5.00", null),
                row(3, BUY, "2024-01-05", "600519", 1L, "1680.00", "100", "5.00", null));
        assertThat(ImportSimulator.simulate(start, rows)).isEmpty();
    }

    private static ImportRow row(int rowNumber, ImportRow.ImportRowType type, String date, String stockCode,
                                 Long groupId, String price, String qty, String fee, String amount) {
        // 模拟器测试直给 groupId；stockName/groupName 留 null（模拟器不消费名称，名称链在 T4 fix 落地）
        return new ImportRow(rowNumber, type, LocalDate.parse(date), stockCode, null, null, groupId,
                price == null ? null : new BigDecimal(price),
                qty == null ? null : new BigDecimal(qty),
                fee == null ? null : new BigDecimal(fee),
                amount == null ? null : new BigDecimal(amount), null);
    }
}
