package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.CostMethod;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CSV 导入编排服务（L3 引用校验 + L5 编排 + 执行落库）全链测试：mock PortfolioRepository，
 * 真 CsvImportParser/ImportSimulator 集成（零 Spring 上下文）。样例 CSV 内嵌文本块
 * （九列勘误后格式，与 CsvImportParserTest.SIX_TYPE_CSV 同源：DEPOSIT/WITHDRAW 金额在
 * 第 8 列费用/金额位）。手算基准（六类行文件，起点现金 0、零持仓）：
 * DEPOSIT +200000 → BUY 100×1680+5=168005（余 31995）→ SELL 50×1750.50−5=87520
 * （余 119515）→ CASH_DIV 25.63×50=1281.50（余 120796.50）→ STOCK_DIV ×1.05（50→52.5）
 * → WITHDRAW 10000（余 110796.50）——全链通过；执行终态持仓数量 52.5、累计现金分红 1281.50、
 * netCashFlow −79203.5（=−168005+87520+1281.50），与模拟器终态同构对拍（设计规格 §1.2）。
 */
class PortfolioImportServiceTest {

    private static final String HEADER_LINE = "日期,类型,证券代码,证券名称,分组名称,价格,数量,费用/金额,备注";

    /** 六类型标准样例（§1.1 示例行 + 矩阵对齐修正，同 CsvImportParserTest.SIX_TYPE_CSV）。 */
    private static final String SIX_ROW_CSV = """
            日期,类型,证券代码,证券名称,分组名称,价格,数量,费用/金额,备注
            2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,首次建仓
            2024-06-20,SELL,600519,贵州茅台,主账户,1750.50,50,5.00,
            2024-07-01,CASH_DIVIDEND,600519,贵州茅台,主账户,25.63,,,
            2024-07-01,STOCK_DIVIDEND,600519,贵州茅台,主账户,0.05,,,
            2024-01-03,DEPOSIT,,,主账户,,,200000.00,初始入金
            2024-08-10,WITHDRAW,,,主账户,,,10000.00,出金买房
            """;

    private final PortfolioRepository repo = mock(PortfolioRepository.class);
    private final PortfolioImportService service = new PortfolioImportService(repo);

    @Test
    @DisplayName("六类行标准文件导入成功：importedCount=6 且分组内现金/持仓/记录全部落库")
    void givenValidFileWhenImportThenAllPersisted() {
        givenPortfolioAndMainAccountGroup();
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of());
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of());
        when(repo.findPositionByPortfolioIdAndGroupIdAndStockCode(anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));

        ImportResult r = service.importCsv(1L, SIX_ROW_CSV);

        assertThat(r.rowErrors()).isEmpty();
        assertThat(r.importedCount()).isEqualTo(6);

        // 现金流水 ×2：DEPOSIT/WITHDRAW 字段与 CSV 行一致（执行序=模拟器排序序：DEPOSIT 先于 BUY）
        ArgumentCaptor<CashTransaction> cashCaptor = ArgumentCaptor.forClass(CashTransaction.class);
        verify(repo, times(2)).saveCashTransaction(cashCaptor.capture());
        assertThat(cashCaptor.getAllValues())
                .extracting(CashTransaction::groupId, CashTransaction::type, CashTransaction::txDate,
                        CashTransaction::amount, CashTransaction::note)
                .containsExactly(
                        tuple(1L, CashTransactionType.DEPOSIT, LocalDate.of(2024, 1, 3),
                                new BigDecimal("200000.00"), "初始入金"),
                        tuple(1L, CashTransactionType.WITHDRAW, LocalDate.of(2024, 8, 10),
                                new BigDecimal("10000.00"), "出金买房"));

        // 交易记录 ×2：date/type/price/qty/fee 与 CSV 行一致
        ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
        verify(repo, times(2)).saveTrade(tradeCaptor.capture());
        assertThat(tradeCaptor.getAllValues())
                .extracting(Trade::tradeDate, Trade::type, Trade::price, Trade::quantity, Trade::fee)
                .containsExactly(
                        tuple(LocalDate.of(2024, 1, 5), TradeType.BUY,
                                new BigDecimal("1680.00"), new BigDecimal("100"), new BigDecimal("5.00")),
                        tuple(LocalDate.of(2024, 6, 20), TradeType.SELL,
                                new BigDecimal("1750.50"), new BigDecimal("50"), new BigDecimal("5.00")));

        // 分红记录 ×2：CASH 落每股现金、STOCK 落送股比例（另一列恒 null）
        ArgumentCaptor<Dividend> dividendCaptor = ArgumentCaptor.forClass(Dividend.class);
        verify(repo, times(2)).saveDividend(dividendCaptor.capture());
        assertThat(dividendCaptor.getAllValues())
                .extracting(Dividend::exDate, Dividend::type, Dividend::cashPerShare, Dividend::stockRatio)
                .containsExactly(
                        tuple(LocalDate.of(2024, 7, 1), DividendType.CASH,
                                new BigDecimal("25.63"), null),
                        tuple(LocalDate.of(2024, 7, 1), DividendType.STOCK,
                                null, new BigDecimal("0.05")));

        // 持仓演化 ×4（BUY/SELL/两笔分红逐行 save，同一 (组,股)）：全部指向主账户 600519
        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(repo, times(4)).savePosition(positionCaptor.capture());
        assertThat(positionCaptor.getAllValues())
                .extracting(Position::portfolioId, Position::groupId, Position::stockCode)
                .containsOnly(tuple(10L, 1L, "600519"));
        // 数量演化轨迹 + 终态对拍模拟器：100 → 50（卖 50）→ 50（现金分红不变）→ 52.5（×1.05）
        assertThat(positionCaptor.getAllValues().get(0).quantity()).isEqualByComparingTo("100");
        assertThat(positionCaptor.getAllValues().get(1).quantity()).isEqualByComparingTo("50");
        assertThat(positionCaptor.getAllValues().get(2).quantity()).isEqualByComparingTo("50");
        Position terminal = positionCaptor.getAllValues().get(3);
        assertThat(terminal.quantity()).isEqualByComparingTo("52.5");
        assertThat(terminal.cumulativeCashDividend()).isEqualByComparingTo("1281.5");
        assertThat(terminal.netCashFlow()).isEqualByComparingTo("-79203.5");
    }

    @Test
    @DisplayName("分组不存在返回行错误且零落库（save* 全零调用）")
    void givenUnknownGroupWhenImportThenRowErrorAndNoSave() {
        givenPortfolioAndMainAccountGroup();

        ImportResult r = service.importCsv(1L,
                csv("2024-01-05,BUY,600519,贵州茅台,幽灵账户,1680.00,100,5.00,"));

        assertThat(r.importedCount()).isZero();
        assertThat(r.rowErrors()).hasSize(1);
        assertThat(r.rowErrors().get(0).row()).isEqualTo(2);
        assertThat(r.rowErrors().get(0).reason())
                .contains("分组不存在")
                .contains("幽灵账户")
                .contains("请先在页面创建或修改 CSV");
        verifyNoSave();
    }

    @Test
    @DisplayName("TAG 类型分组返回行错误（仅 ACCOUNT 可导入）")
    void givenTagGroupWhenImportThenRowError() {
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(
                HoldingGroup.reconstitute(2L, 10L, "标签组", GroupType.TAG, Instant.now())));

        ImportResult r = service.importCsv(1L,
                csv("2024-01-03,DEPOSIT,,,标签组,,,100000.00,"));

        assertThat(r.importedCount()).isZero();
        assertThat(r.rowErrors()).hasSize(1);
        assertThat(r.rowErrors().get(0).row()).isEqualTo(2);
        assertThat(r.rowErrors().get(0).reason())
                .contains("标签组")
                .contains("仅支持账户分组");
        verifyNoSave();
    }

    @Test
    @DisplayName("解析层错误直接透传（不触达 repository）")
    void givenMalformedCsvWhenImportThenParseErrorsPassthrough() {
        ImportResult r = service.importCsv(1L,
                csv("2024-01-05,FOO,600519,贵州茅台,主账户,1680.00,100,5.00,"));

        assertThat(r.importedCount()).isZero();
        assertThat(r.rowErrors()).hasSize(1);
        assertThat(r.rowErrors().get(0).row()).isEqualTo(2);
        assertThat(r.rowErrors().get(0).reason())
                .contains("类型无效 FOO")
                .contains("允许：BUY/SELL/CASH_DIVIDEND/STOCK_DIVIDEND/DEPOSIT/WITHDRAW");
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("L5 现金不足零落库：文件 DEPOSIT 100000 + BUY 168005 → 行错误 + save* 全零")
    void givenSimulatedInsufficientCashWhenImportThenNothingPersisted() {
        givenPortfolioAndMainAccountGroup();
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of());
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of());

        ImportResult r = service.importCsv(1L, csv(
                "2024-01-03,DEPOSIT,,,主账户,,,100000.00,",
                "2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,"));

        assertThat(r.importedCount()).isZero();
        assertThat(r.rowErrors()).hasSize(1);
        assertThat(r.rowErrors().get(0).row()).isEqualTo(3);
        assertThat(r.rowErrors().get(0).reason())
                .contains("现金不足")
                .contains("100000")
                .contains("168005");
        verifyNoSave();
    }

    @Test
    @DisplayName("既有持仓作起点：库内已有 100 股（mock findPositionsByGroupId 返回），文件只 SELL 150 → 行错误")
    void givenExistingHoldingAsStartWhenSimulateThenChecked() {
        givenPortfolioAndMainAccountGroup();
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(existingPosition(5L,
                new BigDecimal("100.0000"), new BigDecimal("-168005.0000"))));
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of());

        ImportResult r = service.importCsv(1L,
                csv("2024-06-20,SELL,600519,贵州茅台,主账户,1750.50,150,5.00,"));

        assertThat(r.importedCount()).isZero();
        assertThat(r.rowErrors()).hasSize(1);
        assertThat(r.rowErrors().get(0).row()).isEqualTo(2);
        assertThat(r.rowErrors().get(0).reason())
                .contains("卖出数量超过持仓")
                .contains("100")
                .contains("150");
        verifyNoSave();
    }

    @Test
    @DisplayName("卖出复用既有持仓行（含已清仓行）：SELL 行解析到既有 positionId，不新建 Position")
    void givenExistingPositionRowWhenImportThenReusePositionId() {
        givenPortfolioAndMainAccountGroup();
        // 库内该 (组,股) 已有一条已清仓行（id=5，数量 0）：起点持仓 0、起点现金 0（净流 0）
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(existingPosition(5L,
                new BigDecimal("0.0000"), new BigDecimal("0.0000"))));
        when(repo.findCashTransactionsByGroupId(1L)).thenReturn(List.of());
        when(repo.findPositionByPortfolioIdAndGroupIdAndStockCode(10L, 1L, "600519"))
                .thenReturn(Optional.of(existingPosition(5L,
                        new BigDecimal("0.0000"), new BigDecimal("0.0000"))));
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));

        ImportResult r = service.importCsv(1L, csv(
                "2024-01-03,DEPOSIT,,,主账户,,,100000.00,",
                "2024-01-05,BUY,600519,贵州茅台,主账户,10.00,100,,",
                "2024-01-06,SELL,600519,贵州茅台,主账户,11.00,50,,"));

        assertThat(r.rowErrors()).isEmpty();
        assertThat(r.importedCount()).isEqualTo(3);

        // 两次持仓演化（BUY 重开已清仓行、SELL 续用）都带既有 id=5——不新建 Position
        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(repo, times(2)).savePosition(positionCaptor.capture());
        assertThat(positionCaptor.getAllValues())
                .extracting(Position::id)
                .containsExactly(5L, 5L);
        assertThat(positionCaptor.getAllValues().get(0).quantity()).isEqualByComparingTo("100");
        assertThat(positionCaptor.getAllValues().get(1).quantity()).isEqualByComparingTo("50");

        // 交易记录挂在既有持仓 id 上
        ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
        verify(repo, times(2)).saveTrade(tradeCaptor.capture());
        assertThat(tradeCaptor.getAllValues())
                .extracting(Trade::positionId, Trade::type)
                .containsExactly(tuple(5L, TradeType.BUY), tuple(5L, TradeType.SELL));
    }

    /** userId=1 → portfolio(id=10)，组「主账户」(id=1, ACCOUNT)。 */
    private void givenPortfolioAndMainAccountGroup() {
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(mainAccountGroup()));
    }

    private static Portfolio portfolio() {
        return Portfolio.reconstitute(10L, 1L, CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now());
    }

    private static HoldingGroup mainAccountGroup() {
        return HoldingGroup.reconstitute(1L, 10L, "主账户", GroupType.ACCOUNT, Instant.now());
    }

    /** 库内既有持仓（成本字段取同值简化，测试只关心数量/净流）。 */
    private static Position existingPosition(Long id, BigDecimal quantity, BigDecimal netCashFlow) {
        return Position.reconstitute(id, 10L, 1L, "600519", "贵州茅台",
                quantity, new BigDecimal("168005.0000"), new BigDecimal("168005.0000"),
                new BigDecimal("0.0000"), new BigDecimal("0.0000"), netCashFlow,
                Instant.now(), Instant.now());
    }

    /** 全量拒绝口径：四个写方法全部零调用。 */
    private void verifyNoSave() {
        verify(repo, never()).savePosition(any());
        verify(repo, never()).saveTrade(any());
        verify(repo, never()).saveDividend(any());
        verify(repo, never()).saveCashTransaction(any());
    }

    /** 标准表头 + 逐行拼接数据行。 */
    private static String csv(String... dataRows) {
        StringBuilder sb = new StringBuilder(HEADER_LINE).append('\n');
        for (String row : dataRows) {
            sb.append(row).append('\n');
        }
        return sb.toString();
    }
}
