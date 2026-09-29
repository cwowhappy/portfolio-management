package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.AnnouncementExtractResult;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 公告 LLM 结构化抽取批（MS-21 F07/决策 #11，照 {@link NewsExtractionService}「批量→逐条→
 * 失败隔离」骨架）：工作日 22:40（晚间披露高峰后）/06:40（早间补采后）两批（Asia/Shanghai），
 * 游标窗口内（{@link #EXTRACTION_LOOKBACK_DAYS} 个自然日含当日）的 PENDING 公告按
 * {@code extract-batch-size} 循环取批逐条抽取。
 *
 * <p><b>送审预筛（fix round 1 裁定）</b>：标题命中 {@link #TITLE_REVIEW_KEYWORDS} 任一
 * 关键词（业绩类 + 增持/减持/回购/关联交易宽栏目 4 类），或 ann_type_source 命中
 * {@link #COLUMN_TYPE_KEYWORDS} 十类直判栏目（探测报告 §4.3 备源映射全量；东财中文栏目
 * 可判，巨潮 ann_type_source 为通用分类码链不可判，靠标题臂兜底）——其一即送 LLM。
 * 宽栏目 4 类走正常 LLM 路径（PDF 照常下载、无 pdf_url 凭标题；metrics 预期全 null +
 * undisclosed 全六项，annTypes 获得标签）。未命中的杂项公告（股东大会通知/会议决议/
 * 其他公告等无类型语义条目）不下载不调 LLM，直接 SUCCESS 空抽取落库
 * （metrics/annTypes/pdfText/model 全空）标记已处理——避免永远 PENDING，又不浪费护栏 token。
 *
 * <p>单条链路：PdfFetcher 下载 → {@link AnnouncementPdfTextPort} 内存文本 →
 * {@link AnnouncementExtractor} LLM 抽取 → upsertExtract。失败语义：PDF 下载/解析异常/
 * 无文本层（端口空串契约，扫描件重试无益）标 FAILED 终态；无 pdf_url 罕见——跳过下载凭标题
 * 送 LLM（metrics 可能全 null + undisclosed 全量）；LLM 通道不可用整批静默跳过、条目留
 * PENDING 下批再试；单条意外异常隔离不阻塞批次。顶层 try/catch 吞异常护调度线程。
 *
 * <p>短文本跳过：pdfText &lt; {@link #MIN_PDF_TEXT_LENGTH} 且标题 &lt;
 * {@link #MIN_TITLE_LENGTH} 的条目不送 LLM、不落库、留 PENDING（同轮不重选），由游标窗口
 * 自然过期。成本护栏（D16）：每条 LLM 抽取后向共享 {@link IntelligenceTokenBudget} 计入
 * inputTokens（与新闻抽取批同一 bean，跨服务共享当日额度），超限当日停批 + AlertNotifier
 * 每日告警一次；FAILED 批末计数告警（独立按日去重，NFR-5）。
 *
 * <p>批末触发 {@link AnnouncementPushTrigger#pushExtracted}（批起点 Instant，extracted_at
 * 轴 ≥ 含边界恰好覆盖本批置换行）——Task 7 的 AnnouncementPushService 就位前无实现 bean，
 * ObjectProvider 取空跳过（debug 日志）。幂等可重入：PENDING→SUCCESS/FAILED 置换，
 * FAILED 为终态不重试；LLM 失效/护栏停批的积压在游标窗口内由后续批次续抽。
 */
@Service
public class AnnouncementExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementExtractionService.class);

    /** 抽取游标窗口（自然日含当日）：LLM 失效/护栏停批后的积压跨日续抽上界（与新闻同款）。 */
    static final int EXTRACTION_LOOKBACK_DAYS = 3;

    /** 短文本跳过阈值：PDF 文本低于此字符数且标题过短即不值得过 LLM。 */
    static final int MIN_PDF_TEXT_LENGTH = 50;

    /** 短文本跳过阈值：标题低于此字符数（与 PDF 阈值联合判定）。 */
    static final int MIN_TITLE_LENGTH = 8;

    /**
     * 送审标题关键词表（常量化；fix round 1 裁定扩词）：标题命中任一即送 LLM。业绩类
     * （任务原六词 + 真实标题完整形态召回补全——「年报」等缩写作子串匹配不到「年度报告」
     * 标题，召回优先；业绩预增/预减/预亏/扭亏变体）+ 宽栏目 4 类（增持/减持/回购/关联交易
     * ——控制器裁定并入，否则 T8 type 检索 4/11 枚举值结构性恒空；「回购」子串已覆盖
     * 「股份回购」）。
     */
    static final List<String> TITLE_REVIEW_KEYWORDS = List.of(
            "业绩预告", "业绩快报", "业绩预增", "业绩预减", "业绩预亏", "业绩扭亏",
            "定期报告", "年度报告", "半年度报告", "季度报告", "中期报告",
            "年报", "半年报", "季报", "一季报", "三季报", "中报",
            "增持", "减持", "回购", "关联交易");

    /** 源站栏目关键词 → 直判类型对（探测报告 §4.3 备源映射全量，与 collector 栏目判据同款）：命中即送 LLM 且并入 annTypes 并集。 */
    private record ColumnTypeKeyword(String keyword, AnnouncementType type) {
    }

    /**
     * 栏目关键词表（List 保序，直判类型去重稳定），十类全覆盖：业绩 6 类专属栏目 +
     * 宽栏目 4 类（增持/减持/回购/关联交易——fix round 1 裁定并入：关注集标的的宽栏目
     * 公告经 LLM 获得标签）。巨潮 ann_type_source 为通用码链不在此列（标题臂兜底）。
     */
    private static final List<ColumnTypeKeyword> COLUMN_TYPE_KEYWORDS = List.of(
            new ColumnTypeKeyword("报告全文", AnnouncementType.PERIODIC_REPORT),
            new ColumnTypeKeyword("报告摘要", AnnouncementType.PERIODIC_REPORT),
            new ColumnTypeKeyword("业绩预告", AnnouncementType.EARNINGS_FORECAST),
            new ColumnTypeKeyword("业绩快报", AnnouncementType.EARNINGS_FLASH),
            new ColumnTypeKeyword("增发", AnnouncementType.PLACEMENT),
            new ColumnTypeKeyword("配股", AnnouncementType.PLACEMENT),
            new ColumnTypeKeyword("股权激励", AnnouncementType.EQUITY_INCENTIVE),
            new ColumnTypeKeyword("退市", AnnouncementType.DELISTING_RISK),
            new ColumnTypeKeyword("风险警示", AnnouncementType.DELISTING_RISK),
            new ColumnTypeKeyword("增持", AnnouncementType.INCREASE_HOLD),
            new ColumnTypeKeyword("减持", AnnouncementType.DECREASE_HOLD),
            new ColumnTypeKeyword("回购", AnnouncementType.BUYBACK),
            new ColumnTypeKeyword("关联交易", AnnouncementType.RELATED_TRANSACTION));

    private final AnnouncementRepository announcementRepository;
    private final IntelligenceChatPort chatPort;
    private final AnnouncementPdfTextPort pdfTextPort;
    private final PdfFetcher pdfFetcher;
    private final IntelligenceTokenBudget tokenBudget;
    private final AlertNotifier alertNotifier;
    private final InvestProperties props;
    private final AnnouncementExtractor extractor;
    private final ObjectProvider<AnnouncementPushTrigger> pushTriggerProvider;
    private final Clock clock;

    /** 护栏告警去重：同一自然日最多告警一次（跨日随日期比较自动失效）。 */
    private LocalDate alertedDay;

    /** 解析失败告警去重（独立于护栏告警）：同一自然日最多告警一次。 */
    private LocalDate failedAlertedDay;

    @Autowired
    public AnnouncementExtractionService(AnnouncementRepository announcementRepository,
                                         IntelligenceChatPort chatPort,
                                         AnnouncementPdfTextPort pdfTextPort,
                                         PdfFetcher pdfFetcher,
                                         IntelligenceTokenBudget tokenBudget,
                                         AlertNotifier alertNotifier,
                                         InvestProperties props,
                                         ObjectProvider<AnnouncementPushTrigger> pushTriggerProvider) {
        this(announcementRepository, chatPort, pdfTextPort, pdfFetcher, tokenBudget, alertNotifier,
                props, pushTriggerProvider, new AnnouncementExtractor(),
                Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /** 测试构造器：注入抽取器与时钟。 */
    AnnouncementExtractionService(AnnouncementRepository announcementRepository,
                                  IntelligenceChatPort chatPort,
                                  AnnouncementPdfTextPort pdfTextPort,
                                  PdfFetcher pdfFetcher,
                                  IntelligenceTokenBudget tokenBudget,
                                  AlertNotifier alertNotifier,
                                  InvestProperties props,
                                  ObjectProvider<AnnouncementPushTrigger> pushTriggerProvider,
                                  AnnouncementExtractor extractor,
                                  Clock clock) {
        this.announcementRepository = announcementRepository;
        this.chatPort = chatPort;
        this.pdfTextPort = pdfTextPort;
        this.pdfFetcher = pdfFetcher;
        this.tokenBudget = tokenBudget;
        this.alertNotifier = alertNotifier;
        this.props = props;
        this.pushTriggerProvider = pushTriggerProvider;
        this.extractor = extractor;
        this.clock = clock;
    }

    @Scheduled(cron = "0 40 22 * * MON-FRI", zone = "Asia/Shanghai")
    public void extractPendingNight() {
        try {
            extractPending();
        } catch (Exception e) { // 调度保护：批处理绝不能炸调度线程
            log.error("公告抽取晚间批异常", e);
        }
    }

    @Scheduled(cron = "0 40 6 * * MON-FRI", zone = "Asia/Shanghai")
    public void extractPendingMorning() {
        try {
            extractPending();
        } catch (Exception e) { // 调度保护：批处理绝不能炸调度线程
            log.error("公告抽取早间批异常", e);
        }
    }

    /**
     * 幂等可重入抽取入口（集成测试/运维也可直调）：批起点记 Instant → 循环取批直至窗口内
     * 无 PENDING、LLM 失效或护栏停批 → 批末先触发定向推送（批起点，本批置换行 extracted_at
     * ≥ 该值全命中）再按本轮 FAILED 置换数告警（NFR-5）。同轮已处理条目记 {@code handled}
     * （含杂项空抽取与异常跳过者，同轮不重选）；短文本条目记 {@code lengthSkipped} 同样
     * 同轮不重选，但不送 LLM、不落库、留 PENDING 由窗口自然过期。
     */
    public void extractPending() {
        Instant batchStart = Instant.now(clock);
        int failedCount = extractInBatches();
        pushExtracted(batchStart);
        alertParseFailures(LocalDate.now(clock), failedCount);
    }

    /** 批处理主体：返回本轮 FAILED 置换条数（含 PDF 阶段失败与 LLM 解析失败）。 */
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
            List<AnnouncementRecord> batch = announcementRepository
                    .findPendingForExtraction(today, EXTRACTION_LOOKBACK_DAYS,
                            props.getIntelligence().getExtractBatchSize())
                    .stream().filter(a -> !handled.contains(a.id())
                            && !lengthSkipped.contains(a.id())).toList();
            if (batch.isEmpty()) { // 空批快退（无新披露/窗口内已清空）
                return failedCount;
            }
            for (AnnouncementRecord announcement : batch) {
                try {
                    if (!isExtractable(announcement)) { // 未命中送审预筛（杂项）：SUCCESS 空抽取标记已处理
                        announcementRepository.upsertExtract(announcement.id(), unreviewedResult());
                        handled.add(announcement.id());
                        log.debug("杂项公告跳过 LLM 抽取（announcementId={}，title={}）：SUCCESS 空抽取落库",
                                announcement.id(), announcement.title());
                        continue;
                    }
                    String pdfText = resolvePdfText(announcement); // null = 无 pdf_url 凭标题降级
                    if (tooShortForExtraction(announcement.title(), pdfText)) {
                        // 不入 handled（未处置），仅同轮防重选；留 PENDING 由窗口自然过期
                        lengthSkipped.add(announcement.id());
                        log.info("短文本跳过（announcementId={}，pdfText<{} 且标题<{}）：不送 LLM、不落库，留 PENDING 由 {} 日游标窗口自然过期",
                                announcement.id(), MIN_PDF_TEXT_LENGTH, MIN_TITLE_LENGTH,
                                EXTRACTION_LOOKBACK_DAYS);
                        continue;
                    }
                    handled.add(announcement.id());
                    AnnouncementExtractor.ExtractionOutcome outcome = extractor.extractOne(
                            chatPort, announcement.title(), pdfText == null ? "" : pdfText,
                            directTypesFromColumn(announcement.annTypeSource()));
                    if (outcome.status() == AnnouncementExtractor.OutcomeStatus.LLM_UNAVAILABLE) {
                        log.warn("情报 LLM 通道不可用，本批跳过（剩余 {} 条留 PENDING 待后续批次续抽，{} 日游标窗口）",
                                batch.size() - batch.indexOf(announcement), EXTRACTION_LOOKBACK_DAYS);
                        return failedCount;
                    }
                    AnnouncementExtractResult result = toResult(outcome, pdfText);
                    announcementRepository.upsertExtract(announcement.id(), result);
                    if (result.status() == ExtractStatus.FAILED) { // FAILED 置换计数（批末告警口径）
                        failedCount++;
                    }
                    if (!tokenBudget.tryAcquire(outcome.inputTokens())) { // 本条已落库，剩余停批
                        stopForGuardrail(today);
                        return failedCount;
                    }
                } catch (PdfStageException e) { // PDF 阶段失败：单条 FAILED 终态，不阻塞批次
                    handled.add(announcement.id());
                    log.warn("公告 PDF 获取/解析失败（announcementId={}，pdfUrl={}）：标 FAILED 终态——{}",
                            announcement.id(), announcement.pdfUrl(), e.getMessage());
                    try {
                        announcementRepository.upsertExtract(announcement.id(), failedResult());
                        failedCount++;
                    } catch (Exception upsertError) { // FAILED 落库也失败：留 PENDING 后续批续试
                        log.error("FAILED 落库异常（announcementId={}），本条跳过待后续批次续抽",
                                announcement.id(), upsertError);
                    }
                } catch (Exception e) { // 单条隔离：一条失败不拖垮其余
                    handled.add(announcement.id());
                    log.error("单条公告抽取异常（announcementId={}），本条跳过待后续批次续抽",
                            announcement.id(), e);
                }
            }
        }
    }

    /** 送审判定：栏目直判命中或标题含送审关键词（二者其一即送 LLM）。 */
    private static boolean isExtractable(AnnouncementRecord announcement) {
        return !directTypesFromColumn(announcement.annTypeSource()).isEmpty()
                || containsReviewKeyword(announcement.title());
    }

    private static boolean containsReviewKeyword(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        return TITLE_REVIEW_KEYWORDS.stream().anyMatch(title::contains);
    }

    /** ann_type_source 栏目关键词 → 直判类型（保序去重；null/空栏目归空列表）。 */
    private static List<AnnouncementType> directTypesFromColumn(String annTypeSource) {
        if (annTypeSource == null || annTypeSource.isBlank()) {
            return List.of();
        }
        LinkedHashSet<AnnouncementType> types = new LinkedHashSet<>();
        for (ColumnTypeKeyword entry : COLUMN_TYPE_KEYWORDS) {
            if (annTypeSource.contains(entry.keyword())) {
                types.add(entry.type());
            }
        }
        return List.copyOf(types);
    }

    /**
     * 下载并解析公告 PDF 文本。无 pdf_url 返回 null（凭标题降级送 LLM）；下载失败（受检
     * {@link PdfFetcher.PdfFetchException}）/解析异常（{@link AnnouncementPdfTextPortException}）/
     * 无文本层（端口空串契约，扫描件重试无益）统一抛 {@link PdfStageException}。
     */
    private String resolvePdfText(AnnouncementRecord announcement) throws PdfStageException {
        String pdfUrl = announcement.pdfUrl();
        if (pdfUrl == null || pdfUrl.isBlank()) {
            return null;
        }
        try {
            byte[] pdf = pdfFetcher.download(pdfUrl);
            String text = pdfTextPort.extract(pdf);
            if (text == null || text.isBlank()) {
                throw new PdfStageException("公告 PDF 无文本层（扫描件/图片型），重试无益");
            }
            return text;
        } catch (PdfFetcher.PdfFetchException | AnnouncementPdfTextPortException e) {
            throw new PdfStageException(e.getMessage(), e);
        }
    }

    /** 短文本过滤：pdfText（null 计 0）与标题（null 计 0）均低于阈值才跳过（联合判定）。 */
    private static boolean tooShortForExtraction(String title, String pdfText) {
        int titleLength = title == null ? 0 : title.length();
        int pdfLength = pdfText == null ? 0 : pdfText.length();
        return pdfLength < MIN_PDF_TEXT_LENGTH && titleLength < MIN_TITLE_LENGTH;
    }

    /** 未送审（杂项）公告空抽取结果：SUCCESS + metrics/annTypes/pdfText/model 全空（「未经模型抽取」标识）。 */
    private AnnouncementExtractResult unreviewedResult() {
        return new AnnouncementExtractResult(null, List.of(), null, ExtractStatus.SUCCESS,
                null, Instant.now(clock));
    }

    /** PDF 阶段失败结果：FAILED + 分析字段空 + 批配置模型留痕（照 P1 FAILED 口径）。 */
    private AnnouncementExtractResult failedResult() {
        return new AnnouncementExtractResult(null, List.of(), null, ExtractStatus.FAILED,
                props.getLlm().getModel(), Instant.now(clock));
    }

    /** 抽取三态 → 落库结果：SUCCESS 带六字段 metrics/并集标签/实际送入 LLM 的 pdfText；FAILED 仅状态与留痕字段。 */
    private AnnouncementExtractResult toResult(AnnouncementExtractor.ExtractionOutcome outcome,
                                               String pdfText) {
        String model = props.getLlm().getModel();
        Instant extractedAt = Instant.now(clock);
        if (outcome.status() == AnnouncementExtractor.OutcomeStatus.SUCCESS) {
            AnnouncementExtractor.ExtractedFields fields = outcome.fields();
            // pdfText 留痕取实际送入 LLM 的文本（无 pdf_url 的降级条目为空串）
            return new AnnouncementExtractResult(fields.metrics(), fields.annTypes(),
                    pdfText == null ? "" : pdfText, ExtractStatus.SUCCESS, model, extractedAt);
        }
        return new AnnouncementExtractResult(null, List.of(), null, ExtractStatus.FAILED,
                model, extractedAt);
    }

    /** PDF 阶段失败信号（下载/解析/无文本层）：单条 FAILED 终态，区别于意外异常的跳过留 PENDING。 */
    private static final class PdfStageException extends Exception {

        PdfStageException(String message) {
            super(message);
        }

        PdfStageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 批末定向推送触发（Task 7 就位前无实现 bean 跳过）；推送异常绝不拖垮抽取批。 */
    private void pushExtracted(Instant since) {
        AnnouncementPushTrigger trigger = pushTriggerProvider.getIfAvailable();
        if (trigger == null) {
            log.debug("公告推送触发器未装配（Task 7 AnnouncementPushService 就位前跳过定向推送）");
            return;
        }
        try {
            trigger.pushExtracted(since);
        } catch (Exception e) { // 双保险：实现侧自吞之外的兜底
            log.error("公告定向推送触发异常（since={}）", since, e);
        }
    }

    /** 批末解析失败告警（NFR-5）：本轮有 FAILED 置换且当日未告警过才发，文案含条数；尽力而为不重试。 */
    private void alertParseFailures(LocalDate today, int failedCount) {
        if (failedCount == 0 || today.equals(failedAlertedDay)) {
            return;
        }
        failedAlertedDay = today;
        boolean ok = alertNotifier.send("⚠️ 公告抽取出现失败条目", "red", List.of(
                "本轮抽取有 " + failedCount + " 条公告失败（PDF 下载/解析失败或 LLM 输出解析失败），已标 FAILED 终态",
                "FAILED 不自动重试，请抽查 intelligence_announcement_extract 的 FAILED 行评估 PDF 链路与提示词稳定性"));
        if (!ok) {
            log.warn("公告抽取失败告警推送失败（不重试，同日如再触发按日去重不再重复告警）");
        }
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
}
