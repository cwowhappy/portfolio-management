package com.portfolio.invest.domain.intelligence;

/** 情报重要度分档：重要 / 关注 / 忽略。 */
public enum ImportanceGrade {
    MAJOR, WATCH, IGNORE;

    /**
     * 按阈值分档的纯函数：score ≥ majorAt 为 MAJOR，score ≥ watchAt 为 WATCH，否则 IGNORE。
     */
    public static ImportanceGrade grade(int score, int majorAt, int watchAt) {
        if (score >= majorAt) return MAJOR;
        if (score >= watchAt) return WATCH;
        return IGNORE;
    }
}
