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
    /** MS-10 策展：同行业同名策展企业冲突（UNIQUE 兜底，handler 映 409，照 wiki DUPLICATE_METRIC 先例）。 */
    public static final String UNLISTED_DUPLICATE = "INDUSTRY_UNLISTED_DUPLICATE";
    /** MS-10 策展：CSV 导入文件级错误（空文件/超 1MB），handler 映 400。 */
    public static final String INVALID_CSV = "INDUSTRY_INVALID_CSV";
    /** MS-10 P3 产业链：环节层级非 ChainTier 枚举名（写侧命令校验），handler 映 400。 */
    public static final String INVALID_TIER = "INDUSTRY_INVALID_TIER";
    /** MS-10 P3 产业链：成员类型/引用约束违例（镜像 DB CHECK 的前置校验），handler 映 400。 */
    public static final String INVALID_MEMBER = "INDUSTRY_INVALID_MEMBER";
    /** MS-10 P3 产业链：按 id 更新目标链不存在，handler 映 404。 */
    public static final String CHAIN_NOT_FOUND = "INDUSTRY_CHAIN_NOT_FOUND";
    /** MS-10 P3 产业链：链名 UNIQUE 冲突（兜底转译，照 UNLISTED_DUPLICATE 先例），handler 映 409。 */
    public static final String CHAIN_DUPLICATE = "INDUSTRY_CHAIN_DUPLICATE";
}
