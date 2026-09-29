package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 盘前简报归档（intelligence_daily_brief 一行一交易日，D17）：content_md 为 5+1 节
 * markdown（空简版/失败版同留档——表列 NOT NULL）；top_stocks 为选取条目 stock_codes
 * 频次 top 20 快照（决策 #23 检索维度），JSONB 序列化由仓库实现负责（域不解释存储形态）。
 *
 * <p>三态便捷工厂：{@link #generated}/{@link #emptySimple}/{@link #failed}——fail_reason
 * 仅 FAILED 有意义，其余两态为 null。
 *
 * @param id           主键（落库前为 null）
 * @param tradeDate    交易日（UNIQUE）
 * @param contentMd    5+1 节 markdown（或空简版/失败版文本）
 * @param topStocks    涉及标的频次快照（频次降序，至多 20）
 * @param status       GENERATED / EMPTY_SIMPLE / FAILED
 * @param failReason   失败原因（仅 FAILED）
 * @param model        生成时模型标识
 * @param generatedAt  落档时间
 */
public record DailyBrief(
        Long id,
        LocalDate tradeDate,
        String contentMd,
        List<String> topStocks,
        BriefStatus status,
        String failReason,
        String model,
        Instant generatedAt) {

    public DailyBrief {
        topStocks = topStocks == null ? List.of() : List.copyOf(topStocks);
    }

    /** 正常生成档。 */
    public static DailyBrief generated(LocalDate tradeDate, String contentMd, List<String> topStocks,
                                       String model, Instant generatedAt) {
        return new DailyBrief(null, tradeDate, contentMd, topStocks, BriefStatus.GENERATED,
                null, model, generatedAt);
    }

    /** 空简版档（决策 #22：一句话 + 数据截止期别，不跳过）。 */
    public static DailyBrief emptySimple(LocalDate tradeDate, String contentMd, String model,
                                         Instant generatedAt) {
        return new DailyBrief(null, tradeDate, contentMd, List.of(), BriefStatus.EMPTY_SIMPLE,
                null, model, generatedAt);
    }

    /** 失败留档（content_md NOT NULL：失败版以标题+原因占位，详因见 fail_reason）。 */
    public static DailyBrief failed(LocalDate tradeDate, String failReason, String model,
                                    Instant generatedAt) {
        String contentMd = "# 盘前情报速递（" + tradeDate + "）\n\n生成失败：" + failReason;
        return new DailyBrief(null, tradeDate, contentMd, List.of(), BriefStatus.FAILED,
                failReason, model, generatedAt);
    }
}
