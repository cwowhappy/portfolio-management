package com.portfolio.invest.agent.report;

/**
 * 研报 payload 提取/校验失败（六态，MS-32 B6）：编排器按 {@link #kind()} 区分回炉反馈
 * （围栏/JSON/字段问题 → 格式反馈重试）与终态失败口径（超限 → PARSE_FAILED 计费留痕）。
 * 非受检异常：解析点在编排器（B9）单处捕获，工具/HTTP 层不经手。
 */
public final class ReportPayloadException extends RuntimeException {

    /** 失败六态：围栏缺失 / JSON 非法 / 契约版本不支持 / 标题空白 / 正文空白 / 正文超限。 */
    public enum Kind {
        FENCE_MISSING, JSON_INVALID, SPEC_VERSION_UNSUPPORTED,
        TITLE_BLANK, CONTENT_BLANK, CONTENT_OVERSIZE
    }

    private final Kind kind;

    public ReportPayloadException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
