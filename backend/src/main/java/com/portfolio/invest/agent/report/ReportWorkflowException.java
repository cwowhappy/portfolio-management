package com.portfolio.invest.agent.report;

/**
 * 研报工作流失败信号（MS-32 B9，设计规格 §3.2）：编排器以 {@link #errorCode()} 分流任务终态
 * FAILED 的错误码（V8 error_code 五值口径：QUOTA_TASK_CAP/TIMEOUT/VALIDATION_FAILED/
 * PARSE_FAILED/AGENT_ERROR）。非受检：编排器单处捕获收敛 fail-closed，HTTP/工具层不经手。
 */
public final class ReportWorkflowException extends RuntimeException {

    /** 单轮调用/任务总时长超时（runner block 超时或每轮后 elapsed 检查）。 */
    public static final String TIMEOUT = "TIMEOUT";
    /** Agent 执行异常/未预期异常/装配缺失（fail-closed 兜底码）。 */
    public static final String AGENT_ERROR = "AGENT_ERROR";
    /** 单任务 token 上限（invest.report.quota.per-task-token-cap，严格大于）。 */
    public static final String QUOTA_TASK_CAP = "QUOTA_TASK_CAP";
    /** payload 提取/校验失败且重试耗尽（格式反馈重试/正文超限）。 */
    public static final String PARSE_FAILED = "PARSE_FAILED";
    /** 校验门失败且回炉轮耗尽（max-regen-rounds）。 */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    private final String errorCode;

    public ReportWorkflowException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
