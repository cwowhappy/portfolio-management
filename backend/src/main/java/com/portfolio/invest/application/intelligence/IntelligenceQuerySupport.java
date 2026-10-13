package com.portfolio.invest.application.intelligence;

/** Intelligence 查询族共享的微小归一函数（避免三个查询族各抄一份空白串归一）。 */
final class IntelligenceQuerySupport {

    private IntelligenceQuerySupport() {}

    /** 空白串归一为 null（空白关键词不启用条件）。 */
    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
