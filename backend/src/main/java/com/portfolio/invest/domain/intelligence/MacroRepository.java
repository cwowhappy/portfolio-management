package com.portfolio.invest.domain.intelligence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 宏观域仓库端口（intelligence_macro_series / intelligence_macro_calendar /
 * intelligence_source_switch 三表 + treasury_yield_curve 跨表只读）。
 *
 * <p>macro_series 与 treasury_yield_curve 均为 collector 写入的跨服务契约表
 * （V1/V3 注释口径），本仓库只读；macro_calendar 为迁移 seed + SQL 人工维护，
 * 亦只读；source_switch 由本仓库独占写入（D19 社融降级留痕）。事务边界在
 * application 层（照既有仓库先例，单语句写无需显式事务）。
 */
public interface MacroRepository {

    /**
     * 每指标最新一期（{@code DISTINCT ON (indicator) … ORDER BY indicator, period DESC}，
     * 走 idx_intelligence_macro_series_indicator）+ **国债收益率跨表只读合成**：
     * 从 treasury_yield_curve 各取 term='1Y'/'10Y' 各自最新交易日的行，合成
     * {@code MacroPoint(indicator=TY1Y/TY10Y, period=最新交易日, periodType=DAY,
     * value=对应 yield)} 两点（复用既有采集任务不另采；两期限各自取最新——采集
     * 延迟日单期限缺行时另一期限仍给出最新点，其余字段缺席为 null 不编造）。
     * macro 指标按 indicator 升序，TY 两点追加在尾（固定 TY1Y 在 TY10Y 前——指标码
     * 字典序 TY10Y &lt; TY1Y，展示口径以 1Y→10Y 为自然序）。
     */
    List<MacroPoint> findLatestPerIndicator();

    /**
     * 单指标历史序列：period 倒序（最新在前）取前 limit 期。仅查 macro_series 表
     * （国债收益率只合成最新点，不提供历史序列——T6 工具口径）。
     */
    List<MacroPoint> findSeries(String indicator, int limit);

    /**
     * 日历区间查询：expected_date 闭区间 [from, to]，按 expected_date、indicator
     * 升序（同日多指标按指标码稳定排序）。
     */
    List<MacroCalendarEntry> findCalendarBetween(LocalDate from, LocalDate to);

    /**
     * 插入一条源切换留痕（switched_at 落库默认 now()）。**幂等由调用方「状态变化
     * 才插」保证**（MacroHealthService 巡检判定源与目标源不同才调本方法），本方法
     * 不做去重——同一切换重复调用即重复留痕。
     */
    void insertSourceSwitch(String indicator, String fromSource, String toSource, String reason);

    /**
     * 该指标最近一次源切换留痕（{@code switched_at} 最新一行，含 from/to 方向）；
     * 无留痕为 empty——降级巡检以最新记录方向为「当前态」（to=m2 即降级态），
     * 支撑「状态翻转才告警一次」的幂等判定（D19）。
     */
    Optional<SourceSwitch> findLatestSwitch(String indicator);
}
