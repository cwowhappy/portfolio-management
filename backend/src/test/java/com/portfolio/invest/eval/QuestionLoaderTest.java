package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.eval.EvalRegressionJudge;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 题库装载冒烟（MS-30 E1）：对 classpath 真题库走 {@link QuestionLoader#loadFromClasspath()}
 * 全量 schema 校验——与 {@code make eval-agent EVAL_ARGS="--list"} 干跑同一实现（题目是测试资产
 * 本身，装载校验即测试）。钉 MS-30 E1 新分类 MARKET_FACT：新分类题可装载、stub 模式、judge 引用
 * rubric-answer-quality、断言维面 dataFidelity 全量声明（entityAlignment 全量声明，唯一豁免是
 * 无参工具的大盘指数题——无入参可对齐，沿 st-overview-market 先例）；并钉 category 词表与 main 侧
 * {@code EvalRegressionJudge.NEW_CATEGORIES} 的逐字对齐（Task 5 传导：错字则分类判定静默旁路）。
 * Task 12 同口径钉 METRIC_CALC：容差维面 dataFidelityTolerance 全量声明、entityAlignment
 * 无豁免（计算类必须先对准标的/数据源，容差锚才有意义）。
 */
class QuestionLoaderTest {

    @DisplayName("装载全量题库不抛：MARKET_FACT 恰 10 题且全部通过 schema 校验（stub + judge + dataFidelity）")
    @Test
    void givenClasspathBank_whenLoaded_thenTenMarketFactQuestionsPassSchema() {
        List<EvalQuestion> all = QuestionLoader.loadFromClasspath();
        List<EvalQuestion> marketFact = all.stream()
                .filter(q -> "MARKET_FACT".equals(q.category())).toList();

        assertThat(marketFact).hasSize(10);
        assertThat(marketFact).allSatisfy(q -> {
            assertThat(q.mode()).isEqualTo("stub");
            assertThat(q.judge()).isEqualTo("rubric-answer-quality");
            assertThat(q.expect().declaredDimensions()).contains("dataFidelity");
        });
        // 实体对齐唯一的无声明题：无参工具的大盘题（get_market_overview 无入参可对齐）
        List<String> withoutAlignment = marketFact.stream()
                .filter(q -> q.expect().entityAlignment() == null)
                .map(EvalQuestion::id).toList();
        assertThat(withoutAlignment).containsExactly("mf-overview-shindex");
    }

    @DisplayName("category 词表与 judge 常量逐字对齐：MARKET_FACT 大写同现于题库词表与 NEW_CATEGORIES")
    @Test
    void givenCategoryVocabulary_whenCheckedAgainstJudgeConstants_thenAlignedVerbatim() {
        assertThat(EvalQuestion.CATEGORIES).contains("MARKET_FACT");
        assertThat(EvalRegressionJudge.NEW_CATEGORIES).contains("MARKET_FACT");
    }

    @DisplayName("装载全量题库不抛：METRIC_CALC 恰 10 题且全部通过 schema 校验（stub + judge + 容差维面）")
    @Test
    void givenClasspathBank_whenLoaded_thenTenMetricCalcQuestionsPassSchema() {
        List<EvalQuestion> all = QuestionLoader.loadFromClasspath();
        List<EvalQuestion> metricCalc = all.stream()
                .filter(q -> "METRIC_CALC".equals(q.category())).toList();

        assertThat(metricCalc).hasSize(10);
        assertThat(metricCalc).allSatisfy(q -> {
            assertThat(q.mode()).isEqualTo("stub");
            assertThat(q.judge()).isEqualTo("rubric-answer-quality");
            // 计算类核心断言维面：容差锚非空（引擎逐锚核对的前提）
            assertThat(q.expect().declaredDimensions()).contains("dataFidelityTolerance");
            assertThat(q.expect().dataFidelityTolerance().anchorValues()).isNotEmpty();
            // 计算类实体对齐无豁免：必须先对准标的/数据源（标的错则容差锚全盘失义）
            assertThat(q.expect().entityAlignment()).isNotNull();
        });
    }

    @DisplayName("category 词表与 judge 常量逐字对齐：METRIC_CALC 大写同现于题库词表与 NEW_CATEGORIES")
    @Test
    void givenCategoryVocabulary_whenCheckedAgainstJudgeConstants_thenMetricCalcAligned() {
        assertThat(EvalQuestion.CATEGORIES).contains("METRIC_CALC");
        assertThat(EvalRegressionJudge.NEW_CATEGORIES).contains("METRIC_CALC");
    }
}
