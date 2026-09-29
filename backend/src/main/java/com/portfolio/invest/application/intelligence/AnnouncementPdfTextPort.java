package com.portfolio.invest.application.intelligence;

/**
 * 公告 PDF→文本端口（MS-21 D18，infrastructure/intelligence 以 pdfbox 3.x 纯内存实现，零落盘）。
 *
 * <p>语义约定：
 * <ul>
 *   <li>空文本层（扫描件/图片型 PDF）→ 返回空串 {@code ""}，调用方据此判 FAILED reason=NO_TEXT_LAYER</li>
 *   <li>加密（需密码）/损坏/超限 → 抛 {@link AnnouncementPdfTextPortException}，消息含原因</li>
 * </ul>
 *
 * <p>实现须自带防御：入口字节上限 20MB（超限直接拒绝），页数上限 300（超限截断前 300 页）。
 */
public interface AnnouncementPdfTextPort {

    /**
     * 纯内存抽取 PDF 文本。
     *
     * @param pdf 公告 PDF 原始字节
     * @return 全文文本；无文本层返回空串
     * @throws AnnouncementPdfTextPortException 加密（需密码）/损坏/超过字节或页数上限
     */
    String extract(byte[] pdf);
}
