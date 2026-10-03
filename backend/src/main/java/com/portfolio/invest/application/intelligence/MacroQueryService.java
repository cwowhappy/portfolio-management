package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.domain.intelligence.MacroCalendarEntry;
import com.portfolio.invest.domain.intelligence.MacroPoint;
import com.portfolio.invest.domain.intelligence.MacroRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 宏观查询用例（MS-22 F13/F14）：T6 宏观工具与 P4 宏观页/日历卡共用的查询口径。
 *
 * <p>夹紧单点收口在本服务（照 {@link IntelligenceQueryService} 先例）——series limit
 * 夹紧 1..60、calendarUpcoming days 夹紧 1..30，工具层与 web 层不各自防御；
 * 日历窗口的「今日」按 Asia/Shanghai 市场时区（与调度任务 zone 一致）。
 */
@Service
public class MacroQueryService {

    /** 序列条数上限（60 期：月度指标五年 / 日度指标一个季度，LLM 上下文友好）。 */
    static final int MAX_SERIES_LIMIT = 60;

    /** upcoming 日历天数上限（一个月余的发布窗口足够日历卡展示）。 */
    static final int MAX_UPCOMING_DAYS = 30;

    /** 市场时区（「今日」口径，与宏观采集/简报调度 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MacroRepository macroRepository;
    private final Clock clock;

    @Autowired
    public MacroQueryService(MacroRepository macroRepository) {
        this(macroRepository, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟（calendarUpcoming 的「今日」可确定化）。 */
    MacroQueryService(MacroRepository macroRepository, Clock clock) {
        this.macroRepository = macroRepository;
        this.clock = clock;
    }

    /**
     * 全指标最新观测：五先行指标各最新一期 + 国债收益率 TY1Y/TY10Y 合成
     * （跨表只读，见 {@link MacroRepository#findLatestPerIndicator}）。
     */
    public List<MacroPoint> latest() {
        return macroRepository.findLatestPerIndicator();
    }

    /** 单指标历史序列（period 倒序），limit 夹紧 1..60。 */
    public List<MacroPoint> series(String indicator, int limit) {
        return macroRepository.findSeries(indicator, clamp(limit, MAX_SERIES_LIMIT));
    }

    /**
     * 即将发布的日历窗口：今日（Asia/Shanghai）起 days 天（今日含——发布日当天的
     * 指标也在「即将」之列），两端闭区间；days 夹紧 1..30。
     */
    public List<MacroCalendarEntry> calendarUpcoming(int days) {
        LocalDate today = LocalDate.now(clock);
        return macroRepository.findCalendarBetween(today, today.plusDays(clamp(days, MAX_UPCOMING_DAYS)));
    }

    /** 指定日期的日历（单日闭区间退化）。 */
    public List<MacroCalendarEntry> calendarOn(LocalDate date) {
        return macroRepository.findCalendarBetween(date, date);
    }

    /**
     * 源切换留痕透传。幂等由调用方「状态变化才插」保证（巡检判定当前源与目标源
     * 不同才调本方法）——本服务不做去重，重复调用即重复留痕。
     */
    public void recordSourceSwitch(String indicator, String fromSource, String toSource, String reason) {
        macroRepository.insertSourceSwitch(indicator, fromSource, toSource, reason);
    }

    /** 该指标最近一次源切换时间（告警去重消费），无留痕为 empty。 */
    public Optional<Instant> lastSourceSwitchAt(String indicator) {
        return macroRepository.lastSourceSwitchAt(indicator);
    }

    /** 夹紧 1..upper（下界 1：非正数视为「至少取一期/一天」）。 */
    private static int clamp(int value, int upper) {
        return Math.min(Math.max(value, 1), upper);
    }
}
