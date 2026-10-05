package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.config.InvestProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AdviceDetector 单元测试（MS-29 B6，设计规格 §4.2 步骤 7）：自声明标记检测/剥离（决策 #5）、
 * 词表兜底命中（决策 #4 参数组④）、否定语境豁免（词前 8 字符窗口，保守清单）、
 * 井集 by 三态（self|lexicon|both）、空文本/无标记直通。
 */
class AdviceDetectorTest {

    private final AdviceDetector detector =
            new AdviceDetector(new InvestProperties().getTrust().getAdviceLexicon());

    // ———— 自声明标记（决策 #5）：检测 + 剥离 ————

    @DisplayName("标记行检测：文末单独一行 <!--advice--> → by=self 且该行剥离")
    @Test
    void givenMarkerAtLastLine_whenDetect_thenSelfAndStripped() {
        var d = detector.detect("今日大盘上涨。\n<!--advice-->");

        assertThat(d.flag()).isTrue();
        assertThat(d.by()).isEqualTo("self");
        assertThat(d.cleanText()).isEqualTo("今日大盘上涨。");
    }

    @DisplayName("标记行容错：缩进/前后空行/尾随空白 → 仍检测并剥离")
    @Test
    void givenMarkerWithSurroundingWhitespace_whenDetect_thenStillStripped() {
        var d = detector.detect("结论先行。\n\n  <!--advice-->  \n");

        assertThat(d.flag()).isTrue();
        assertThat(d.by()).isEqualTo("self");
        assertThat(d.cleanText()).isEqualTo("结论先行。");
    }

    @DisplayName("整条消息只有标记行 → 剥离为空文本仍置 self")
    @Test
    void givenMarkerOnlyText_whenDetect_thenSelfAndEmptyCleanText() {
        var d = detector.detect("<!--advice-->");

        assertThat(d.flag()).isTrue();
        assertThat(d.by()).isEqualTo("self");
        assertThat(d.cleanText()).isEmpty();
    }

    @DisplayName("无标记：文本原样直通（self 层不命中）")
    @Test
    void givenNoMarker_whenDetect_thenTextUntouched() {
        var d = detector.detect("今日大盘上涨。");

        assertThat(d.flag()).isFalse();
        assertThat(d.by()).isNull();
        assertThat(d.cleanText()).isEqualTo("今日大盘上涨。");
    }

    // ———— 词表兜底（决策 #4：参数组④） ————

    @DisplayName("词表命中：消息含词表词 → by=lexicon，文本不变")
    @Test
    void givenLexiconWord_whenDetect_thenLexicon() {
        var d = detector.detect("当前估值偏低，可以考虑分批建仓。");

        assertThat(d.flag()).isTrue();
        assertThat(d.by()).isEqualTo("lexicon");
        assertThat(d.cleanText()).isEqualTo("当前估值偏低，可以考虑分批建仓。");
    }

    @DisplayName("否定语境豁免：「这不构成买入建议」不触发（词前窗口含否定词）")
    @Test
    void givenNegatedLexiconWord_whenDetect_thenExempted() {
        var d = detector.detect("以上内容不构成买入建议。");

        assertThat(d.flag()).isFalse();
        assertThat(d.by()).isNull();
    }

    @DisplayName("否定窗口边界：否定词距命中词超过 8 字符（窗口外）→ 正常命中")
    @Test
    void givenNegationBeyondWindow_whenDetect_thenHit() {
        var d = detector.detect("无论大盘怎么走，个股分化明显，可择机加仓。");

        assertThat(d.flag()).isTrue();
        assertThat(d.by()).isEqualTo("lexicon");
    }

    @DisplayName("逐出现豁免：同词首次出现被否定、后次干净 → 仍命中")
    @Test
    void givenFirstOccurrenceNegatedSecondClean_whenDetect_thenHit() {
        var d = detector.detect("现在不建议买入；若回踩企稳放量，再考虑买入。");

        assertThat(d.flag()).isTrue();
        assertThat(d.by()).isEqualTo("lexicon");
    }

    // ———— 井集 by 三态 ————

    @DisplayName("两层都命中 → by=both（标记 + 词表词）")
    @Test
    void givenMarkerAndLexiconWord_whenDetect_thenBoth() {
        var d = detector.detect("建议逢低买入。\n<!--advice-->");

        assertThat(d.flag()).isTrue();
        assertThat(d.by()).isEqualTo("both");
        assertThat(d.cleanText()).isEqualTo("建议逢低买入。");
    }

    // ———— 直通护栏 ————

    @DisplayName("空/null 文本直通：不置 flag")
    @Test
    void givenNullOrEmptyText_whenDetect_thenPassthrough() {
        assertThat(detector.detect(null).flag()).isFalse();
        assertThat(detector.detect(null).by()).isNull();
        assertThat(detector.detect(null).cleanText()).isNull();
        assertThat(detector.detect("").flag()).isFalse();
    }

    @DisplayName("空/null 词表：词表层恒不命中，仅自声明标记生效")
    @Test
    void givenEmptyOrNullLexicon_whenDetect_thenOnlySelfLayerWorks() {
        AdviceDetector empty = new AdviceDetector(List.of());

        assertThat(empty.detect("可以考虑买入。").flag()).isFalse();
        assertThat(empty.detect("可以考虑买入。\n<!--advice-->").by()).isEqualTo("self");
        assertThat(new AdviceDetector(null).detect("可以考虑买入。").flag()).isFalse();
    }
}
