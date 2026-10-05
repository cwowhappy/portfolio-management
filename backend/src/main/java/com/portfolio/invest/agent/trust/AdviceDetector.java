package com.portfolio.invest.agent.trust;

import java.util.ArrayList;
import java.util.List;

/**
 * 投资建议检测器（MS-29 B6，设计规格 §4.2 步骤 7）：自声明标记 + 词表兜底双层井集（决策 #4/#5）。
 *
 * <p>① <strong>自声明</strong>：提示词约束模型含投资建议时在文末输出标记行
 * {@code <!--advice-->}（HTML 注释 markdown 不渲染，天然隐蔽）——检测到即<strong>剥离</strong>
 * 该行（剥离后的文本才是最终 Msg/payload 文本；剥离由 {@link TrustTurnProcessor} 安排在
 * 校验/改写之前，使注记行附加在干净文本上），置 by=self；② <strong>词表兜底</strong>
 * （参数组④，{@code invest.trust.advice-lexicon} 可配）：消息文本命中任一词 → by=lexicon；
 * 两层都命中 by=both，flag=任一命中。
 *
 * <p><strong>否定语境豁免</strong>：命中词前 {@value #NEGATION_WINDOW} 字符窗口内含否定词
 * → 该次命中不计（「这不构成买入建议」不触发）；豁免按<strong>出现次数</strong>判——同词
 * 前一次出现被否定、后一次干净仍命中。保守清单宁可少标不可误报。
 */
public final class AdviceDetector {

    /** 自声明标记行（提示词约束模型在文末单独一行输出；HTML 注释 markdown 不渲染）。 */
    public static final String MARKER = "<!--advice-->";

    /** 否定语境窗口：命中词前该字符数内的否定词使该次命中豁免。 */
    static final int NEGATION_WINDOW = 8;

    /** 保守否定词清单（窗口内出现任一即豁免该次命中；单字「不/非/无」已覆盖多数复合否定）。 */
    static final List<String> NEGATION_TERMS =
            List.of("不", "非", "无", "并非", "不构成", "不代表", "不作为", "排除", "避免", "免责");

    private final List<String> lexicon;

    public AdviceDetector(List<String> lexicon) {
        List<String> words = new ArrayList<>();
        if (lexicon != null) {
            for (String word : lexicon) {
                if (word != null && !word.isBlank()) {
                    words.add(word);
                }
            }
        }
        this.lexicon = List.copyOf(words);
    }

    /** 一次检测的产物：flag=任一层命中；by=self|lexicon|both（未命中为 null）；cleanText=剥离标记行后的文本。 */
    public record Detection(boolean flag, String by, String cleanText) {}

    /**
     * 检测：先剥离自声明标记行（cleanText 才是进入校验/改写与最终 Msg 的文本），
     * 再对 cleanText 做词表兜底扫描。null/空文本直通。
     */
    public Detection detect(String text) {
        if (text == null || text.isEmpty()) {
            return new Detection(false, null, text);
        }
        boolean self = false;
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (MARKER.equals(line.strip())) {
                self = true;
                continue;
            }
            kept.add(line);
        }
        String cleanText = self ? String.join("\n", kept).stripTrailing() : text;
        boolean lexiconHit = lexiconHit(cleanText);
        if (self && lexiconHit) {
            return new Detection(true, "both", cleanText);
        }
        if (self) {
            return new Detection(true, "self", cleanText);
        }
        if (lexiconHit) {
            return new Detection(true, "lexicon", cleanText);
        }
        return new Detection(false, null, cleanText);
    }

    /** 词表扫描：任一词存在一次非否定命中的出现即命中（逐出现判否定窗口，豁免个别出现）。 */
    private boolean lexiconHit(String text) {
        for (String word : lexicon) {
            int idx = text.indexOf(word);
            while (idx >= 0) {
                if (!negatedBefore(text, idx)) {
                    return true;
                }
                idx = text.indexOf(word, idx + word.length());
            }
        }
        return false;
    }

    /** 命中词前 {@link #NEGATION_WINDOW} 字符窗口内是否含否定词（该次命中豁免）。 */
    private static boolean negatedBefore(String text, int idx) {
        String window = text.substring(Math.max(0, idx - NEGATION_WINDOW), idx);
        for (String term : NEGATION_TERMS) {
            if (window.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
