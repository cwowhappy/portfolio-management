package com.portfolio.invest.application.memory;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.memory.MemoryDigestCursorRepository;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F03 摘要调度器（MS-31 B6，设计规格 §4.1；三段式模板逐项对齐 {@code EvalScheduler:154-177}）：
 * 每晚 21:07（日终收盘/晚间复盘后、早晚批情报任务之外错峰）读全局游标为窗口起，委托
 * {@link MemoryDigestService#digestAll}。
 *
 * <p>三段快退（顺序固定）：①{@code invest.profile.digest-enabled=false} 跳过；②
 * {@code DEEPSEEK_API_KEY} 缺席静默跳过留痕——必须在批前整批快退：端口无 key 时全程
 * 返回 empty，若照跑会按「批完成」推进游标，窗口事件被静默跳过造成<b>永久漏摘要</b>；
 * ③游标单行缺失（V7 seed 不变量破坏）WARN 跳过。顶层 try/catch 护调度线程（沿十任务
 * 先例）。cron 字面量不入配置（InvestProperties cron 声明位先例）；类级
 * {@code @ConditionalOnExpression} 沿 EvalScheduler/SchedulingConfig 先例——eval 子进程
 * （--Eval_MODE=true）不注册本类。父环境经 {@code Supplier} 注入（生产装配
 * infrastructure 的 SystemParentEnvironment，A3/E4 禁 application 直调 System.getenv）。
 */
@Component
@ConditionalOnExpression("!'true'.equals('${Eval_MODE:}')")
public class MemoryDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryDigestScheduler.class);

    private final MemoryDigestService digestService;
    private final MemoryDigestCursorRepository cursorRepository;
    private final InvestProperties props;
    private final Supplier<Map<String, String>> parentEnv;

    public MemoryDigestScheduler(MemoryDigestService digestService,
                                 MemoryDigestCursorRepository cursorRepository,
                                 InvestProperties props, Supplier<Map<String, String>> parentEnv) {
        this.digestService = digestService;
        this.cursorRepository = cursorRepository;
        this.props = props;
        this.parentEnv = parentEnv;
    }

    /** 定时入口（21:07）：顶层 try/catch 护调度线程。 */
    @Scheduled(cron = "0 7 21 * * *", zone = "Asia/Shanghai")
    public void runScheduled() {
        try {
            scheduledOnce();
        } catch (Exception e) { // 调度保护：摘要链绝不能炸调度线程
            log.error("记忆摘要定时调度异常", e);
        }
    }

    void scheduledOnce() {
        if (!props.getProfile().isDigestEnabled()) {
            log.info("记忆摘要未启用（invest.profile.digest-enabled=false），跳过");
            return;
        }
        if (isBlank(parentEnv.get().get("DEEPSEEK_API_KEY"))) {
            log.info("DEEPSEEK_API_KEY 缺席，记忆摘要静默跳过（无 key 全批 empty 假成功会永久漏摘要，沿情报域空 key 先例）");
            return;
        }
        var cursor = cursorRepository.find();
        if (cursor.isEmpty()) {
            log.warn("memory_digest_cursor 全局单行缺失（V7 seed 不变量破坏？），记忆摘要跳过，请核查迁移与库状态");
            return;
        }
        digestService.digestAll(cursor.get().lastSuccessAt());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
