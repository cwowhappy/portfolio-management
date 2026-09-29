package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.config.InvestProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 情报域当日 input token 护栏（D16）：进程内按 Asia/Shanghai 自然日累计 LLM input tokens，
 * 超 {@code invest.intelligence.daily-token-guardrail}（默认 2,000,000）即当日停批——调用方
 * （新闻/公告/政策抽取）注入同一 bean 共享护栏；跨日自动重置（重启丢计数可接受：偏低不偏高）。
 *
 * <p>语义：超限 = 累计严格大于限额；拒批的一笔仍计入累计（token 已真实消费）；
 * 已超限后继续调用恒拒且不再累计。synchronized 防多抽取批并发窗口错账。
 */
@Component
public class IntelligenceTokenBudget {

    private final long dailyLimit;
    private final Clock clock;

    private LocalDate currentDay;
    private long usedToday;

    @Autowired
    public IntelligenceTokenBudget(InvestProperties props) {
        this(props.getIntelligence().getDailyTokenGuardrail(), Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /** 测试构造器：注入限额与时钟（跨日重置可测）。 */
    IntelligenceTokenBudget(long dailyLimit, Clock clock) {
        this.dailyLimit = dailyLimit;
        this.clock = clock;
        this.currentDay = LocalDate.now(clock);
    }

    /** 当日累计是否已超护栏（超 = 严格大于限额）。 */
    public synchronized boolean exhausted() {
        rollDayIfNeeded();
        return usedToday > dailyLimit;
    }

    /**
     * 累计一笔 input tokens；返回 false 表示累计后已超护栏（调用方当日停批 + 告警一次）。
     * 已超限后调用恒返回 false 且不再累计。
     */
    public synchronized boolean tryAcquire(long tokens) {
        rollDayIfNeeded();
        if (usedToday > dailyLimit) {
            return false;
        }
        usedToday += Math.max(0, tokens);
        return usedToday <= dailyLimit;
    }

    /** 当日已累计 input tokens（观测/测试）。 */
    public synchronized long usedToday() {
        rollDayIfNeeded();
        return usedToday;
    }

    private void rollDayIfNeeded() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(currentDay)) {
            currentDay = today;
            usedToday = 0;
        }
    }
}
