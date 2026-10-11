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
}
