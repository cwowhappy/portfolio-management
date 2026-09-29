package com.portfolio.invest.infrastructure.intelligence;

import com.portfolio.invest.application.intelligence.AnnouncementPdfTextPort;
import com.portfolio.invest.application.intelligence.AnnouncementPdfTextPortException;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AnnouncementPdfTextPort 的 pdfbox 3.x 实现：{@code Loader.loadPDF(byte[])} 纯内存加载
 * （默认 RandomAccessStreamCache 内存缓存，零落盘）+ {@code PDFTextStripper} 抽取。
 *
 * <p>防御（D18）：入口超过 20MB 直接拒绝；超过 300 页只抽前 300 页（WARN）。加密
 * （InvalidPasswordException，user 密码缺失/错误）与损坏（IOException）统一翻译为端口异常、
 * 消息含原因；仅 owner 密码（空 user 密码）的文档 pdfbox 可直读，不视为失败。
 */
@Component
public class PdfboxAnnouncementPdfTextPort implements AnnouncementPdfTextPort {

    private static final Logger log = LoggerFactory.getLogger(PdfboxAnnouncementPdfTextPort.class);

    /** 入口字节上限（20MB）：公告 PDF 远小于此，超限直接拒绝，防超大文件 OOM。包内可见供测试引用。 */
    static final int MAX_PDF_BYTES = 20 * 1024 * 1024;

    /** 页数上限：年报等超长文档只抽前 300 页，防抽取阶段 OOM。包内可见供测试引用。 */
    static final int MAX_PAGES = 300;

    @Override
    public String extract(byte[] pdf) {
        if (pdf == null) {
            throw new AnnouncementPdfTextPortException("公告 PDF 字节为 null，无法解析");
        }
        if (pdf.length > MAX_PDF_BYTES) {
            throw new AnnouncementPdfTextPortException(
                    "公告 PDF 超过字节上限 " + MAX_PDF_BYTES + "（实际 " + pdf.length + "），拒绝解析");
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = document.getNumberOfPages();
            if (pages > MAX_PAGES) {
                log.warn("公告 PDF 页数 {} 超上限 {}，仅抽取前 {} 页", pages, MAX_PAGES, MAX_PAGES);
                stripper.setStartPage(1);
                stripper.setEndPage(MAX_PAGES);
            }
            // 空文本层（扫描件）getText 返回空串；strip() 去掉页尾换行等空白
            return stripper.getText(document).strip();
        } catch (IOException | RuntimeException e) {
            // InvalidPasswordException（加密）与损坏 IO 异常统一翻译；getText 对个别畸形内容流
            // 亦可能抛 RuntimeException，一并包裹，保证调用方只见端口异常
            throw new AnnouncementPdfTextPortException("公告 PDF 解析失败（加密或损坏）: " + e.getMessage(), e);
        }
    }
}
