package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BindingCodeRepository;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.observability.ObservabilityRecorder;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 情报数据滚动清理：每日 04:07 四步清扫——collector 夜间低频采集在 0/2/4/6 点整点跑
 * （tasks/news_night.yaml），错开至 07 分避免与批量写入撞车，也不落整点半点限流时段——
 *
 * <ul>
 *   <li>新闻 90 天滚动：{@code published_at} 早于 now-90d 的 raw 删除，extract 经
 *       FK ON DELETE CASCADE 级联（业务时间轴口径，TIMESTAMPTZ 直接传 Instant 无时区
 *       歧义）；</li>
 *   <li>绑定码过期清扫：{@code expires_at} 早于 now-1d 的码扫除（DDL D8「过期由清理
 *       任务扫除」；TTL 本身 10 分钟，1d 缓冲让在途核销不受清扫竞态影响）；</li>
 *   <li>验证码 90 天滚动（B11）：{@code created_at} 早于 now-90d 的码删除（严格小于，
 *       恰在 cutoff 的行保留）。TTL 分钟级、核销/防重读即时生效，90 天窗口仅为注册/
 *       找回密码流水的最低审计追溯期，与新闻保留同口径。</li>
 *   <li>观测数据滚动清理（MS-30 B6/Task 10）：tool_invocation_obs（{@code called_at}）与
 *       turn_observation（{@code created_at}）早于 now-retention-days 的观测行直删
 *       （{@link ObservabilityRecorder#purgeBefore}，严格小于恰在 cutoff 的行保留），
 *       保留天数 {@code invest.eval.observability.retention-days} 默认 90，与新闻/
 *       验证码保留同口径；user_id 弱引用无 FK，用户残留观测随保留期自然过期。</li>
 * </ul>
 *
 * <p>四步各自 try/catch 隔离：一步失败（记 ERROR）不挡其余三步；调度入口再顶层吞异常
 * 护调度线程（照 PrincipleAlertService / BriefPushService 先例，尽力而为不炸线程）。
 */
@Service
public class IntelligenceCleanupService {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceCleanupService.class);

    /** 市场时区（与调度 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 新闻保留窗口：90 天滚动。 */
    private static final Duration NEWS_RETENTION = Duration.ofDays(90);

    /** 绑定码过期缓冲：TTL（10 分钟）之外再留 1 天才扫，避开在途核销竞态。 */
    private static final Duration BINDING_CODE_GRACE = Duration.ofDays(1);

    /** 验证码保留窗口：90 天滚动（最低审计追溯期，与新闻保留同口径）。 */
    private static final Duration CODE_RETENTION = Duration.ofDays(90);

    /** 观测数据保留窗口兜底：retention-days 配置异常（非正）时按 90 天，护栏不放大删除面。 */
    private static final Duration OBSERVABILITY_RETENTION_FLOOR = Duration.ofDays(90);

    private final NewsRepository newsRepository;
    private final BindingCodeRepository bindingCodeRepository;
    private final VerificationCodeRepository codeRepository;
    private final ObservabilityRecorder observabilityRecorder;
    private final InvestProperties properties;
    private final Clock clock;

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口）。 */
    @Autowired
    public IntelligenceCleanupService(NewsRepository newsRepository,
                                      BindingCodeRepository bindingCodeRepository,
                                      VerificationCodeRepository codeRepository,
                                      ObservabilityRecorder observabilityRecorder,
                                      InvestProperties properties) {
        this(newsRepository, bindingCodeRepository, codeRepository, observabilityRecorder,
                properties, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟。 */
    IntelligenceCleanupService(NewsRepository newsRepository,
                               BindingCodeRepository bindingCodeRepository,
                               VerificationCodeRepository codeRepository,
                               ObservabilityRecorder observabilityRecorder,
                               InvestProperties properties, Clock clock) {
        this.newsRepository = newsRepository;
        this.bindingCodeRepository = bindingCodeRepository;
        this.codeRepository = codeRepository;
        this.observabilityRecorder = observabilityRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 7 4 * * *", zone = "Asia/Shanghai")
    public void cleanupScheduled() {
        try {
            cleanupNow();
        } catch (Exception e) { // 调度保护：清理任务绝不能炸调度线程
            log.error("情报数据清理调度异常", e);
        }
    }

    /** 清理入口（集成测试/运维也可直调）：新闻 90 天滚动 + 绑定码过期清扫 + 验证码 90 天滚动 + 观测数据 retention-days 滚动，四步互不阻断。 */
    public void cleanupNow() {
        Instant now = Instant.now(clock);
        try {
            long deletedNews = newsRepository.deleteRawBefore(now.minus(NEWS_RETENTION));
            log.info("情报新闻滚动清理完成：cutoff={}，删除 {} 行", now.minus(NEWS_RETENTION), deletedNews);
        } catch (Exception e) { // 一步失败不挡其余三步
            log.error("情报新闻滚动清理失败", e);
        }
        try {
            int deletedCodes = bindingCodeRepository.deleteExpiredBefore(now.minus(BINDING_CODE_GRACE));
            log.info("过期绑定码清扫完成：cutoff={}，删除 {} 行", now.minus(BINDING_CODE_GRACE), deletedCodes);
        } catch (Exception e) { // 一步失败不挡其余三步
            log.error("过期绑定码清扫失败", e);
        }
        try {
            int deletedVerificationCodes = codeRepository.deleteCreatedBefore(now.minus(CODE_RETENTION));
            log.info("过期验证码滚动清理完成：cutoff={}，删除 {} 行", now.minus(CODE_RETENTION), deletedVerificationCodes);
        } catch (Exception e) { // 一步失败不挡其余三步
            log.error("过期验证码滚动清理失败", e);
        }
        try {
            Instant observabilityCutoff = now.minus(observabilityRetention());
            int purged = observabilityRecorder.purgeBefore(observabilityCutoff);
            log.info("观测数据滚动清理完成：cutoff={}，删除 {} 行（两表合计）", observabilityCutoff, purged);
        } catch (Exception e) { // 一步失败不挡其余三步
            log.error("观测数据滚动清理失败", e);
        }
    }

    /** 观测保留窗口：invest.eval.observability.retention-days（默认 90）；非正配置回退 90 天——DELETE 面不因误配放大。 */
    private Duration observabilityRetention() {
        int retentionDays = properties.getEval().getObservability().getRetentionDays();
        return retentionDays > 0 ? Duration.ofDays(retentionDays) : OBSERVABILITY_RETENTION_FLOOR;
    }
}
