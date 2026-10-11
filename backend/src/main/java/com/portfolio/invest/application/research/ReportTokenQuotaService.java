package com.portfolio.invest.application.research;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.research.ReportTokenQuota;
import com.portfolio.invest.domain.research.ReportTokenQuotaRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 研报 token 日配额服务（MS-32 B10，设计规格 §3.9）：按用户按 Asia/Shanghai 自然日
 * （quota_date = {@code LocalDate.now(clock)}，沿 A17 IntelligenceTokenBudget 时区先例）
 * 读写 report_token_quota 行——发起前软检查（{@link #assertCanStart}）、任务终态结算
 * （{@link #settle} 走 B3 单语句 upsert 累加，持久化跨重启不丢）、当日余量查询
 * （{@link #remainingToday}，发起响应/前端提示条）。
 *
 * <p>事务：本服务不挂方法级 @Transactional（沿仓内 application 层惯例）——settle 为单语句
 * 原生 upsert，自身原子；其 {@code @Modifying} 所需的活动事务由调用方（B11 编排器终态结算）提供。
 */
@Service
public class ReportTokenQuotaService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReportTokenQuotaRepository repository;
    private final long dailyTokenBudget;
    private final Clock clock;

    /** 主构造器（@Autowired：存在包私有测试构造器时需显式指定注入入口）。 */
    @Autowired
    public ReportTokenQuotaService(ReportTokenQuotaRepository repository, InvestProperties props) {
        this(repository, props, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟（quota_date 跨日路由可测，沿 B4 双构造器先例）。 */
    ReportTokenQuotaService(ReportTokenQuotaRepository repository, InvestProperties props, Clock clock) {
        this.repository = repository;
        this.dailyTokenBudget = props.getReport().getQuota().dailyTokenBudget();
        this.clock = clock;
    }

    /**
     * 发起检查（软语义，D7）：当日累计 used &gt;= daily-budget 即抛 {@link ReportQuotaExceededException}
     * （remaining=0）——恰等也拒：剩余为 0 时新任务必然超支（与 A17 的事后「超 = 严格大于」判定不同，
     * 本处是事前闸门，边界前移一格）。软检查不做原子预留：并发双任务检查时可同时通过
     * （各自读到的 used 均未达线），超额风险由 per-task-token-cap 单任务兜底、
     * settle 事后累计使后续发起暴露。
     */
    public void assertCanStart(Long userId) {
        long used = usedToday(userId);
        if (used >= dailyTokenBudget) {
            throw new ReportQuotaExceededException(
                    "当日研报 token 配额已用尽（used=%d，budget=%d）".formatted(used, dailyTokenBudget),
                    Math.max(0, dailyTokenBudget - used));
        }
    }

    /**
     * 结算：任务终态（成功/失败/取消）调；委托 B3 settle 单语句原生 upsert 累加（同用户同日
     * 并发结算不丢增量），持久化跨重启。tokens 为负抛 IllegalArgumentException（防御：结算口径
     * 只增不减）。不挂服务级事务（沿仓内惯例）：upsert 自原子，活动事务由调用方提供。
     */
    public void settle(Long userId, long tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("settle tokens 不能为负：" + tokens);
        }
        repository.settle(userId, LocalDate.now(clock), tokens);
    }

    /** 当日剩余（发起响应/前端提示条）：budget − used；无行 = budget 全额；超支钳 0（不为负）。 */
    public long remainingToday(Long userId) {
        return Math.max(0, dailyTokenBudget - usedToday(userId));
    }

    private long usedToday(Long userId) {
        return repository.findByUserIdAndDate(userId, LocalDate.now(clock))
                .map(ReportTokenQuota::tokensUsed)
                .orElse(0L);
    }
}
