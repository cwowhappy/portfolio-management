package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.intelligence.CollectorRunInspectPort.TaskRunSummary;
import com.portfolio.invest.domain.intelligence.MacroRepository;
import com.portfolio.invest.domain.intelligence.SourceSwitch;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 社融源健康巡检（D19 决策 #25）：每日 08:05（早于 macro_afmi 任务 11:00 cron 窗口，
 * 无并发风险）判定主源 socfin 是否「连续 2 个发布期失败」，是则 source_switch 留痕
 * + 飞书群告警——<b>仅状态翻转时告警一次</b>；恢复（新期月成功）插回切留痕不告警。
 *
 * <p><b>月度聚组口径（MS-22 探测报告 §4.2，写死如下）</b>：
 * <ul>
 *   <li>回看窗 {@value #LOOKBACK_DAYS} 天（覆盖 ≥2 个完整发布期），started_at 按
 *       Asia/Shanghai 自然月分组；<b>无 run 的月不参与判定</b>（任务未上线/停用窗口
 *       不触发降级，空月视为异常静默不切换）；</li>
 *   <li>月成功 ⇔ ∃ run（task_code=macro_afmi ∧ source_used='socfin' ∧ status ∈
 *       {success, partial}——executor success/partial 必带 source_used、AllSourcesFailed
 *       终行为 NULL，故「全部落 m2 的月 / 全 failed 的月」均计失败月）；</li>
 *   <li>连续 = <b>日历月相邻</b>：两个失败月之间隔着空月或成功月即打断连续性；</li>
 *   <li>当前态以 {@link MacroRepository#findLatestSwitch} 最新留痕<b>方向</b>为准
 *       （to=m2 即降级态）：降级态下再连续失败不重复留痕不重复告警（幂等），从健康
 *       态（无留痕或最新留痕 to=socfin）翻转到降级态才留痕+告警。</li>
 * </ul>
 *
 * <p>与采集侧职责边界：selector 的自动 failover 是<b>期内的</b>（当日 socfin 失败即
 * 落 m2）；本服务判定的是<b>发布期级的持续劣化</b>（连续 2 期都要靠 m2 兜底 = 主源
 * 结构性失效）——留痕晚于 selector 动作属预期。尽力而为：顶层吞异常护调度线程
 * （照 PrincipleAlertService 先例）；无 now() 消费点（窗口截断在端口 SQL 的 DB now()、
 * 月聚组取 run started_at、幂等取 source_switch 最新行），不注入 Clock。
 */
@Service
public class MacroHealthService {

    private static final Logger log = LoggerFactory.getLogger(MacroHealthService.class);

    /** 市场时区（月聚组与调度 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 社融采集任务码（collector/tasks/macro_afmi.yaml，双源 socfin→m2）。 */
    static final String TASK_CODE = "macro_afmi";

    /** 留痕指标：社融（M2 备源落库 indicator=M2 不冒充 AFMI，见探测报告 §3.2）。 */
    static final String INDICATOR = "AFMI";

    /** 主源（央行调查统计司社融增量 xlsx）与备源（货币供应量 xlsx，indicator=M2）。 */
    static final String PRIMARY_SOURCE = "socfin";
    static final String FALLBACK_SOURCE = "m2";

    /** 回看窗 65 天：月度发布节奏下覆盖 ≥2 个完整发布期（探测报告 §4.2）。 */
    static final int LOOKBACK_DAYS = 65;

    /** 月度成功口径：status ∈ {success, partial} 且 source_used='socfin'（partial 按成功计）。 */
    static final Set<String> SUCCESS_STATUSES = Set.of("success", "partial");

    private final CollectorRunInspectPort runInspect;
    private final MacroRepository macroRepository;
    private final IntelligencePushPort pushPort;

    public MacroHealthService(CollectorRunInspectPort runInspect, MacroRepository macroRepository,
                              IntelligencePushPort pushPort) {
        this.runInspect = runInspect;
        this.macroRepository = macroRepository;
        this.pushPort = pushPort;
    }

    @Scheduled(cron = "0 5 8 * * *", zone = "Asia/Shanghai")
    public void patrol() {
        try {
            check();
        } catch (Exception e) { // 调度保护：巡检绝不能炸调度线程
            log.error("社融源健康巡检异常", e);
        }
    }

    /** 巡检入口（测试/运维可直调）：无 run 不判定 → 最新月成功走恢复 → 否则连续 2 失败月降级。 */
    void check() {
        List<TaskRunSummary> runs = runInspect.runsOf(TASK_CODE, LOOKBACK_DAYS);
        if (runs.isEmpty()) {
            log.warn("社融源巡检：{} 近 {} 天无终态 run（任务未上线/停用？），不判定", TASK_CODE, LOOKBACK_DAYS);
            return;
        }
        TreeMap<YearMonth, Boolean> monthSuccess = groupByMonth(runs);
        Map.Entry<YearMonth, Boolean> latest = monthSuccess.lastEntry();
        boolean degraded = isDegraded(macroRepository.findLatestSwitch(INDICATOR));
        if (latest.getValue()) {
            recoverIfNeeded(latest.getKey(), degraded);
            return;
        }
        // 最新月失败：向前找日历相邻的失败月（空月/成功月即打断）
        YearMonth prev = latest.getKey().minusMonths(1);
        if (!Boolean.FALSE.equals(monthSuccess.get(prev))) {
            log.info("社融源巡检：最近失败月 {} 前一月 {} 非失败（单期/被打断），不触发降级",
                    latest.getKey(), prev);
            return;
        }
        degradeIfHealthy(latest.getKey(), prev, degraded);
    }

    /**
     * 月度聚组：started_at → Asia/Shanghai 自然月；月 verdict = 各 run 的
     * 「socfin 成功」按 OR 合并（∃ 即成功）；TreeMap 保证月升序（lastEntry 即最新月）。
     */
    private static TreeMap<YearMonth, Boolean> groupByMonth(List<TaskRunSummary> runs) {
        TreeMap<YearMonth, Boolean> months = new TreeMap<>();
        for (TaskRunSummary run : runs) {
            months.merge(YearMonth.from(run.startedAt().atZone(ZONE)),
                    isSocfinSuccess(run), Boolean::logicalOr);
        }
        return months;
    }

    private static boolean isSocfinSuccess(TaskRunSummary run) {
        return PRIMARY_SOURCE.equals(run.sourceUsed()) && SUCCESS_STATUSES.contains(run.status());
    }

    /** 当前态 = 最新留痕方向：to=m2 即降级态（无留痕 = 从健康态起步）。 */
    private static boolean isDegraded(Optional<SourceSwitch> latestSwitch) {
        return latestSwitch.isPresent() && FALLBACK_SOURCE.equals(latestSwitch.get().toSource());
    }

    /** 恢复：降级态下最新月 socfin 成功 → 回切留痕（不告警）；健康态无动作。 */
    private void recoverIfNeeded(YearMonth successMonth, boolean degraded) {
        if (!degraded) {
            log.info("社融源巡检：最近月 {} socfin 月度成功（健康态），无动作", successMonth);
            return;
        }
        macroRepository.insertSourceSwitch(INDICATOR, FALLBACK_SOURCE, PRIMARY_SOURCE, "社融源恢复");
        log.info("社融主源恢复（{} 月度成功），已插回切留痕（不告警）", successMonth);
    }

    /** 降级：连续 2 失败月，健康态翻转才留痕+告警一次；降级态幂等跳过。 */
    private void degradeIfHealthy(YearMonth latestMonth, YearMonth prevMonth, boolean degraded) {
        String twoMonths = prevMonth + "、" + latestMonth;
        if (degraded) {
            log.info("社融源已处降级态（{} 连续失败持续中），留痕与告警不重复", twoMonths);
            return;
        }
        macroRepository.insertSourceSwitch(INDICATOR, PRIMARY_SOURCE, FALLBACK_SOURCE,
                "社融主源连续2个发布期失败（" + twoMonths + "）");
        boolean ok = pushPort.sendToGroup("【社融数据源降级】" + twoMonths, "red", List.of(
                "社融数据源连续 2 个发布期失败（" + twoMonths + "），已自动切换 M2 口径采集；社融序列留痕可查。",
                "请人工检查央行社融数据页结构。"));
        if (ok) {
            log.warn("社融源降级：{} 连续 2 发布期失败，已留痕并告警（M2 口径兜底）", twoMonths);
        } else {
            log.warn("社融源降级：{} 已留痕，但告警推送失败（次日巡检不重发，见 source_switch 留痕）", twoMonths);
        }
    }
}
