package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 研报 payload 围栏提取六态校验（MS-32 B6，设计规格 §3.4）：Agent 最终消息 → 单个
 * {@code ```research-report} 围栏 JSON → {@link ResearchReportSpec.V1}。失败六态各一锚
 * （FENCE_MISSING / JSON_INVALID / SPEC_VERSION_UNSUPPORTED / TITLE_BLANK / CONTENT_BLANK /
 * CONTENT_OVERSIZE）+ 正常 round-trip（围栏前后带正文噪音，验惰性取首围栏）+
 * draftMaxBytes 字节边界「恰超」（UTF-8 字节口径：等于上限过、恰超一字节拦）。
 */
class ReportPayloadParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 真实形态：围栏前后有正文噪音（收集/分析小结），解析须只取围栏内 JSON。 */
    private String fenced(String json) {
        return "本轮研报已完成，收集与分析结论如前所述，结构化产物如下。\n"
                + "```research-report\n" + json + "\n```\n（以上为最终交付物，围栏外文字不作数）";
    }

    @DisplayName("正常载荷：围栏前后带正文噪音，提取首个围栏 round-trip 四字段完整")
    @Test
    void givenFencedValidJson_whenParse_thenRoundTripAllFields() throws Exception {
        ResearchReportSpec.V1 spec = ResearchReportSpec.v1("贵州茅台深度研究",
                "# 贵州茅台深度研究\n## 关键假设\n1. 假设批价企稳——来源 search_news（条目提及批价环比持平）",
                "行情数据存在约 15 分钟延迟");
        String text = fenced(MAPPER.writeValueAsString(spec));

        ResearchReportSpec.V1 parsed = ReportPayloadParser.parse(text, 131072);

        assertThat(parsed.specVersion()).isEqualTo(1);
        assertThat(parsed.title()).isEqualTo("贵州茅台深度研究");
        assertThat(parsed.contentMd())
                .startsWith("# 贵州茅台深度研究")
                .contains("## 关键假设")
                .contains("来源 search_news");
        assertThat(parsed.limitations()).isEqualTo("行情数据存在约 15 分钟延迟");
    }

    @DisplayName("围栏缺失：最终消息无 research-report 围栏（含 null 输入）→ FENCE_MISSING")
    @Test
    void givenNoFence_whenParse_thenFenceMissing() {
        assertThatThrownBy(() -> ReportPayloadParser.parse("研报已完成：结论看好，风险可控。", 131072))
                .isInstanceOf(ReportPayloadException.class)
                .extracting("kind")
                .isEqualTo(ReportPayloadException.Kind.FENCE_MISSING);
        assertThatThrownBy(() -> ReportPayloadParser.parse(null, 131072))
                .isInstanceOf(ReportPayloadException.class)
                .extracting("kind")
                .isEqualTo(ReportPayloadException.Kind.FENCE_MISSING);
    }

    @DisplayName("JSON 非法：围栏内非合法 JSON（缺逗号）→ JSON_INVALID")
    @Test
    void givenFencedMalformedJson_whenParse_thenJsonInvalid() {
        String text = fenced("{\"specVersion\":1,\"title\":\"研报\" \"contentMd\":\"正文\",\"limitations\":\"无\"}");

        assertThatThrownBy(() -> ReportPayloadParser.parse(text, 131072))
                .isInstanceOf(ReportPayloadException.class)
                .extracting("kind")
                .isEqualTo(ReportPayloadException.Kind.JSON_INVALID);
    }

    @DisplayName("契约版本不支持：specVersion=2（未来版本）→ SPEC_VERSION_UNSUPPORTED")
    @Test
    void givenFutureSpecVersion_whenParse_thenSpecVersionUnsupported() {
        String text = fenced("{\"specVersion\":2,\"title\":\"研报\",\"contentMd\":\"正文\",\"limitations\":\"无\"}");

        assertThatThrownBy(() -> ReportPayloadParser.parse(text, 131072))
                .isInstanceOf(ReportPayloadException.class)
                .extracting("kind")
                .isEqualTo(ReportPayloadException.Kind.SPEC_VERSION_UNSUPPORTED);
    }

    @DisplayName("标题空白：title 为空白串 → TITLE_BLANK")
    @Test
    void givenBlankTitle_whenParse_thenTitleBlank() {
        String text = fenced("{\"specVersion\":1,\"title\":\"   \",\"contentMd\":\"正文\",\"limitations\":\"无\"}");

        assertThatThrownBy(() -> ReportPayloadParser.parse(text, 131072))
                .isInstanceOf(ReportPayloadException.class)
                .extracting("kind")
                .isEqualTo(ReportPayloadException.Kind.TITLE_BLANK);
    }

    @DisplayName("正文空白：contentMd 为空串 → CONTENT_BLANK")
    @Test
    void givenBlankContent_whenParse_thenContentBlank() {
        String text = fenced("{\"specVersion\":1,\"title\":\"研报\",\"contentMd\":\"\",\"limitations\":\"无\"}");

        assertThatThrownBy(() -> ReportPayloadParser.parse(text, 131072))
                .isInstanceOf(ReportPayloadException.class)
                .extracting("kind")
                .isEqualTo(ReportPayloadException.Kind.CONTENT_BLANK);
    }

    @DisplayName("正文超限：contentMd UTF-8 字节数远超上限 → CONTENT_OVERSIZE 且报文含两侧字节数")
    @Test
    void givenOversizedContent_whenParse_thenContentOversize() {
        String text = fenced("{\"specVersion\":1,\"title\":\"研报\",\"contentMd\":\""
                + "正文".repeat(50) + "\",\"limitations\":\"无\"}");

        assertThatThrownBy(() -> ReportPayloadParser.parse(text, 32))
                .isInstanceOf(ReportPayloadException.class)
                .hasMessageContaining("32")
                .extracting("kind")
                .isEqualTo(ReportPayloadException.Kind.CONTENT_OVERSIZE);
    }

    @DisplayName("字节边界恰超：30 字节正文按上限 30 过、按上限 29 拦（「恰超」= 字节数 > 上限）")
    @Test
    void givenExactByteBoundary_whenParse_thenEqualPassesAndJustOverFails() throws Exception {
        // 「研」UTF-3 字节 × 10 = 恰 30 字节；title/limitations 不计入上限（contentMd 口径）
        String text = fenced(MAPPER.writeValueAsString(ResearchReportSpec.v1(
                "研报", "研".repeat(10), "无")));

        assertThat(ReportPayloadParser.parse(text, 30).contentMd()).isEqualTo("研".repeat(10));
        assertThatThrownBy(() -> ReportPayloadParser.parse(text, 29))
                .isInstanceOf(ReportPayloadException.class)
                .extracting("kind")
                .isEqualTo(ReportPayloadException.Kind.CONTENT_OVERSIZE);
    }

    // ———— Ruling-21 修复②：字符串字面量裸控制字符预清洗（真 LLM 实证形态） ————

    @DisplayName("修复②：contentMd 值内裸换行/裸制表符（真 LLM 实证形态）→ 预清洗后解析成功且换行保真")
    @Test
    void givenRawNewlinesInsideStringValue_whenParse_thenSucceedsWithFaithfulNewlines() {
        // 形态复刻 S1 taskId=13：Jackson 报 Illegal unquoted character ((CTRL-CHAR, code 10))
        String json = "{\"specVersion\":1,\"title\":\"茅台研报\",\"contentMd\":\"# 茅台研报\n"
                + "## 关键假设\n1. 现价1520.33元\t（来源 get_quote）\",\"limitations\":\"无\"}";
        String text = fenced(json);

        ResearchReportSpec.V1 parsed = ReportPayloadParser.parse(text, 131072);

        assertThat(parsed.title()).isEqualTo("茅台研报");
        assertThat(parsed.contentMd())
                .startsWith("# 茅台研报\n")
                .contains("## 关键假设\n1. 现价1520.33元\t（来源 get_quote）");
    }

    @DisplayName("修复②：字符串外结构性裸换行（pretty JSON）本就合法 → 原样保留不扰动")
    @Test
    void givenStructuralNewlinesOutsideStrings_whenParse_thenUntouched() {
        String json = "{\n  \"specVersion\": 1,\n  \"title\": \"研报\",\n  \"contentMd\": \"正文\",\n  \"limitations\": \"无\"\n}";
        String text = fenced(json);

        ResearchReportSpec.V1 parsed = ReportPayloadParser.parse(text, 131072);

        assertThat(parsed.contentMd()).isEqualTo("正文");
    }

    @DisplayName("修复②：已转义序列（字面反斜杠 n）不被二次加工 → 解析后保持字面 \\\\n")
    @Test
    void givenAlreadyEscapedSequence_whenParse_thenNotDoubleEscaped() {
        // Java 源里 \\\\n = JSON 源文本中的 \\n（已转义反斜杠+字母 n）→ 解析后是字面 "\n" 两字符
        String json = "{\"specVersion\":1,\"title\":\"研报\",\"contentMd\":\"路径 C:\\\\n 分区\",\"limitations\":\"无\"}";
        String text = fenced(json);

        ResearchReportSpec.V1 parsed = ReportPayloadParser.parse(text, 131072);

        assertThat(parsed.contentMd()).isEqualTo("路径 C:\\n 分区");
    }

    @DisplayName("修复②：值内其它裸控制字符（\\r 0x0D / 0x00）→ \\u00XX 兜底转义可解析")
    @Test
    void givenExoticRawControlChars_whenParse_thenHexEscaped() {
        String json = "{\"specVersion\":1,\"title\":\"研报\",\"contentMd\":\"a\rb\u0000b\",\"limitations\":\"无\"}";
        String text = fenced(json);

        ResearchReportSpec.V1 parsed = ReportPayloadParser.parse(text, 131072);

        assertThat(parsed.contentMd()).isEqualTo("a\rb\u0000b");
    }
}
