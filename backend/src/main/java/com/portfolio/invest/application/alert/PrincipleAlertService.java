package com.portfolio.invest.application.alert;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 投资原则预警巡检（feishu-messaging P1）：交易日日终（18:33，晚于 collector 17:30 巡检、
 * 避开整点半点飞书限流时段）评估 owner 的启用规则 × 持仓当日快照，超限推送飞书卡片。
 * 取数全部走 stock_valuation_daily 收盘口径；最新快照非今日（节假日/采集未就绪）静默跳过——
 * 数据断流告警由 collector freshness patrol（P0 通道）负责，职责分离。
 * 尽力而为：任何异常吞掉记 ERROR，绝不影响调度线程（NFR-2）。
 */
@Service
public class PrincipleAlertService {

    private static final Logger log = LoggerFactory.getLogger(PrincipleAlertService.class);

    private final PrincipleRuleRepository ruleRepo;
    private final PortfolioRepository portfolioRepo;
    private final ValuationRepository valuationRepo;
    private final ValuationDailyPort valDaily;
    private final AlertNotifier notifier;
    private final UserRepository userRepo;
    private final PrincipleAlertEvaluator evaluator;
    private final InvestProperties props;
    private final Clock clock;

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口）。
     * 评估器为无状态纯函数、非 Spring bean（同 CsvImportParser 惯例），此处直接实例化。 */
    @Autowired
    public PrincipleAlertService(PrincipleRuleRepository ruleRepo, PortfolioRepository portfolioRepo,
                                 ValuationRepository valuationRepo, ValuationDailyPort valDaily,
                                 AlertNotifier notifier, UserRepository userRepo, InvestProperties props) {
        this(ruleRepo, portfolioRepo, valuationRepo, valDaily, notifier, userRepo,
                new PrincipleAlertEvaluator(), props, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /** 测试构造器：注入时钟。 */
    PrincipleAlertService(PrincipleRuleRepository ruleRepo, PortfolioRepository portfolioRepo,
                          ValuationRepository valuationRepo, ValuationDailyPort valDaily,
                          AlertNotifier notifier, UserRepository userRepo,
                          PrincipleAlertEvaluator evaluator, InvestProperties props, Clock clock) {
        this.ruleRepo = ruleRepo;
        this.portfolioRepo = portfolioRepo;
        this.valuationRepo = valuationRepo;
        this.valDaily = valDaily;
        this.notifier = notifier;
        this.userRepo = userRepo;
        this.evaluator = evaluator;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 33 18 * * MON-FRI", zone = "Asia/Shanghai")
    public void patrol() {
        try {
            doPatrol();
        } catch (Exception e) { // 调度保护：巡检绝不能炸调度线程
            log.error("原则预警巡检异常", e);
        }
    }

    void doPatrol() {
        InvestProperties.Im im = props.getIm();
        if (im.getAppId() == null || im.getAppId().isBlank()
                || im.getAppSecret() == null || im.getAppSecret().isBlank()
                || im.getChatId() == null || im.getChatId().isBlank()) {
            log.info("原则预警未启用（飞书 im 配置缺失），跳过巡检");
            return;
        }
        var owner = userRepo.findByUsername(im.getOwnerUsername());
        if (owner.isEmpty()) {
            log.warn("原则预警 owner 不存在（username={}），跳过巡检", im.getOwnerUsername());
            return;
        }
        Long userId = owner.get().id();
        var portfolio = portfolioRepo.findPortfolioByUserId(userId);
        if (portfolio.isEmpty()) {
            log.info("用户 {} 无组合，跳过原则预警", userId);
            return;
        }
        var positions = portfolioRepo.findPositionsByPortfolioId(portfolio.get().id()).stream()
                .filter(p -> p.quantity().signum() > 0)
                .toList();
        if (positions.isEmpty()) {
            log.info("用户 {} 无有效持仓，跳过原则预警", userId);
            return;
        }
        LocalDate today = LocalDate.now(clock);
        var latest = valDaily.latestTradingDay();
        if (latest.isEmpty() || !latest.get().equals(today)) {
            log.info("最新快照 {} 非今日 {}（节假日/采集未就绪），跳过原则预警", latest, today);
            return;
        }
        List<String> codes = positions.stream().map(p -> p.stockCode()).toList();
        Map<String, StockMetric> metrics = valDaily.snapshots(today, codes);
        Map<String, String> industries = valuationRepo.findAllIndustryMappings().stream()
                .collect(Collectors.toMap(m -> m.stockCode(), m -> m.industryName(), (a, b) -> a));
        List<PrincipleAlertEvaluator.Holding> holdings = positions.stream()
                .map(p -> new PrincipleAlertEvaluator.Holding(p.stockCode(), p.stockName(), p.quantity()))
                .toList();
        var violations = evaluator.evaluate(
                ruleRepo.findByUserId(userId).stream().filter(r -> r.enabled()).toList(),
                holdings, metrics, industries);
        if (violations.isEmpty()) {
            log.info("原则预警巡检通过：{} 条规则全部健康", ruleRepo.findByUserId(userId).size());
            return;
        }
        List<String> lines = new java.util.ArrayList<>();
        lines.add("**交易日**：" + today);
        lines.add("");
        for (AlertViolation v : violations) {
            String desc = v.description() == null || v.description().isBlank() ? "" : "（%s）".formatted(v.description());
            lines.add("- **%s** %s：当前 %s > 阈值 %s%s".formatted(v.metricLabel(), v.subject(),
                    v.current().setScale(4, RoundingMode.HALF_UP), v.threshold(), desc));
        }
        boolean ok = notifier.send("⚠️ 投资原则预警", "red", lines);
        if (!ok) {
            log.warn("原则预警推送失败（{} 条违规）", violations.size());
        }
    }
}
