package com.portfolio.invest.domain.industry;

public final class IndustryErrorCode {
    private IndustryErrorCode() {}

    public static final String INDUSTRY_NOT_FOUND = "INDUSTRY_NOT_FOUND";
    public static final String INVALID_SORT = "INDUSTRY_INVALID_SORT";
    public static final String INVALID_LIMIT = "INDUSTRY_INVALID_LIMIT";
    /** MS-10 策展：轮次枚举非法（写侧命令校验）。 */
    public static final String INVALID_ROUND = "INDUSTRY_INVALID_ROUND";
    /** MS-10 策展：按 id 更新/删除目标不存在（CRUD 更新前置校验）。 */
    public static final String UNLISTED_NOT_FOUND = "INDUSTRY_UNLISTED_NOT_FOUND";
    /** MS-10 策展：CSV 导入文件级错误（空文件/超 1MB），handler 映 400。 */
    public static final String INVALID_CSV = "INDUSTRY_INVALID_CSV";
}
