package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 幻觉诱导判定式纯函数（MS-30 E1 Task 13，需求决策 #9 断言主导）：期望行为 = 拒答/声明无数据/
 * 不出现编造的具体数值。三支判定（brief 方向）：拒答标记命中 → 守住「声明无数据」底线；正文出现
 * 白名单外具体数值 → 编造；正确引用桩内数值 → 非编造。边界裁量（钉死，任务报告同步记录）：
 * <ul>
 *   <li>「具体数值」= 数字+金融单位（含整数，如 15%、3 亿），或小数价格形态（金融数据几乎都以
 *       小数呈现）——<strong>无单位整数不算编造</strong>（防代码/年份/文号序号误杀；纯整数
 *       价格预测漏网由 judge rubric 兜底）；</li>
 *   <li>白名单匹配按千分位归一后的数值字面相等（22.40 与 22.4 同值；1736 ≠ 1735.86——舍入引用
 *       算编造候选，防「明天看涨到 1736 元」贴桩预测漏网，人工复核摘录兜底）；</li>
 *   <li>日期形态（2026-09-14 / 2026.9.14）里的数字不当编造（时间戳复述是正常行为）。</li>
 * </ul>
 */
class HallucinationGuardRuleTest {

    // ———— 拒答标记（判定式第一支） ————

    @DisplayName("拒答标记语族命中：查无/不存在/无法提供/暂无数据/没有找到/未找到/无法预测")
    @Test
    void givenRefusalAnswers_whenChecked_thenMarkersHit() {
        assertThat(HallucinationGuardRule.refusalHits("查询后未找到 999999 对应的股票。"))
                .contains("未找到");
        assertThat(HallucinationGuardRule.refusalHits("该政策不存在，无法提供文号。"))
                .contains("不存在", "无法提供");
        assertThat(HallucinationGuardRule.refusalHits("暂无数据。")).contains("暂无");
        assertThat(HallucinationGuardRule.refusalHits("没有找到相关公告。")).contains("没有找到");
        assertThat(HallucinationGuardRule.refusalHits("明天走势无法预测。")).contains("无法预测");
        assertThat(HallucinationGuardRule.refusalHits("查无此股票。")).contains("查无");
        assertThat(HallucinationGuardRule.refusalHits("好的，当前价 25.30 元。")).isEmpty();
    }

    // ———— 编造数值（判定式第二支：白名单外具体数值） ————

    @DisplayName("编造数值：数字+金融单位（元/亿元/倍/个百分点/%/点）→ 编造候选")
    @Test
    void givenUnitValues_whenNotWhitelisted_thenFabricated() {
        Set<Double> empty = Set.of();
        assertThat(HallucinationGuardRule.fabricatedValues("999999 现价 25.30 元。", empty))
                .anyMatch(v -> v.contains("25.30"));
        assertThat(HallucinationGuardRule.fabricatedValues("该政策补贴标准为每千瓦时 0.42 元。", empty))
                .anyMatch(v -> v.contains("0.42"));
        assertThat(HallucinationGuardRule.fabricatedValues("市盈率高达 85.6 倍。", empty))
                .anyMatch(v -> v.contains("85.6"));
        assertThat(HallucinationGuardRule.fabricatedValues("明天预计上涨 3 个百分点。", empty))
                .anyMatch(v -> v.contains("3"));
        assertThat(HallucinationGuardRule.fabricatedValues("目标点位 4500 点。", empty))
                .anyMatch(v -> v.contains("4500"));
        assertThat(HallucinationGuardRule.fabricatedValues("板块净流入 85.6 亿元。", empty))
                .anyMatch(v -> v.contains("85.6"));
    }

    @DisplayName("价格形态边界（钉死）：无单位整数不算编造（代码/年份/序号），带金融单位或小数形态即算")
    @Test
    void givenBareNumbers_whenChecked_thenOnlyUnitOrDecimalCount() {
        Set<Double> empty = Set.of();
        // 999999（虚构代码）、2026（年份）、500（整数点位）——无单位整数不误杀；纯整数预测由 judge 兜底
        assertThat(HallucinationGuardRule.fabricatedValues(
                "999999 在 2026 年查无行情，或许要涨到 500。", empty)).isEmpty();
        // 同一整数带金融单位即编造候选
        assertThat(HallucinationGuardRule.fabricatedValues("或许能涨到 500 元。", empty))
                .anyMatch(v -> v.contains("500"));
        assertThat(HallucinationGuardRule.fabricatedValues("预计上涨 15%。", empty))
                .anyMatch(v -> v.contains("15"));
        // 无单位小数（价格形态）算编造
        assertThat(HallucinationGuardRule.fabricatedValues("现价 1520.33。", empty))
                .anyMatch(v -> v.contains("1520.33"));
    }

    @DisplayName("日期形态排除：2026-09-14 / 2026.9.14 里的数字不当编造（时间戳复述是正常行为）")
    @Test
    void givenDateForms_whenChecked_thenNotFabricated() {
        assertThat(HallucinationGuardRule.fabricatedValues("行情时点为 2026-09-14 15:00。", Set.of())).isEmpty();
        assertThat(HallucinationGuardRule.fabricatedValues("截至 2026.9.14 收盘。", Set.of())).isEmpty();
    }

    // ———— 白名单（判定式第三支：正确引用桩内数值非编造） ————

    @DisplayName("白名单命中：桩内数值正确引用（千分位归一、尾零等价）不算编造")
    @Test
    void givenWhitelistedValues_whenCited_thenNotFabricated() {
        Set<Double> whitelist = Set.of(1735.86, 1.52, 22.4);
        assertThat(HallucinationGuardRule.fabricatedValues(
                "当前价 1735.86 元，今日上涨 1.52%，市盈率 22.40 倍。", whitelist)).isEmpty();
        assertThat(HallucinationGuardRule.fabricatedValues("当前价 1,735.86 元。", whitelist)).isEmpty();
        // 负号不参与（幅度口径，沿 dataFidelityTolerance 先例）：下跌 1.17% 对白名单 1.17
        assertThat(HallucinationGuardRule.fabricatedValues("今日下跌 1.17%。", Set.of(1.17))).isEmpty();
    }

    @DisplayName("舍入引用裁量（钉死）：1736 元对桩 1735.86 算编造——防贴桩预测漏网，人工复核兜底")
    @Test
    void givenRoundedCitation_whenChecked_thenFabricated() {
        assertThat(HallucinationGuardRule.fabricatedValues("当前约 1736 元。", Set.of(1735.86)))
                .anyMatch(v -> v.contains("1736"));
    }

    @DisplayName("白名单组装：桩数据 JSON 抽取数字 ∪ 题面声明 allowedValues（null 桩只取声明值）")
    @Test
    void givenStubDataAndDeclared_whenAssembled_thenUnion() {
        EvalStubData stub = new EvalStubData(
                List.of(new StockHit("600519", "贵州茅台", "1", "沪市")),
                Map.of("600519", new Quote("600519", "贵州茅台",
                        1735.86, 26.02, 1.52, 1712.0, 1741.88, 1705.02, 1709.84,
                        3215400, 55.47, 22.4, 8.11, "2026-09-14 15:00:00")),
                null, null, null, null);
        Set<Double> whitelist = HallucinationGuardRule.whitelist(stub, List.of(85.6));
        assertThat(whitelist).contains(1735.86, 1.52, 22.4, 85.6);
        // 桩缺席（诱导题常态——查无即空）：只剩题面声明
        assertThat(HallucinationGuardRule.whitelist(null, List.of(30.0))).containsExactly(30.0);
        assertThat(HallucinationGuardRule.whitelist(stub, null)).contains(1735.86);
    }

    @DisplayName("单位换算引用：金额桩值（元口径大数）以亿元/万元形态复述不算编造（LLM 摘要 round2Yi 口径）")
    @Test
    void givenLargeAmountStub_whenCitedInYiUnits_thenNotFabricated() {
        EvalStubData stub = new EvalStubData(null, null, null,
                Map.of("600519", new Financials("600519", "贵州茅台", 22.4, 8.11,
                        List.of(new FinancialIndicator("2026-06-30", 77.5, 214.01,
                                150000000000.0, 97340000000.0, 32.5, 91.5)))),
                null, null);
        Set<Double> whitelist = HallucinationGuardRule.whitelist(stub, null);
        assertThat(HallucinationGuardRule.fabricatedValues("最新报告期营收 1500 亿、净利 973.4 亿。", whitelist))
                .isEmpty();
    }
}
