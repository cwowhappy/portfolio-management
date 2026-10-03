package com.portfolio.invest.application.intelligence;

/**
 * 公告 PDF 解析失败（加密需密码/损坏/超限）。
 *
 * <p>随端口置于 application/intelligence（裁定 2026-09-29）：端口消费方在 application，
 * domain 不需要 PDF 语义。
 */
public class AnnouncementPdfTextPortException extends RuntimeException {

    public AnnouncementPdfTextPortException(String message) {
        super(message);
    }

    public AnnouncementPdfTextPortException(String message, Throwable cause) {
        super(message, cause);
    }
}
