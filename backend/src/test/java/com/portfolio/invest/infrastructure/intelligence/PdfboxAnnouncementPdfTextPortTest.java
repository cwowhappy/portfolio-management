package com.portfolio.invest.infrastructure.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.application.intelligence.AnnouncementPdfTextPortException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * fixture 全部测试内现场生成，不提交二进制文件。中文文本页采用「标准14 Helvetica 写 ASCII 代理文本 +
 * 手工挂 ToUnicode CMap」构造，理由（pdfbox 3.0.8 实测探针结论）：
 *
 * <ul>
 *   <li>Helvetica 直写中文抛 IllegalArgumentException（U+8425 无字形）——标准14字体不含 CJK；</li>
 *   <li>PDType0Font.load 嵌入系统字体可写中文，但依赖本机 CJK 字体文件（CI ubuntu-latest 无），
 *       且触发 FileSystemFontProvider 全盘字体扫描；</li>
 *   <li>手工构造预定义 CJK 字典（STSong-Light/UniGB-UCS2-H）+ showText 抛
 *       UnsupportedOperationException——非嵌入字体不可编码；</li>
 *   <li>ToUnicode 法零系统依赖：标准14字体无字体文件、无字体映射器，且 ToUnicode CMap 本就是
 *       中文 PDF 文本抽取的主通道（与真实公告 PDF 的嵌入子集字体抽取路径一致）。</li>
 * </ul>
 */
class PdfboxAnnouncementPdfTextPortTest {

    /** 抽取断言目标：两页各出现一次。 */
    private static final String CHINESE_TEXT = "营业收入 12.34 亿元";

    /** ASCII 代理文本（可打印、WinAnsi 可编码），逐字符经 ToUnicode 映射为 {@link #CHINESE_TEXT}。 */
    private static final String PROXY_TEXT = "0123456789ABC";

    private final PdfboxAnnouncementPdfTextPort port = new PdfboxAnnouncementPdfTextPort();

    @DisplayName("两页中文文本 PDF：抽取文本含「营业收入 12.34 亿元」，且两页各出现一次")
    @Test
    void givenTwoPageChineseTextPdf_whenExtract_thenContainsChineseTextOnBothPages() throws IOException {
        String text = port.extract(twoPageChinesePdf());

        assertThat(text).contains(CHINESE_TEXT);
        assertThat(countOccurrences(text, CHINESE_TEXT)).isEqualTo(2);
    }

    @DisplayName("空文本层（有页无内容，扫描件形态）：返回空串")
    @Test
    void givenPagesWithoutText_whenExtract_thenReturnsEmptyString() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            pdf = save(doc);
        }

        assertThat(port.extract(pdf)).isEmpty();
    }

    @DisplayName("加密文档（user 密码）：抛端口异常，消息含原因，cause 保留")
    @Test
    void givenEncryptedPdf_whenExtract_thenThrowsWithReason() throws IOException {
        assertThatThrownBy(() -> port.extract(encryptedPdf("user-pw")))
                .isInstanceOf(AnnouncementPdfTextPortException.class)
                .hasMessageContaining("Cannot decrypt PDF")
                .cause()
                .isInstanceOf(IOException.class);
    }

    @DisplayName("仅 owner 密码（空 user 密码，复制受限公告常见形态）：可直读，不视为加密失败")
    @Test
    void givenOwnerPasswordOnlyPdf_whenExtract_thenReadsText() throws IOException {
        String text = port.extract(encryptedPdf(""));

        assertThat(text).contains("owner-only");
    }

    @DisplayName("损坏字节（非 PDF 内容）：抛端口异常，消息含原因")
    @Test
    void givenCorruptBytes_whenExtract_thenThrows() {
        byte[] corrupt = "这不是一个PDF文件".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> port.extract(corrupt))
                .isInstanceOf(AnnouncementPdfTextPortException.class)
                .hasMessageContaining("解析失败");
    }

    @DisplayName("null 输入：抛端口异常（明确报错，不让 NPE 泄漏）")
    @Test
    void givenNullBytes_whenExtract_thenThrows() {
        assertThatThrownBy(() -> port.extract(null))
                .isInstanceOf(AnnouncementPdfTextPortException.class)
                .hasMessageContaining("null");
    }

    @DisplayName("超过 20MB 字节上限：入口直接拒绝，不进入解析")
    @Test
    void givenOversizeBytes_whenExtract_thenThrowsWithoutParsing() {
        byte[] oversize = new byte[PdfboxAnnouncementPdfTextPort.MAX_PDF_BYTES + 1];

        assertThatThrownBy(() -> port.extract(oversize))
                .isInstanceOf(AnnouncementPdfTextPortException.class)
                .hasMessageContaining("超过字节上限");
    }

    @DisplayName("超过 300 页：截断只抽前 300 页（首页文本在、第 301 页文本不在）")
    @Test
    void givenOver300PagePdf_whenExtract_thenTruncatesToFirst300Pages() throws IOException {
        byte[] pdf = pagesWithEdgeMarkers(PdfboxAnnouncementPdfTextPort.MAX_PAGES + 1);

        String text = port.extract(pdf);

        assertThat(text).contains("FIRST-PAGE").doesNotContain("LAST-PAGE");
    }

    // ---- fixture 生成（全部内存构造，零落盘） ----

    /** 两页中文文本 PDF：Helvetica 写代理 ASCII，ToUnicode 映射后抽取结果即 {@link #CHINESE_TEXT}。 */
    private static byte[] twoPageChinesePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int p = 0; p < 2; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(PROXY_TEXT);
                    cs.endText();
                }
            }
            attachToUnicode(doc, font);
            return save(doc);
        }
    }

    /** 给字体字典挂 ToUnicode CMap：逐字符把代理 ASCII 码位映射为目标中文字符。 */
    private static void attachToUnicode(PDDocument doc, PDType1Font font) throws IOException {
        if (PROXY_TEXT.length() != CHINESE_TEXT.length()) {
            throw new IllegalStateException("代理文本与目标文本长度不一致: "
                    + PROXY_TEXT.length() + " vs " + CHINESE_TEXT.length());
        }
        StringBuilder cmap = new StringBuilder();
        cmap.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n");
        cmap.append("/CMapName /UtfTest def\n/CMapType 2 def\n");
        cmap.append("1 begincodespacerange\n<00> <ff>\nendcodespacerange\n");
        cmap.append(PROXY_TEXT.length()).append(" beginbfchar\n");
        for (int i = 0; i < PROXY_TEXT.length(); i++) {
            cmap.append(String.format("<%02x> <%04x>%n", (int) PROXY_TEXT.charAt(i), (int) CHINESE_TEXT.charAt(i)));
        }
        cmap.append("endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        PDStream toUnicode = new PDStream(doc);
        try (OutputStream os = toUnicode.createOutputStream(COSName.FLATE_DECODE)) {
            os.write(cmap.toString().getBytes(StandardCharsets.US_ASCII));
        }
        font.getCOSObject().setItem(COSName.TO_UNICODE, toUnicode);
    }

    /** 加密 PDF：owner 密码固定，userPassword 传空串表示仅 owner 限制（可直读形态）。 */
    private static byte[] encryptedPdf(String userPassword) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("owner-only");
                cs.endText();
            }
            doc.protect(new StandardProtectionPolicy("owner-pw", userPassword, new AccessPermission()));
            return save(doc);
        }
    }

    /** 首末页带 ASCII 标记、其余空页的多页 PDF。 */
    private static byte[] pagesWithEdgeMarkers(int totalPages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= totalPages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (i == 1 || i == totalPages) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(i == 1 ? "FIRST-PAGE" : "LAST-PAGE");
                        cs.endText();
                    }
                }
            }
            return save(doc);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
