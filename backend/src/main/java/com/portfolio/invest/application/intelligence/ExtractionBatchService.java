package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.config.InvestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 情报 LLM 抽取批模板（2026-10-12 审查 B2）：承载新闻/政策/公告三套抽取批誊写的共同骨架——
 * 循环取批（handled/lengthSkipped 同轮防重选）、单条隔离续抽、LLM 通道不可用整批静默跳过、
 * token 护栏停批（当日告警恰一次）、批末 FAILED 置换按日去重告警（话术钩子由子类给词）。
 * 差异点（取批来源/长度阈值口径/单条预处理与落库/告警话术）由子类模板方法提供。
 *
 * <p>失败语义（三域一致）：单条解析失败（含重试 1 次）标 FAILED 终态；LLM 通道不可用
 * （未配置/调用失败）整批静默跳过、条目留 PENDING 下批再试（绝不误杀）；单条意外异常
 * 隔离不阻塞批次。幂等可重入：PENDING→SUCCESS/FAILED 置换，LLM 失效/护栏停批的积压
 * 在游标窗口内由后续批次续抽。
 */
public abstract class ExtractionBatchService<T> {

    private static final Logger log = LoggerFactory.getLogger(ExtractionBatchService.class);

    /** 抽取游标窗口（自然日含当日）：LLM 失效/护栏停批后的积压跨日续抽上界。 */
    static final int EXTRACTION_LOOKBACK_DAYS = 3;

    protected final AlertNotifier alertNotifier;
    protected final InvestProperties props;
    protected final Clock clock;

    private final IntelligenceTokenBudget tokenBudget;

    /** 护栏告警去重：同一自然日最多告警一次（跨日随日期比较自动失效）。 */
    private LocalDate alertedDay;

    /** 解析失败告警去重（独立于护栏告警）：同一自然日最多告警一次。 */
    private LocalDate failedAlertedDay;

    protected ExtractionBatchService(IntelligenceTokenBudget tokenBudget, AlertNotifier alertNotifier,
                                     InvestProperties props, Clock clock) {
        this.tokenBudget = tokenBudget;
        this.alertNotifier = alertNotifier;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 幂等可重入抽取入口（集成测试/运维也可直调）：循环取批直至窗口内无 PENDING、
     * LLM 失效、或护栏停批 → 批末钩子（公告定向推送）→ 按本轮 FAILED 置换数告警（NFR-5）。
     * 同轮已处理条目记录在 {@code handled}——意外异常条目保持 PENDING 但本轮不重选
     * （防同一轮内死循环），由后续批次在游标窗口内再试；短文本条目记入
     * {@code lengthSkipped} 同样同轮不重选，但不送 LLM、不落库、留 PENDING 由窗口自然过期。
     */
    public final void extractPending() {
        Instant batchStart = Instant.now(clock);
        int failedCount = extractInBatches();
        afterBatches(batchStart);
        alertParseFailures(LocalDate.now(clock), failedCount);
    }

    /** 批末钩子：公告批末触发定向推送；新闻/政策无动作（默认空实现）。 */
    protected void afterBatches(Instant batchStart) {
    }

    /** 批处理主体：返回本轮 FAILED 置换条数。 */
    private int extractInBatches() {
        LocalDate today = LocalDate.now(clock);
        Set<Long> handled = new HashSet<>();
        Set<Long> lengthSkipped = new HashSet<>();
        int failedCount = 0;
        while (true) {
            if (tokenBudget.exhausted()) { // 晨间已超限的晚批：不取数不调 LLM 快退
                stopForGuardrail(today);
                return failedCount;
            }
            List<T> batch = findPendingBatch(today).stream()
                    .filter(item -> !handled.contains(itemId(item))
                            && !lengthSkipped.contains(itemId(item)))
                    .toList();
            if (batch.isEmpty()) { // 空批快退（无新披露/窗口内已清空）
                return failedCount;
            }
            for (T item : batch) {
                Long id = itemId(item);
                if (isTooShortForExtraction(item)) { // 长度过滤：不入 handled（未处置），仅同轮防重选
                    lengthSkipped.add(id);
                    onLengthSkipped(id);
                    continue;
                }
                handled.add(id);
                try {
                    ItemStep step = extractAndPersist(item);
                    if (step.lengthSkip()) { // 子类内判定短文本（公告：PDF 解析后联合阈值），同 try 外过滤语义
                        lengthSkipped.add(id);
                        onLengthSkipped(id);
                        continue;
                    }
                    if (step.failedPersisted()) { // FAILED 置换计数（批末告警口径）
                        failedCount++;
                    }
                    if (step.stopBatch()) { // LLM 通道不可用：整批静默跳过，剩余留 PENDING 下批续抽
                        logLlmUnavailable(batch, item);
                        return failedCount;
                    }
                    if (step.inputTokens() != null
                            && !tokenBudget.tryAcquire(step.inputTokens())) { // 本条已落库，剩余停批
                        stopForGuardrail(today);
                        return failedCount;
                    }
                } catch (Exception e) { // 单条隔离：一条失败不拖垮其余
                    onItemError(id, e);
                }
            }
        }
    }

    /** LLM 通道不可用整批静默跳过（三域话术逐字一致）。 */
    private void logLlmUnavailable(List<T> batch, T item) {
        log.warn("情报 LLM 通道不可用，本批跳过（剩余 {} 条留 PENDING 待后续批次续抽，{} 日游标窗口）",
                batch.size() - batch.indexOf(item), EXTRACTION_LOOKBACK_DAYS);
    }

    /** 批末解析失败告警（NFR-5）：本轮有 FAILED 置换且当日未告警过才发，文案含条数；话术由子类给词。 */
    private void alertParseFailures(LocalDate today, int failedCount) {
        if (failedCount == 0 || today.equals(failedAlertedDay)) {
            return;
        }
        failedAlertedDay = today;
        sendParseFailureAlert(failedCount);
    }

    /** 护栏超限停批：当日告警恰一次（AlertNotifier 尽力而为，失败不重试）。 */
    private void stopForGuardrail(LocalDate today) {
        long guardrail = props.getIntelligence().getDailyTokenGuardrail();
        log.warn("情报抽取当日 input token 超护栏（{}），当日停批，剩余条目留 PENDING 待后续批次续抽（{} 日游标窗口）",
                guardrail, EXTRACTION_LOOKBACK_DAYS);
        if (today.equals(alertedDay)) {
            return;
        }
        alertedDay = today;
        boolean ok = alertNotifier.send("⚠️ 情报抽取 token 护栏超限", "red", List.of(
                "当日累计 input tokens 已超过护栏 " + guardrail,
                "当日剩余批已停止，未抽取条目留 PENDING，" + EXTRACTION_LOOKBACK_DAYS + " 日游标窗口内后续批次续抽"));
        if (!ok) {
            log.warn("护栏超限告警推送失败（不重试，同日如再触发按日去重不再重复告警）");
        }
    }

    // ———— 差异点钩子（子类实现） ————

    /** 取批：{@value #EXTRACTION_LOOKBACK_DAYS} 日窗口内 PENDING 按 {@code extract-batch-size} 限批。 */
    protected abstract List<T> findPendingBatch(LocalDate today);

    protected abstract Long itemId(T item);

    /**
     * try 外长度预检（true = 短文本跳过：模板登记 lengthSkipped 并调 {@link #onLengthSkipped}）。
     * 公告恒 false——其长度判定依赖 PDF 解析结果，在 {@link #extractAndPersist} 内完成并回报
     * {@code lengthSkip=true}（同轮防重选语义一致）。
     */
    protected abstract boolean isTooShortForExtraction(T item);

    /** 短文本跳过留痕话术（域差异：id 标签与阈值口径）。 */
    protected abstract void onLengthSkipped(Long id);

    /**
     * 单条处理：子类特有预处理（公告送审预筛/PDF 解析）→ LLM 抽取 → 落库，回报推进信号。
     * 单条意外异常直接外抛——模板按「单条隔离」捕获留痕，本条跳过待后续批次续抽。
     */
    protected abstract ItemStep extractAndPersist(T item) throws Exception;

    /** 单条隔离异常留痕话术。 */
    protected abstract void onItemError(Long id, Exception e);

    /** 批末 FAILED 告警话术（域差异：标题/正文/留痕表名）。 */
    protected abstract void sendParseFailureAlert(int failedCount);

    /**
     * 单条处理回报（{@link #extractAndPersist} 的返回）。
     *
     * @param inputTokens 本条 LLM 调用累计 input tokens（护栏计量输入）；null = 未消耗 LLM（不计护栏）
     * @param failedPersisted 本条是否落了 FAILED 置换（批末告警口径）
     * @param stopBatch LLM 通道不可用：整批静默跳过，剩余留 PENDING 下批续抽
     * @param lengthSkip 子类内判定短文本跳过（模板登记 lengthSkipped 并留痕）
     */
    protected record ItemStep(Long inputTokens, boolean failedPersisted, boolean stopBatch,
                              boolean lengthSkip) {
    }
}
