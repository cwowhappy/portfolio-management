package com.portfolio.invest.domain.eval;

/** eval 域错误码（常量类，照 {@code IntelligenceErrorCode} 先例；MS-30 B5 admin 端点消费）。 */
public final class EvalErrorCode {
    private EvalErrorCode() {}

    /** eval 运行行不存在（baseline 置位目标缺失）——HTTP 映射 404。 */
    public static final String EVAL_RUN_NOT_FOUND = "EVAL_RUN_NOT_FOUND";
    /** 提示词资产版本行不存在（补注目标缺失）——HTTP 映射 404。 */
    public static final String PROMPT_ASSET_NOT_FOUND = "PROMPT_ASSET_NOT_FOUND";
    /** 基准置位不合格：仅 COMPLETED 且 alert_status≠DEGRADED 跑可置 true——HTTP 映射 422。 */
    public static final String ERR_BASELINE_INELIGIBLE = "ERR_BASELINE_INELIGIBLE";
}
