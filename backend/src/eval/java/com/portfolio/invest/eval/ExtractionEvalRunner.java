package com.portfolio.invest.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.portfolio.invest.application.intelligence.AnnouncementExtractor;
import com.portfolio.invest.application.intelligence.IntelligenceChatPort;
import com.portfolio.invest.application.intelligence.NewsExtractor;
import com.portfolio.invest.application.intelligence.PolicyExtractor;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ImportanceGrade;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import com.portfolio.invest.infrastructure.intelligence.AgentScopeIntelligenceChatPort;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 抽取质量 eval 回归入口（D21 + MS-21 Task 9）：题库（{@code src/eval/resources/extraction/*.yaml}）
 * 逐题直调抽取纯组件，LLM 通道走生产同款 {@link AgentScopeIntelligenceChatPort}。Model 在本进程
 * 直构（无 Spring 上下文）：与 agent/AgentConfig#investModel 同款
 * {@code ModelRegistry.resolve("deepseek:"+model)}；ObjectProvider 以最小匿名实现提供该 Model，
 * 不改生产类可见性。唯一差异：apiKey 显式入 ModelCreationContext（生产靠 provider 回退
 * System.getenv），使仅存在于 .env 的 key 也可用。
 *
 * <p><b>三 kind（MS-21 Task 9 双 kind + MS-22 Task 8 增政策）</b>：题库 {@code kind} 字段区分
 * 新闻题（缺省 news，P1 题库无该字段）、公告题（announcement，直调
 * {@link AnnouncementExtractor#extractOne}）与政策题（policy，直调
 * {@link PolicyExtractor#extractOne}）。新闻题比对口径（题库文件头注释与本实现互为对照）：
 * stock_codes 全集相等（空数组=断言无标的）；direction 期望非 null 时须一致（null=容忍不确定，
 * 不断言）；importance 按 80/50 阈值判档，跨档但分数落在阈值 ±10 内按容差不计错；event_type
 * 期望非空时须一致（大小写不敏感）。公告题比对口径：metrics 五数值字段（revenueYi/netProfitYi/
 * netProfitYoyPct/deductedProfitYi/grossMarginPct）期望键 BigDecimal 等值（compareTo，容忍尾零
 * 差异；期望缺失该键=不断言）；dividendDesc 期望非 null=断言实际非 null（披露识别；文本措辞
 * 不强一致，报告留实得值人工抽阅，null 断言经 undisclosed「分红」联动守护——YAML 显式 null
 * 与缺省不可区分，不做 null 直断）；undisclosed 期望每项须在实际 undisclosed 命中，且六个规范名
 * 联动断言对应字段为 null（未披露字段 null 且进 undisclosed 的契约）；annTypes 期望集 ⊆ 实际集
 * （栏目直判 ∪ LLM 精判并集语义，允许多）。政策题比对口径：policy_direction/strength/confidence
 * 三受控枚举期望非 null 时须一致（null=容忍不确定，不断言）；affected_areas 期望集 ⊆ 实际集
 * （部分命中，允许多；期望缺失=不断言）；is_policy 期望非 null 时须一致（false=非政策兜底行——
 * PolicyExtractor 哨兵置换 direction=NEUTRAL/strength=LOW/confidence=LOW 落库而非丢弃，
 * 期望哨兵值即守护该转换链）；summary 恒断言非空（SUCCESS 的解析契约锚点）。
 *
 * <p>报告写 {@code backend/build/reports/eval-extraction/eval-report.{json,md}}
 * （schema=eval-extraction-report/3，公告维度为 /2 新增、政策维度为 /3 新增；汇总：parse 成功率 +
 * 新闻四指标 + 公告三指标 metrics/undisclosed/annTypes + 政策五指标 direction/strength/
 * confidence/affectedAreas/isPolicy + 总体 PASS）。
 *
 * <p>定位（D21，同 evalAgent 的 agent-testing 口径）：报告即产出——退出码恒 0、不抛
 * （题库 schema 错误与框架异常写 ERROR 报告后同样正常退出）；DEEPSEEK_API_KEY 未配置时
 * 写 SKIP 报告正常退出。不挂 CI 门禁；提示词/模型/参数变更 PR 须附最新报告
 * （流程约束见 rubric/extraction.md）。入口 {@code make eval-extraction}。
 */
public final class ExtractionEvalRunner {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 档位判档阈值（与落库口径一致：80/50）。 */
    private static final int MAJOR_AT = 80;
    private static final int WATCH_AT = 50;
    /** 档位容差：跨档但分数落在阈值 ±10 内不计错（题库口径，rubric/extraction.md）。 */
    private static final int GRADE_TOLERANCE = 10;

    /** kind 取值（题库缺省 news——P1 新闻题库无 kind 字段）。 */
    private static final String KIND_NEWS = "news";
    private static final String KIND_ANNOUNCEMENT = "announcement";
    private static final String KIND_POLICY = "policy";

    /**
     * 公告 metrics 五数值字段白名单（期望 metrics 键的 fail-fast 校验；dividendDesc 为文本，
     * 不在数值 map 内）。
     */
    private static final Set<String> METRIC_NUMERIC_KEYS = Set.of(
            "revenueYi", "netProfitYi", "netProfitYoyPct", "deductedProfitYi", "grossMarginPct");

    /** undisclosed 规范名 → metrics 组件（联动 null 断言的映射，与提示词规范名一致）。 */
    private static final Map<String, String> UNDISCLOSED_FIELDS = Map.of(
            "营业收入", "revenueYi",
            "归母净利润", "netProfitYi",
            "归母净利润同比", "netProfitYoyPct",
            "扣非净利润", "deductedProfitYi",
            "毛利率", "grossMarginPct",
            "分红", "dividendDesc");

    private static final Path REPORT_DIR = Path.of("build", "reports", "eval-extraction");

    // ———— 题库 ————

    /**
     * 期望（snake_case 键与题库 YAML 对齐）。前四项为新闻题（kind=news）；四项之后为公告题
     * （kind=announcement）：metrics（五数值字段 camelCase 键 → 期望 BigDecimal）、
     * dividend_desc、undisclosed、ann_types（AnnouncementType 枚举名数组，装载时 fail-fast 校验）；
     * 末五项为政策题（kind=policy）：policy_direction/strength/confidence 三受控枚举、
     * affected_areas、is_policy（false=非政策哨兵转换链断言）。
     */
    public record Expectation(
            @JsonProperty("stock_codes") List<String> stockCodes,
            Direction direction,
            ImportanceGrade importance,
            @JsonProperty("event_type") String eventType,
            Map<String, BigDecimal> metrics,
            @JsonProperty("dividend_desc") String dividendDesc,
            List<String> undisclosed,
            @JsonProperty("ann_types") List<String> annTypes,
            @JsonProperty("policy_direction") PolicyDirection policyDirection,
            PolicyStrength strength,
            PolicyConfidence confidence,
            @JsonProperty("affected_areas") List<String> affectedAreas,
            @JsonProperty("is_policy") Boolean isPolicy) {
    }

    /** kind 缺省 news；news 用 summary、announcement 用 pdf_text、policy 用 content_text。 */
    public record ExtractionQuestion(String id, String kind, String title, String summary,
            @JsonProperty("pdf_text") String pdfText,
            @JsonProperty("content_text") String contentText, Expectation expect) {
    }

    /** 单题单维度结论：PASS 一致 / TOLERATED 跨档但阈值±10 内 / FAIL 不一致 / SKIP 未断言或无法比对。 */
    private enum Status { PASS, TOLERATED, FAIL, SKIP }

    private record DimensionResult(String name, Status status, String detail) {
    }

    /**
     * outcome 取抽取器三态 + ERROR（单题框架异常兜底，理论不可达：端口吞异常）；
     * newsFields/announcementFields/policyFields 按 kind 三选一非空（非 SUCCESS 均为 null）。
     */
    private record QuestionResult(ExtractionQuestion question, String outcome, boolean pass,
                                  long durationMs, long inputTokens,
                                  NewsExtractor.ExtractedFields newsFields,
                                  AnnouncementExtractor.ExtractedFields announcementFields,
                                  PolicyExtractor.ExtractedFields policyFields,
                                  List<DimensionResult> dimensions) {
    }

    private record Summary(int total, int newsTotal, int announcementTotal, int policyTotal,
                           int success,
                           int parseFailed, int llmUnavailable, int error,
                           int stockPass, int stockAsserted,
                           int directionPass, int directionAsserted,
                           int gradeExact, int gradeTolerated, int gradeAsserted,
                           int annMetricsPass, int annMetricsAsserted,
                           int annUndisclosedPass, int annUndisclosedAsserted,
                           int annTypesPass, int annTypesAsserted,
                           int policyDirectionPass, int policyDirectionAsserted,
                           int policyStrengthPass, int policyStrengthAsserted,
                           int policyConfidencePass, int policyConfidenceAsserted,
                           int policyAreasPass, int policyAreasAsserted,
                           int policyIsPolicyPass, int policyIsPolicyAsserted,
                           int overallPass) {
    }

    public static void main(String[] args) {
        try {
            new ExtractionEvalRunner().run(args);
        } catch (Throwable t) {
            // 兜底：报告即产出（D21）——框架性异常（如题库 schema 违约）写 ERROR 报告后照常退出 0
            t.printStackTrace();
            try {
                new Report(REPORT_DIR).writeError(t);
            } catch (Exception suppressed) {
                suppressed.printStackTrace();
            }
        }
        System.exit(0);
    }

    private void run(String[] args) throws IOException {
        boolean list = false;
        for (String arg : args) {
            if ("--list".equals(arg)) {
                list = true;
            } else {
                throw new IllegalArgumentException("未知参数: " + arg + "（支持 --list）");
            }
        }
        List<ExtractionQuestion> questions = loadQuestions();
        Report report = new Report(REPORT_DIR);

        // —— --list：只装载校验题库并打印清单（干跑，不起 LLM、不连任何外部端点） ——
        if (list) {
            System.out.printf("%-40s %-12s %-8s %-7s %-10s %s%n",
                    "id", "kind", "dir", "grade", "event", "stocks/annTypes/areas");
            for (ExtractionQuestion q : questions) {
                System.out.printf("%-40s %-12s %-8s %-7s %-10s %s%n", q.id(),
                        kindOf(q),
                        expectDirectionLabel(q),
                        q.expect().importance(),
                        q.expect().eventType() == null ? "-" : q.expect().eventType(),
                        expectTailLabel(q));
            }
            System.out.printf("[eval-extraction] --list 干跑通过：装载 %d 题（news %d / announcement %d / policy %d），"
                            + "schema 校验全部通过（未起 LLM）%n",
                    questions.size(),
                    questions.stream().filter(q -> KIND_NEWS.equals(kindOf(q))).count(),
                    questions.stream().filter(ExtractionEvalRunner::isAnnouncement).count(),
                    questions.stream().filter(ExtractionEvalRunner::isPolicy).count());
            return;
        }

        // —— 环境解析：进程环境变量优先，缺失回退仓库根 .env（EnvSupport，同 EvalRunner） ——
        Map<String, String> dotEnv = EnvSupport.loadDotEnv(EnvSupport.repoRoot());
        String apiKey = EnvSupport.resolve("DEEPSEEK_API_KEY", dotEnv).orElse(null);
        if (apiKey == null) {
            String reason = "缺少 DEEPSEEK_API_KEY：请 export DEEPSEEK_API_KEY=<key>，或在仓库根 .env 配置"
                    + "（make eval-extraction / gradlew evalExtraction 会自动把 .env 的 key 注入子进程环境变量）";
            System.err.println("[eval-extraction] " + reason + "——本轮写 SKIP 报告后正常退出。");
            report.writeSkip(reason);
            System.out.printf("[eval-extraction] SKIP 报告已生成：%s%n", REPORT_DIR);
            return;
        }
        String model = EnvSupport.resolve("DEEPSEEK_MODEL", dotEnv).orElse("deepseek-v4-flash");
        String baseUrl = EnvSupport.resolve("DEEPSEEK_BASE_URL", dotEnv).orElse("https://api.deepseek.com");

        // —— Model 直构 + 生产同款端口（见类 javadoc） ——
        Model investModel = ModelRegistry.resolve(
                "deepseek:" + model,
                ModelCreationContext.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .stream(true)
                        // 与 AgentConfig#investModel 同款默认 GenerateOptions（0.3/禁并行工具调用）——
                        // 抽取调用时端口以 temperature 0 覆盖，与生产同路径
                        .component(GenerateOptions.class, GenerateOptions.builder()
                                .parallelToolCalls(false)
                                .temperature(0.3)
                                .build())
                        .build());
        ObjectProvider<Model> models = new ObjectProvider<>() {
            @Override
            public Model getObject() {
                throw new UnsupportedOperationException("eval 直构 Model，无 Spring 容器（getObject 不可用）");
            }

            @Override
            public Model getIfAvailable() {
                return investModel;
            }
        };
        IntelligenceChatPort chatPort = new AgentScopeIntelligenceChatPort(models);
        NewsExtractor newsExtractor = new NewsExtractor();
        AnnouncementExtractor announcementExtractor = new AnnouncementExtractor();
        PolicyExtractor policyExtractor = new PolicyExtractor();

        System.out.printf("[eval-extraction] 题库装载 %d 题；通道=AgentScopeIntelligenceChatPort（deepseek/%s @ %s）%n",
                questions.size(), model, baseUrl);
        List<QuestionResult> results = new ArrayList<>();
        for (ExtractionQuestion q : questions) {
            QuestionResult result = runOne(newsExtractor, announcementExtractor, policyExtractor,
                    chatPort, q);
            results.add(result);
            String misses = result.dimensions().stream()
                    .filter(d -> d.status() == Status.FAIL)
                    .map(d -> d.name())
                    .reduce((a, b) -> a + "," + b).orElse("");
            System.out.printf("[eval-extraction] %-40s %-14s %-4s（%.1fs，in %d tok）%s%n",
                    q.id(), result.outcome(), result.pass() ? "PASS" : "FAIL",
                    result.durationMs() / 1000.0, result.inputTokens(),
                    misses.isEmpty() ? "" : "[" + misses + " FAIL]");
        }

        report.writeRun(results, model, baseUrl);
        Summary s = summarize(results);
        System.out.printf("[eval-extraction] 报告已生成：%s%n[eval-extraction]           %s%n",
                REPORT_DIR.resolve("eval-report.json"), REPORT_DIR.resolve("eval-report.md"));
        System.out.printf(
                "[eval-extraction] 汇总：parse 成功率 %d/%d｜总体 PASS %d/%d%n",
                s.success(), s.total(), s.overallPass(), s.total());
        if (s.newsTotal() > 0) {
            System.out.printf(
                    "[eval-extraction]   新闻（%d 题）：stock 命中率 %d/%d｜direction 一致率 %d/%d｜"
                            + "importance 档位一致率（严格 %d/%d，含容差 %d/%d）%n",
                    s.newsTotal(), s.stockPass(), s.stockAsserted(),
                    s.directionPass(), s.directionAsserted(),
                    s.gradeExact(), s.gradeAsserted(), s.gradeExact() + s.gradeTolerated(), s.gradeAsserted());
        }
        if (s.announcementTotal() > 0) {
            System.out.printf(
                    "[eval-extraction]   公告（%d 题）：metrics 一致率 %d/%d｜undisclosed 命中率 %d/%d｜"
                            + "annTypes 命中率 %d/%d%n",
                    s.announcementTotal(), s.annMetricsPass(), s.annMetricsAsserted(),
                    s.annUndisclosedPass(), s.annUndisclosedAsserted(),
                    s.annTypesPass(), s.annTypesAsserted());
        }
        if (s.policyTotal() > 0) {
            System.out.printf(
                    "[eval-extraction]   政策（%d 题）：direction 一致率 %d/%d｜strength 一致率 %d/%d｜"
                            + "confidence 一致率 %d/%d｜affectedAreas 命中率 %d/%d｜isPolicy 一致率 %d/%d%n",
                    s.policyTotal(), s.policyDirectionPass(), s.policyDirectionAsserted(),
                    s.policyStrengthPass(), s.policyStrengthAsserted(),
                    s.policyConfidencePass(), s.policyConfidenceAsserted(),
                    s.policyAreasPass(), s.policyAreasAsserted(),
                    s.policyIsPolicyPass(), s.policyIsPolicyAsserted());
        }
    }

    // ———— 单题执行与比对 ————

    private QuestionResult runOne(NewsExtractor newsExtractor, AnnouncementExtractor announcementExtractor,
                                  PolicyExtractor policyExtractor,
                                  IntelligenceChatPort chatPort, ExtractionQuestion q) {
        if (isAnnouncement(q)) {
            return runAnnouncementOne(announcementExtractor, chatPort, q);
        }
        if (isPolicy(q)) {
            return runPolicyOne(policyExtractor, chatPort, q);
        }
        return runNewsOne(newsExtractor, chatPort, q);
    }

    private QuestionResult runNewsOne(NewsExtractor extractor, IntelligenceChatPort chatPort,
                                      ExtractionQuestion q) {
        long start = System.currentTimeMillis();
        try {
            NewsExtractor.ExtractionOutcome outcome = extractor.extractOne(chatPort, q.title(), q.summary());
            long duration = System.currentTimeMillis() - start;
            if (outcome.status() != NewsExtractor.OutcomeStatus.SUCCESS) {
                return failed(q, outcome.status().name(), duration, outcome.inputTokens(),
                        newsSkipDimensions("抽取未成功（" + outcome.status() + "），字段比对跳过"));
            }
            List<DimensionResult> dimensions = evaluateNews(q, outcome.fields());
            boolean pass = dimensions.stream().noneMatch(d -> d.status() == Status.FAIL);
            return new QuestionResult(q, outcome.status().name(), pass, duration, outcome.inputTokens(),
                    outcome.fields(), null, null, dimensions);
        } catch (Throwable t) { // 理论不可达（端口吞异常不抛）；防单题框架异常中断整批
            return failed(q, "ERROR", System.currentTimeMillis() - start, 0,
                    newsSkipDimensions(t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    /** 公告题：直调 AnnouncementExtractor（sourceTypes 传空——eval 只评 LLM 精判面，栏目直判并集路径由单测守护）。 */
    private QuestionResult runAnnouncementOne(AnnouncementExtractor extractor, IntelligenceChatPort chatPort,
                                              ExtractionQuestion q) {
        long start = System.currentTimeMillis();
        try {
            AnnouncementExtractor.ExtractionOutcome outcome =
                    extractor.extractOne(chatPort, q.title(), q.pdfText(), List.of());
            long duration = System.currentTimeMillis() - start;
            if (outcome.status() != AnnouncementExtractor.OutcomeStatus.SUCCESS) {
                return failed(q, outcome.status().name(), duration, outcome.inputTokens(),
                        announcementSkipDimensions("抽取未成功（" + outcome.status() + "），字段比对跳过"));
            }
            List<DimensionResult> dimensions = evaluateAnnouncement(q, outcome.fields());
            boolean pass = dimensions.stream().noneMatch(d -> d.status() == Status.FAIL);
            return new QuestionResult(q, outcome.status().name(), pass, duration, outcome.inputTokens(),
                    null, outcome.fields(), null, dimensions);
        } catch (Throwable t) { // 理论不可达（端口吞异常不抛）；防单题框架异常中断整批
            return failed(q, "ERROR", System.currentTimeMillis() - start, 0,
                    announcementSkipDimensions(t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    /** 政策题：直调 PolicyExtractor（title + 政策正文段，输入截断在提示词层）。 */
    private QuestionResult runPolicyOne(PolicyExtractor extractor, IntelligenceChatPort chatPort,
                                        ExtractionQuestion q) {
        long start = System.currentTimeMillis();
        try {
            PolicyExtractor.ExtractionOutcome outcome =
                    extractor.extractOne(chatPort, q.title(), q.contentText());
            long duration = System.currentTimeMillis() - start;
            if (outcome.status() != PolicyExtractor.OutcomeStatus.SUCCESS) {
                return failed(q, outcome.status().name(), duration, outcome.inputTokens(),
                        policySkipDimensions("抽取未成功（" + outcome.status() + "），字段比对跳过"));
            }
            List<DimensionResult> dimensions = evaluatePolicy(q, outcome.fields());
            boolean pass = dimensions.stream().noneMatch(d -> d.status() == Status.FAIL);
            return new QuestionResult(q, outcome.status().name(), pass, duration, outcome.inputTokens(),
                    null, null, outcome.fields(), dimensions);
        } catch (Throwable t) { // 理论不可达（端口吞异常不抛）；防单题框架异常中断整批
            return failed(q, "ERROR", System.currentTimeMillis() - start, 0,
                    policySkipDimensions(t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    private static QuestionResult failed(ExtractionQuestion q, String outcome, long duration, long tokens,
                                         List<DimensionResult> dimensions) {
        return new QuestionResult(q, outcome, false, duration, tokens, null, null, null, dimensions);
    }

    private static List<DimensionResult> newsSkipDimensions(String reason) {
        return List.of(
                new DimensionResult("stock", Status.SKIP, reason),
                new DimensionResult("direction", Status.SKIP, reason),
                new DimensionResult("importance", Status.SKIP, reason),
                new DimensionResult("event_type", Status.SKIP, reason));
    }

    private static List<DimensionResult> announcementSkipDimensions(String reason) {
        return List.of(
                new DimensionResult("metrics", Status.SKIP, reason),
                new DimensionResult("dividendDesc", Status.SKIP, reason),
                new DimensionResult("undisclosed", Status.SKIP, reason),
                new DimensionResult("annTypes", Status.SKIP, reason));
    }

    private static List<DimensionResult> policySkipDimensions(String reason) {
        return List.of(
                new DimensionResult("direction", Status.SKIP, reason),
                new DimensionResult("strength", Status.SKIP, reason),
                new DimensionResult("confidence", Status.SKIP, reason),
                new DimensionResult("affectedAreas", Status.SKIP, reason),
                new DimensionResult("isPolicy", Status.SKIP, reason),
                new DimensionResult("summary", Status.SKIP, reason));
    }

    /** 新闻四维度比对（口径见类 javadoc 与题库文件头）。 */
    private static List<DimensionResult> evaluateNews(ExtractionQuestion q, NewsExtractor.ExtractedFields f) {
        List<DimensionResult> dims = new ArrayList<>();
        // stock_codes：全集相等（期望空数组=断言无标的）
        Set<String> expectedStocks = new LinkedHashSet<>();
        if (q.expect().stockCodes() != null) {
            q.expect().stockCodes().forEach(c -> expectedStocks.add(c.trim().toUpperCase(Locale.ROOT)));
        }
        Set<String> actualStocks = new LinkedHashSet<>();
        f.stockCodes().forEach(c -> actualStocks.add(c.trim().toUpperCase(Locale.ROOT)));
        if (expectedStocks.equals(actualStocks)) {
            dims.add(new DimensionResult("stock", Status.PASS,
                    expectedStocks.isEmpty() ? "双方均无标的" : "全集一致: " + expectedStocks));
        } else {
            Set<String> missing = new LinkedHashSet<>(expectedStocks);
            missing.removeAll(actualStocks);
            Set<String> extra = new LinkedHashSet<>(actualStocks);
            extra.removeAll(expectedStocks);
            dims.add(new DimensionResult("stock", Status.FAIL,
                    "期望 " + expectedStocks + " 实得 " + actualStocks + "（漏 " + missing + " 多 " + extra + "）"));
        }
        // direction：期望 null=容忍不确定，不断言
        if (q.expect().direction() == null) {
            dims.add(new DimensionResult("direction", Status.SKIP,
                    "expect 未断言（容忍不确定），实得 " + f.direction()));
        } else if (q.expect().direction() == f.direction()) {
            dims.add(new DimensionResult("direction", Status.PASS, "一致: " + f.direction()));
        } else {
            dims.add(new DimensionResult("direction", Status.FAIL,
                    "期望 " + q.expect().direction() + " 实得 " + f.direction()));
        }
        dims.add(evaluateGrade(q.expect().importance(), f.importance()));
        // event_type：期望空=不断言；否则大小写不敏感精确一致
        if (q.expect().eventType() == null || q.expect().eventType().isBlank()) {
            dims.add(new DimensionResult("event_type", Status.SKIP,
                    "expect 未断言，实得 " + f.eventType()));
        } else {
            String expected = q.expect().eventType().trim().toUpperCase(Locale.ROOT);
            String actual = f.eventType() == null ? null : f.eventType().trim().toUpperCase(Locale.ROOT);
            if (expected.equals(actual)) {
                dims.add(new DimensionResult("event_type", Status.PASS, "一致: " + actual));
            } else {
                dims.add(new DimensionResult("event_type", Status.FAIL,
                        "期望 " + expected + " 实得 " + actual));
            }
        }
        return dims;
    }

    /**
     * 公告四维度比对（口径见类 javadoc 与 announcement-001.yaml 文件头）：metrics 五数值字段
     * BigDecimal 等值 / dividendDesc 披露识别 / undisclosed 命中+规范名联动 null / annTypes 子集命中。
     */
    private static List<DimensionResult> evaluateAnnouncement(ExtractionQuestion q,
                                                              AnnouncementExtractor.ExtractedFields f) {
        List<DimensionResult> dims = new ArrayList<>();
        Expectation e = q.expect();
        AnnouncementMetrics m = f.metrics();
        // metrics：期望数值字段 BigDecimal compareTo 等值（期望 map 缺失/空=不断言该维度）
        if (e.metrics() == null || e.metrics().isEmpty()) {
            dims.add(new DimensionResult("metrics", Status.SKIP, "expect 未断言数值字段"));
        } else {
            List<String> misses = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : e.metrics().entrySet()) {
                BigDecimal actual = metricOf(m, entry.getKey());
                if (actual == null || entry.getValue() == null
                        || actual.compareTo(entry.getValue()) != 0) {
                    misses.add(entry.getKey() + " 期望 " + entry.getValue() + " 实得 " + actual);
                }
            }
            dims.add(misses.isEmpty()
                    ? new DimensionResult("metrics", Status.PASS,
                            e.metrics().size() + " 个数值字段全部等值: " + e.metrics().keySet())
                    : new DimensionResult("metrics", Status.FAIL, String.join("；", misses)));
        }
        // dividendDesc：期望非 null=断言实际非 null（披露识别命中，措辞不强一致留 detail 供抽阅）
        if (e.dividendDesc() == null) {
            dims.add(new DimensionResult("dividendDesc", Status.SKIP,
                    "expect 未断言（null 断言走 undisclosed「分红」联动），实得 "
                            + (m == null ? null : m.dividendDesc())));
        } else if (m != null && m.dividendDesc() != null) {
            dims.add(new DimensionResult("dividendDesc", Status.PASS,
                    "已披露识别命中，实得「" + m.dividendDesc() + "」"));
        } else {
            dims.add(new DimensionResult("dividendDesc", Status.FAIL,
                    "期望已披露（" + e.dividendDesc() + "）实得 null"));
        }
        // undisclosed：期望每项命中实际列表；规范名联动断言对应字段为 null（未披露契约）
        if (e.undisclosed() == null || e.undisclosed().isEmpty()) {
            dims.add(new DimensionResult("undisclosed", Status.SKIP,
                    "expect 未断言，实得 " + (m == null ? null : m.undisclosed())));
        } else {
            Set<String> actual = new LinkedHashSet<>(m == null ? List.of() : m.undisclosed());
            List<String> misses = new ArrayList<>();
            for (String name : e.undisclosed()) {
                if (!actual.contains(name)) {
                    misses.add("「" + name + "」未在实际 undisclosed 中（实得 " + actual + "）");
                } else if (m != null && undisclosedLinkedField(m, name) != null) {
                    misses.add("「" + name + "」进 undisclosed 但对应字段非 null（违反未披露契约）");
                }
            }
            dims.add(misses.isEmpty()
                    ? new DimensionResult("undisclosed", Status.PASS,
                            "期望 " + e.undisclosed() + " 全部命中且联动字段为 null")
                    : new DimensionResult("undisclosed", Status.FAIL, String.join("；", misses)));
        }
        // annTypes：期望集 ⊆ 实际集（栏目直判 ∪ LLM 精判并集语义，允许多）
        if (e.annTypes() == null || e.annTypes().isEmpty()) {
            dims.add(new DimensionResult("annTypes", Status.SKIP, "expect 未断言，实得 " + f.annTypes()));
        } else {
            Set<AnnouncementType> expected = enums(e.annTypes());
            Set<AnnouncementType> actualTypes = new LinkedHashSet<>(f.annTypes());
            if (actualTypes.containsAll(expected)) {
                dims.add(new DimensionResult("annTypes", Status.PASS,
                        "期望 " + expected + " ⊆ 实得 " + actualTypes));
            } else {
                Set<AnnouncementType> missing = new LinkedHashSet<>(expected);
                missing.removeAll(actualTypes);
                dims.add(new DimensionResult("annTypes", Status.FAIL,
                        "期望 " + expected + " 实得 " + actualTypes + "（漏 " + missing + "）"));
            }
        }
        return dims;
    }

    /**
     * 政策六维度比对（口径见类 javadoc 与 policy-001.yaml 文件头）：三受控枚举一致 /
     * affectedAreas 部分命中（期望 ⊆ 实际）/ isPolicy 一致（false=哨兵转换链）/ summary 非空。
     */
    private static List<DimensionResult> evaluatePolicy(ExtractionQuestion q,
                                                        PolicyExtractor.ExtractedFields f) {
        List<DimensionResult> dims = new ArrayList<>();
        Expectation e = q.expect();
        dims.add(enumDim("direction", e.policyDirection(), f.direction()));
        dims.add(enumDim("strength", e.strength(), f.strength()));
        dims.add(enumDim("confidence", e.confidence(), f.confidence()));
        // affectedAreas：期望 ⊆ 实际（部分命中，允许多；期望缺失 = 不断言）
        if (e.affectedAreas() == null) {
            dims.add(new DimensionResult("affectedAreas", Status.SKIP,
                    "expect 未断言，实得 " + f.affectedAreas()));
        } else {
            Set<String> actual = new LinkedHashSet<>(f.affectedAreas());
            Set<String> missing = new LinkedHashSet<>(e.affectedAreas());
            missing.removeAll(actual);
            dims.add(missing.isEmpty()
                    ? new DimensionResult("affectedAreas", Status.PASS,
                            "期望 " + e.affectedAreas() + " ⊆ 实得 " + actual)
                    : new DimensionResult("affectedAreas", Status.FAIL,
                            "期望 " + e.affectedAreas() + " 实得 " + actual + "（漏 " + missing + "）"));
        }
        // isPolicy：与抽取 isPolicy 一致（false = 非政策兜底行，哨兵字段由其余维度守护）
        if (e.isPolicy() == null) {
            dims.add(new DimensionResult("isPolicy", Status.SKIP,
                    "expect 未断言，实得 " + f.isPolicy()));
        } else if (e.isPolicy() == f.isPolicy()) {
            dims.add(new DimensionResult("isPolicy", Status.PASS, "一致: " + f.isPolicy()));
        } else {
            dims.add(new DimensionResult("isPolicy", Status.FAIL,
                    "期望 " + e.isPolicy() + " 实得 " + f.isPolicy()));
        }
        // summary：SUCCESS 的解析契约锚点，恒断言非空
        dims.add(f.summary() != null && !f.summary().isBlank()
                ? new DimensionResult("summary", Status.PASS,
                        "非空（" + f.summary().length() + " 字）：「" + f.summary() + "」")
                : new DimensionResult("summary", Status.FAIL, "SUCCESS 但 summary 空白（违反解析契约）"));
        return dims;
    }

    /** 政策枚举维度比对：期望 null=容忍不确定（SKIP）；否则须一致。 */
    private static DimensionResult enumDim(String name, Enum<?> expected, Enum<?> actual) {
        if (expected == null) {
            return new DimensionResult(name, Status.SKIP, "expect 未断言，实得 " + actual);
        }
        if (expected == actual) {
            return new DimensionResult(name, Status.PASS, "一致: " + actual);
        }
        return new DimensionResult(name, Status.FAIL, "期望 " + expected + " 实得 " + actual);
    }

    /** metrics 数值字段按 camelCase 键取值（装载校验保证键在白名单内）。 */
    private static BigDecimal metricOf(AnnouncementMetrics m, String key) {
        if (m == null) {
            return null;
        }
        return switch (key) {
            case "revenueYi" -> m.revenueYi();
            case "netProfitYi" -> m.netProfitYi();
            case "netProfitYoyPct" -> m.netProfitYoyPct();
            case "deductedProfitYi" -> m.deductedProfitYi();
            case "grossMarginPct" -> m.grossMarginPct();
            default -> throw new IllegalStateException("未知 metrics 键: " + key);
        };
    }

    /** undisclosed 规范名对应字段现值（联动 null 断言用；装载校验保证名在六规范名内）。 */
    private static Object undisclosedLinkedField(AnnouncementMetrics m, String name) {
        return switch (name) {
            case "营业收入" -> m.revenueYi();
            case "归母净利润" -> m.netProfitYi();
            case "归母净利润同比" -> m.netProfitYoyPct();
            case "扣非净利润" -> m.deductedProfitYi();
            case "毛利率" -> m.grossMarginPct();
            case "分红" -> m.dividendDesc();
            default -> null; // 不可达：装载校验已限定六个规范名
        };
    }

    /** 枚举名列表 → 枚举集（装载校验保证全部合法）。 */
    private static Set<AnnouncementType> enums(List<String> names) {
        Set<AnnouncementType> out = new LinkedHashSet<>();
        for (String name : names) {
            out.add(AnnouncementType.valueOf(name.trim()));
        }
        return out;
    }

    /**
     * 档位比对：判档用 {@link ImportanceGrade#grade(int, int, int)}（80/50）。
     * 跨 1 档且分数落在边界阈值 ±{@value GRADE_TOLERANCE} 内计 TOLERATED（不算错）；
     * 跨 2 档（MAJOR↔IGNORE）无容差，恒 FAIL。枚举序 MAJOR(0)＜WATCH(1)＜IGNORE(2) 对应重要度递减。
     */
    private static DimensionResult evaluateGrade(ImportanceGrade expected, int score) {
        ImportanceGrade actual = ImportanceGrade.grade(score, MAJOR_AT, WATCH_AT);
        if (actual == expected) {
            return new DimensionResult("importance", Status.PASS,
                    "score " + score + " → " + actual + "（与期望一致）");
        }
        int deviation = Math.abs(expected.ordinal() - actual.ordinal());
        if (deviation == 1) {
            int boundary = Math.min(expected.ordinal(), actual.ordinal()) == 0 ? MAJOR_AT : WATCH_AT;
            if (Math.abs(score - boundary) <= GRADE_TOLERANCE) {
                return new DimensionResult("importance", Status.TOLERATED,
                        "score " + score + " → " + actual + "，期望 " + expected
                                + "（阈值 " + boundary + " ±" + GRADE_TOLERANCE + " 内，按容差计一致）");
            }
        }
        return new DimensionResult("importance", Status.FAIL,
                "score " + score + " → " + actual + "，期望 " + expected + "（偏差 " + deviation + " 档）");
    }

    // ———— 题库装载（schema 校验 fail-fast，同 QuestionLoader 口径） ————

    static List<ExtractionQuestion> loadQuestions() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:extraction/*.yaml");
            List<ExtractionQuestion> all = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Resource resource : resources) {
                List<ExtractionQuestion> inFile;
                try (InputStream in = resource.getInputStream()) {
                    inFile = YAML.readValue(in, YAML.getTypeFactory()
                            .constructCollectionType(List.class, ExtractionQuestion.class));
                }
                for (ExtractionQuestion q : inFile) {
                    validate(q, resource.getFilename());
                    if (!ids.add(q.id())) {
                        throw new IllegalStateException(
                                "题库 id 重复: " + q.id() + "（文件 " + resource.getFilename() + "）");
                    }
                    all.add(q);
                }
            }
            if (all.isEmpty()) {
                throw new IllegalStateException("题库为空：classpath:extraction/*.yaml 未装载到任何题目");
            }
            return List.copyOf(all);
        } catch (IOException e) {
            throw new UncheckedIOException("题库装载失败", e);
        }
    }

    private static void validate(ExtractionQuestion q, String file) {
        require(q.id() != null && !q.id().isBlank(), file, "id 缺失");
        require(q.kind() == null || KIND_NEWS.equals(q.kind()) || KIND_ANNOUNCEMENT.equals(q.kind())
                || KIND_POLICY.equals(q.kind()), file,
                q.id() + ": kind 非法（" + q.kind() + "，合法取值 news/announcement/policy，缺省 news）");
        require(q.title() != null && !q.title().isBlank(), file, q.id() + ": title 缺失");
        require(q.expect() != null, file, q.id() + ": expect 缺失");
        if (isAnnouncement(q)) {
            require(q.pdfText() != null && !q.pdfText().isBlank(), file,
                    q.id() + ": pdf_text 缺失（公告题主输入）");
            if (q.expect().metrics() != null) {
                for (String key : q.expect().metrics().keySet()) {
                    require(METRIC_NUMERIC_KEYS.contains(key), file, q.id()
                            + ": expect.metrics 键非法（" + key + "，合法取值 " + METRIC_NUMERIC_KEYS + "）");
                }
            }
            if (q.expect().undisclosed() != null) {
                for (String name : q.expect().undisclosed()) {
                    require(UNDISCLOSED_FIELDS.containsKey(name), file, q.id()
                            + ": expect.undisclosed 非规范名（" + name + "，合法取值 " + UNDISCLOSED_FIELDS.keySet()
                            + "）");
                }
            }
            if (q.expect().annTypes() != null) {
                for (String name : q.expect().annTypes()) {
                    try {
                        AnnouncementType.valueOf(name.trim());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException("题库 " + file + ": " + q.id()
                                + ": expect.ann_types 非受控枚举名（" + name + "）", e);
                    }
                }
            }
        } else if (isPolicy(q)) {
            require(q.contentText() != null && !q.contentText().isBlank(), file,
                    q.id() + ": content_text 缺失（政策题主输入）");
            require(q.expect().affectedAreas() == null || q.expect().affectedAreas().stream()
                            .noneMatch(a -> a == null || a.isBlank()),
                    file, q.id() + ": expect.affected_areas 须为非空字符串数组");
        } else {
            require(q.summary() != null && !q.summary().isBlank(), file, q.id() + ": summary 缺失");
            require(q.expect().importance() != null, file,
                    q.id() + ": expect.importance 缺失（MAJOR/WATCH/IGNORE）");
            require(q.expect().stockCodes() == null || q.expect().stockCodes().stream()
                            .noneMatch(c -> c == null || c.isBlank()),
                    file, q.id() + ": expect.stock_codes 须为非空字符串数组");
        }
    }

    private static boolean isAnnouncement(ExtractionQuestion q) {
        return KIND_ANNOUNCEMENT.equals(q.kind());
    }

    private static boolean isPolicy(ExtractionQuestion q) {
        return KIND_POLICY.equals(q.kind());
    }

    private static String kindOf(ExtractionQuestion q) {
        if (isAnnouncement(q)) {
            return KIND_ANNOUNCEMENT;
        }
        return isPolicy(q) ? KIND_POLICY : KIND_NEWS;
    }

    /** --list 的 dir 列：news 取 direction、policy 取 policy_direction（均为 null 显示 -）。 */
    private static Object expectDirectionLabel(ExtractionQuestion q) {
        if (isPolicy(q)) {
            return q.expect().policyDirection() == null ? "-" : q.expect().policyDirection();
        }
        return q.expect().direction() == null ? "-" : q.expect().direction();
    }

    /** --list 的末列：news=stock_codes、announcement=ann_types、policy=affected_areas。 */
    private static String expectTailLabel(ExtractionQuestion q) {
        if (isAnnouncement(q)) {
            return String.valueOf(q.expect().annTypes());
        }
        if (isPolicy(q)) {
            return String.valueOf(q.expect().affectedAreas() == null ? List.of() : q.expect().affectedAreas())
                    + (q.expect().isPolicy() == null ? "" : " isPolicy=" + q.expect().isPolicy());
        }
        return String.valueOf(q.expect().stockCodes() == null ? List.of() : q.expect().stockCodes());
    }

    private static void require(boolean condition, String file, String message) {
        if (!condition) {
            throw new IllegalStateException("题库 " + file + ": " + message);
        }
    }

    // ———— 汇总 ————

    private static Summary summarize(List<QuestionResult> results) {
        int newsTotal = 0;
        int announcementTotal = 0;
        int policyTotal = 0;
        int success = 0;
        int newsSuccess = 0;
        int stockPass = 0;
        int directionPass = 0;
        int directionAsserted = 0;
        int gradeExact = 0;
        int gradeTolerated = 0;
        int annMetricsPass = 0;
        int annMetricsAsserted = 0;
        int annUndisclosedPass = 0;
        int annUndisclosedAsserted = 0;
        int annTypesPass = 0;
        int annTypesAsserted = 0;
        int policyDirectionPass = 0;
        int policyDirectionAsserted = 0;
        int policyStrengthPass = 0;
        int policyStrengthAsserted = 0;
        int policyConfidencePass = 0;
        int policyConfidenceAsserted = 0;
        int policyAreasPass = 0;
        int policyAreasAsserted = 0;
        int policyIsPolicyPass = 0;
        int policyIsPolicyAsserted = 0;
        int overallPass = 0;
        for (QuestionResult r : results) {
            boolean announcement = isAnnouncement(r.question());
            boolean policy = isPolicy(r.question());
            if (announcement) {
                announcementTotal++;
            } else if (policy) {
                policyTotal++;
            } else {
                newsTotal++;
            }
            if ("SUCCESS".equals(r.outcome())) {
                success++;
                if (announcement) {
                    Expectation e = r.question().expect();
                    if (e.metrics() != null && !e.metrics().isEmpty()) {
                        annMetricsAsserted++;
                        if (dimension(r, "metrics").status() == Status.PASS) {
                            annMetricsPass++;
                        }
                    }
                    if (e.undisclosed() != null && !e.undisclosed().isEmpty()) {
                        annUndisclosedAsserted++;
                        if (dimension(r, "undisclosed").status() == Status.PASS) {
                            annUndisclosedPass++;
                        }
                    }
                    if (e.annTypes() != null && !e.annTypes().isEmpty()) {
                        annTypesAsserted++;
                        if (dimension(r, "annTypes").status() == Status.PASS) {
                            annTypesPass++;
                        }
                    }
                } else if (policy) {
                    Expectation e = r.question().expect();
                    if (e.policyDirection() != null) {
                        policyDirectionAsserted++;
                        if (dimension(r, "direction").status() == Status.PASS) {
                            policyDirectionPass++;
                        }
                    }
                    if (e.strength() != null) {
                        policyStrengthAsserted++;
                        if (dimension(r, "strength").status() == Status.PASS) {
                            policyStrengthPass++;
                        }
                    }
                    if (e.confidence() != null) {
                        policyConfidenceAsserted++;
                        if (dimension(r, "confidence").status() == Status.PASS) {
                            policyConfidencePass++;
                        }
                    }
                    if (e.affectedAreas() != null) {
                        policyAreasAsserted++;
                        if (dimension(r, "affectedAreas").status() == Status.PASS) {
                            policyAreasPass++;
                        }
                    }
                    if (e.isPolicy() != null) {
                        policyIsPolicyAsserted++;
                        if (dimension(r, "isPolicy").status() == Status.PASS) {
                            policyIsPolicyPass++;
                        }
                    }
                } else {
                    newsSuccess++;
                    if (dimension(r, "stock").status() == Status.PASS) {
                        stockPass++;
                    }
                    if (r.question().expect().direction() != null) {
                        directionAsserted++;
                        if (dimension(r, "direction").status() == Status.PASS) {
                            directionPass++;
                        }
                    }
                    if (dimension(r, "importance").status() == Status.PASS) {
                        gradeExact++;
                    }
                    if (dimension(r, "importance").status() == Status.TOLERATED) {
                        gradeTolerated++;
                    }
                }
            }
            if (r.pass()) {
                overallPass++;
            }
        }
        return new Summary(results.size(), newsTotal, announcementTotal, policyTotal, success,
                (int) results.stream().filter(r -> "PARSE_FAILED".equals(r.outcome())).count(),
                (int) results.stream().filter(r -> "LLM_UNAVAILABLE".equals(r.outcome())).count(),
                (int) results.stream().filter(r -> "ERROR".equals(r.outcome())).count(),
                stockPass, newsSuccess, directionPass, directionAsserted,
                gradeExact, gradeTolerated, newsSuccess,
                annMetricsPass, annMetricsAsserted,
                annUndisclosedPass, annUndisclosedAsserted,
                annTypesPass, annTypesAsserted,
                policyDirectionPass, policyDirectionAsserted,
                policyStrengthPass, policyStrengthAsserted,
                policyConfidencePass, policyConfidenceAsserted,
                policyAreasPass, policyAreasAsserted,
                policyIsPolicyPass, policyIsPolicyAsserted,
                overallPass);
    }

    private static DimensionResult dimension(QuestionResult r, String name) {
        return r.dimensions().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    // ———— 报告（JSON 机读 + Markdown 人读，schema=eval-extraction-report/3——公告维度为 /2 新增、政策维度为 /3 新增） ————

    private static final class Report {

        private final Path outputDir;

        Report(Path outputDir) {
            this.outputDir = outputDir;
        }

        void writeRun(List<QuestionResult> results, String model, String baseUrl) throws IOException {
            ObjectNode root = JSON.createObjectNode();
            root.put("schema", "eval-extraction-report/3");
            root.put("generatedAt", ZonedDateTime.now().toString());
            root.put("status", "RUN");
            ObjectNode subject = root.putObject("subjectModel");
            subject.put("provider", "deepseek");
            subject.put("model", model);
            subject.put("baseUrl", baseUrl);
            root.set("summary", summaryNode(summarize(results)));
            ArrayNode questions = root.putArray("questions");
            for (QuestionResult r : results) {
                questions.add(questionNode(r));
            }
            write(root, buildRunMarkdown(results, model, baseUrl));
        }

        void writeSkip(String reason) throws IOException {
            ObjectNode root = JSON.createObjectNode();
            root.put("schema", "eval-extraction-report/3");
            root.put("generatedAt", ZonedDateTime.now().toString());
            root.put("status", "SKIP");
            root.put("skipReason", reason);
            write(root, "# 抽取质量 eval 报告（eval-extraction）\n\n**SKIP**："
                    + reason + "\n\n运行方式见 rubric/extraction.md。\n");
        }

        void writeError(Throwable t) throws IOException {
            ObjectNode root = JSON.createObjectNode();
            root.put("schema", "eval-extraction-report/3");
            root.put("generatedAt", ZonedDateTime.now().toString());
            root.put("status", "ERROR");
            root.put("error", t.getClass().getName() + ": " + t.getMessage());
            write(root, "# 抽取质量 eval 报告（eval-extraction）\n\n**ERROR**："
                    + t.getClass().getName() + ": " + t.getMessage()
                    + "\n\n（完整堆栈见控制台输出；退出码恒 0——D21 报告即产出）\n");
        }

        private void write(ObjectNode root, String markdown) throws IOException {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("eval-report.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("eval-report.md"), markdown, StandardCharsets.UTF_8);
        }

        private ObjectNode summaryNode(Summary s) {
            ObjectNode node = JSON.createObjectNode();
            node.put("total", s.total());
            node.put("newsTotal", s.newsTotal());
            node.put("announcementTotal", s.announcementTotal());
            node.put("policyTotal", s.policyTotal());
            node.put("parseSuccess", s.success());
            node.put("parseFailed", s.parseFailed());
            node.put("llmUnavailable", s.llmUnavailable());
            node.put("error", s.error());
            node.put("overallPass", s.overallPass());
            ObjectNode stock = node.putObject("stock");
            stock.put("pass", s.stockPass());
            stock.put("asserted", s.stockAsserted());
            ObjectNode direction = node.putObject("direction");
            direction.put("pass", s.directionPass());
            direction.put("asserted", s.directionAsserted());
            ObjectNode grade = node.putObject("importance");
            grade.put("exact", s.gradeExact());
            grade.put("tolerated", s.gradeTolerated());
            grade.put("asserted", s.gradeAsserted());
            grade.put("exactPlusTolerance", s.gradeExact() + s.gradeTolerated());
            ObjectNode announcement = node.putObject("announcement");
            ObjectNode annMetrics = announcement.putObject("metrics");
            annMetrics.put("pass", s.annMetricsPass());
            annMetrics.put("asserted", s.annMetricsAsserted());
            ObjectNode annUndisclosed = announcement.putObject("undisclosed");
            annUndisclosed.put("pass", s.annUndisclosedPass());
            annUndisclosed.put("asserted", s.annUndisclosedAsserted());
            ObjectNode annTypes = announcement.putObject("annTypes");
            annTypes.put("pass", s.annTypesPass());
            annTypes.put("asserted", s.annTypesAsserted());
            ObjectNode policy = node.putObject("policy");
            ObjectNode policyDirection = policy.putObject("direction");
            policyDirection.put("pass", s.policyDirectionPass());
            policyDirection.put("asserted", s.policyDirectionAsserted());
            ObjectNode policyStrength = policy.putObject("strength");
            policyStrength.put("pass", s.policyStrengthPass());
            policyStrength.put("asserted", s.policyStrengthAsserted());
            ObjectNode policyConfidence = policy.putObject("confidence");
            policyConfidence.put("pass", s.policyConfidencePass());
            policyConfidence.put("asserted", s.policyConfidenceAsserted());
            ObjectNode policyAreas = policy.putObject("affectedAreas");
            policyAreas.put("pass", s.policyAreasPass());
            policyAreas.put("asserted", s.policyAreasAsserted());
            ObjectNode policyIsPolicy = policy.putObject("isPolicy");
            policyIsPolicy.put("pass", s.policyIsPolicyPass());
            policyIsPolicy.put("asserted", s.policyIsPolicyAsserted());
            return node;
        }

        private ObjectNode questionNode(QuestionResult r) {
            ObjectNode node = JSON.createObjectNode();
            node.put("id", r.question().id());
            node.put("kind", kindOf(r.question()));
            node.put("outcome", r.outcome());
            node.put("pass", r.pass());
            node.put("durationMs", r.durationMs());
            node.put("inputTokens", r.inputTokens());
            ObjectNode expect = node.putObject("expect");
            expect.set("stock_codes", stringArray(r.question().expect().stockCodes()));
            if (r.question().expect().direction() != null) {
                expect.put("direction", r.question().expect().direction().name());
            } else {
                expect.putNull("direction");
            }
            if (r.question().expect().importance() != null) {
                expect.put("importance", r.question().expect().importance().name());
            } else {
                expect.putNull("importance");
            }
            if (r.question().expect().eventType() != null) {
                expect.put("event_type", r.question().expect().eventType());
            } else {
                expect.putNull("event_type");
            }
            if (isAnnouncement(r.question())) {
                ObjectNode annExpect = expect;
                if (r.question().expect().metrics() == null) {
                    annExpect.putNull("metrics");
                } else {
                    ObjectNode metrics = annExpect.putObject("metrics");
                    r.question().expect().metrics().forEach(metrics::put);
                }
                if (r.question().expect().dividendDesc() != null) {
                    annExpect.put("dividend_desc", r.question().expect().dividendDesc());
                } else {
                    annExpect.putNull("dividend_desc");
                }
                annExpect.set("undisclosed", stringArray(r.question().expect().undisclosed()));
                annExpect.set("ann_types", stringArray(r.question().expect().annTypes()));
            }
            if (isPolicy(r.question())) {
                ObjectNode polExpect = expect;
                if (r.question().expect().policyDirection() != null) {
                    polExpect.put("policy_direction", r.question().expect().policyDirection().name());
                } else {
                    polExpect.putNull("policy_direction");
                }
                if (r.question().expect().strength() != null) {
                    polExpect.put("strength", r.question().expect().strength().name());
                } else {
                    polExpect.putNull("strength");
                }
                if (r.question().expect().confidence() != null) {
                    polExpect.put("confidence", r.question().expect().confidence().name());
                } else {
                    polExpect.putNull("confidence");
                }
                polExpect.set("affected_areas", stringArray(r.question().expect().affectedAreas()));
                if (r.question().expect().isPolicy() != null) {
                    polExpect.put("is_policy", r.question().expect().isPolicy());
                } else {
                    polExpect.putNull("is_policy");
                }
            }
            if (r.newsFields() != null) {
                NewsExtractor.ExtractedFields f = r.newsFields();
                ObjectNode actual = node.putObject("actual");
                if (f.eventType() != null) {
                    actual.put("event_type", f.eventType());
                } else {
                    actual.putNull("event_type");
                }
                actual.set("stock_codes", stringArray(f.stockCodes()));
                actual.set("industry_codes", stringArray(f.industryCodes()));
                if (f.direction() != null) {
                    actual.put("direction", f.direction().name());
                } else {
                    actual.putNull("direction");
                }
                actual.put("summary", f.summary());
                actual.set("key_numbers", stringArray(f.keyNumbers()));
                actual.put("importance", f.importance());
                actual.put("grade",
                        ImportanceGrade.grade(f.importance(), MAJOR_AT, WATCH_AT).name());
            }
            if (r.announcementFields() != null) {
                ObjectNode actual = node.putObject("actual");
                AnnouncementMetrics m = r.announcementFields().metrics();
                if (m == null) {
                    actual.putNull("metrics");
                } else {
                    ObjectNode metrics = actual.putObject("metrics");
                    putDecimal(metrics, "revenueYi", m.revenueYi());
                    putDecimal(metrics, "netProfitYi", m.netProfitYi());
                    putDecimal(metrics, "netProfitYoyPct", m.netProfitYoyPct());
                    putDecimal(metrics, "deductedProfitYi", m.deductedProfitYi());
                    putDecimal(metrics, "grossMarginPct", m.grossMarginPct());
                    if (m.dividendDesc() != null) {
                        metrics.put("dividendDesc", m.dividendDesc());
                    } else {
                        metrics.putNull("dividendDesc");
                    }
                    metrics.set("undisclosed", stringArray(m.undisclosed()));
                }
                ArrayNode annTypes = actual.putArray("annTypes");
                r.announcementFields().annTypes().forEach(t -> annTypes.add(t.name()));
            }
            if (r.policyFields() != null) {
                ObjectNode actual = node.putObject("actual");
                PolicyExtractor.ExtractedFields f = r.policyFields();
                if (f.direction() != null) {
                    actual.put("direction", f.direction().name());
                } else {
                    actual.putNull("direction");
                }
                if (f.strength() != null) {
                    actual.put("strength", f.strength().name());
                } else {
                    actual.putNull("strength");
                }
                actual.set("affectedAreas", stringArray(f.affectedAreas()));
                actual.put("summary", f.summary());
                if (f.confidence() != null) {
                    actual.put("confidence", f.confidence().name());
                } else {
                    actual.putNull("confidence");
                }
                actual.put("isPolicy", f.isPolicy());
            }
            ArrayNode dims = node.putArray("dimensions");
            for (DimensionResult d : r.dimensions()) {
                ObjectNode dim = dims.addObject();
                dim.put("name", d.name());
                dim.put("status", d.status().name());
                dim.put("detail", d.detail());
            }
            return node;
        }

        private String buildRunMarkdown(List<QuestionResult> results, String model, String baseUrl) {
            Summary s = summarize(results);
            StringBuilder md = new StringBuilder();
            md.append("# 抽取质量 eval 报告（eval-extraction）\n\n");
            md.append("- 生成时间：").append(TS.format(ZonedDateTime.now())).append('\n');
            md.append("- 被评通道：deepseek/").append(model).append(" @ ").append(baseUrl)
                    .append("（生产同款 AgentScopeIntelligenceChatPort，抽取 temperature 0）\n");
            md.append("- 题库：extraction/*.yaml（").append(s.total()).append(" 题——news ")
                    .append(s.newsTotal()).append(" / announcement ").append(s.announcementTotal())
                    .append(" / policy ").append(s.policyTotal()).append("）\n\n");
            md.append("## 汇总\n\n| 指标 | 结果 |\n|---|---|\n");
            md.append("| parse 成功率 | ").append(rate(s.success(), s.total())).append(" |\n");
            md.append("| stock 命中率（新闻） | ").append(rate(s.stockPass(), s.stockAsserted())).append(" |\n");
            md.append("| direction 一致率（新闻） | ").append(rate(s.directionPass(), s.directionAsserted()))
                    .append(" |\n");
            md.append("| importance 档位一致率（新闻） | 严格 ").append(rate(s.gradeExact(), s.gradeAsserted()))
                    .append("｜含阈值±").append(GRADE_TOLERANCE).append("容差 ")
                    .append(rate(s.gradeExact() + s.gradeTolerated(), s.gradeAsserted())).append(" |\n");
            md.append("| metrics 一致率（公告） | ")
                    .append(rate(s.annMetricsPass(), s.annMetricsAsserted())).append(" |\n");
            md.append("| undisclosed 命中率（公告） | ")
                    .append(rate(s.annUndisclosedPass(), s.annUndisclosedAsserted())).append(" |\n");
            md.append("| annTypes 命中率（公告） | ")
                    .append(rate(s.annTypesPass(), s.annTypesAsserted())).append(" |\n");
            md.append("| direction 一致率（政策） | ")
                    .append(rate(s.policyDirectionPass(), s.policyDirectionAsserted())).append(" |\n");
            md.append("| strength 一致率（政策） | ")
                    .append(rate(s.policyStrengthPass(), s.policyStrengthAsserted())).append(" |\n");
            md.append("| confidence 一致率（政策） | ")
                    .append(rate(s.policyConfidencePass(), s.policyConfidenceAsserted())).append(" |\n");
            md.append("| affectedAreas 命中率（政策） | ")
                    .append(rate(s.policyAreasPass(), s.policyAreasAsserted())).append(" |\n");
            md.append("| isPolicy 一致率（政策） | ")
                    .append(rate(s.policyIsPolicyPass(), s.policyIsPolicyAsserted())).append(" |\n");
            md.append("| 总体 PASS | ").append(rate(s.overallPass(), s.total())).append(" |\n");
            if (s.parseFailed() + s.llmUnavailable() + s.error() > 0) {
                md.append("\n> 异常结局：PARSE_FAILED=").append(s.parseFailed())
                        .append("，LLM_UNAVAILABLE=").append(s.llmUnavailable())
                        .append("，ERROR=").append(s.error())
                        .append("——须排查（口径见 rubric/extraction.md）\n");
            }
            md.append("\n## 逐题（新闻）\n\n| id | outcome | 判定 | stock | direction | 档位(实得) | event_type | 耗时 | in tok |\n|---|---|---|---|---|---|---|---|---|\n");
            for (QuestionResult r : results) {
                if (!KIND_NEWS.equals(kindOf(r.question()))) {
                    continue;
                }
                String gradeCell;
                if (r.newsFields() != null) {
                    gradeCell = r.newsFields().importance() + "→"
                            + ImportanceGrade.grade(r.newsFields().importance(), MAJOR_AT, WATCH_AT);
                } else {
                    gradeCell = "—";
                }
                md.append("| ").append(r.question().id())
                        .append(" | ").append(r.outcome())
                        .append(" | ").append(r.pass() ? "PASS" : "FAIL")
                        .append(" | ").append(cell(r, "stock"))
                        .append(" | ").append(cell(r, "direction"))
                        .append(" | ").append(gradeCell)
                        .append(" | ").append(cell(r, "event_type"))
                        .append(" | ").append(String.format(Locale.ROOT, "%.1fs", r.durationMs() / 1000.0))
                        .append(" | ").append(r.inputTokens())
                        .append(" |\n");
            }
            md.append("\n## 逐题（公告）\n\n| id | outcome | 判定 | metrics | dividendDesc | undisclosed | annTypes | 耗时 | in tok |\n|---|---|---|---|---|---|---|---|---|\n");
            for (QuestionResult r : results) {
                if (!isAnnouncement(r.question())) {
                    continue;
                }
                md.append("| ").append(r.question().id())
                        .append(" | ").append(r.outcome())
                        .append(" | ").append(r.pass() ? "PASS" : "FAIL")
                        .append(" | ").append(cell(r, "metrics"))
                        .append(" | ").append(cell(r, "dividendDesc"))
                        .append(" | ").append(cell(r, "undisclosed"))
                        .append(" | ").append(cell(r, "annTypes"))
                        .append(" | ").append(String.format(Locale.ROOT, "%.1fs", r.durationMs() / 1000.0))
                        .append(" | ").append(r.inputTokens())
                        .append(" |\n");
            }
            md.append("\n## 逐题（政策）\n\n| id | outcome | 判定 | direction | strength | confidence | affectedAreas | isPolicy | summary | 耗时 | in tok |\n|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (QuestionResult r : results) {
                if (!isPolicy(r.question())) {
                    continue;
                }
                String summaryCell;
                if (r.policyFields() != null) {
                    summaryCell = r.policyFields().summary() == null ? "—"
                            : "「" + r.policyFields().summary() + "」";
                } else {
                    summaryCell = "—";
                }
                md.append("| ").append(r.question().id())
                        .append(" | ").append(r.outcome())
                        .append(" | ").append(r.pass() ? "PASS" : "FAIL")
                        .append(" | ").append(cell(r, "direction"))
                        .append(" | ").append(cell(r, "strength"))
                        .append(" | ").append(cell(r, "confidence"))
                        .append(" | ").append(cell(r, "affectedAreas"))
                        .append(" | ").append(cell(r, "isPolicy"))
                        .append(" | ").append(summaryCell)
                        .append(" | ").append(String.format(Locale.ROOT, "%.1fs", r.durationMs() / 1000.0))
                        .append(" | ").append(r.inputTokens())
                        .append(" |\n");
            }
            md.append("\n### 命中明细（非 PASS 维度）\n\n");
            boolean any = false;
            for (QuestionResult r : results) {
                for (DimensionResult d : r.dimensions()) {
                    if (d.status() == Status.PASS || d.status() == Status.SKIP) {
                        continue;
                    }
                    md.append("- **").append(r.question().id()).append("**（").append(kindOf(r.question()))
                            .append("）").append(d.name()).append("（")
                            .append(d.status()).append("）：").append(d.detail()).append('\n');
                    any = true;
                }
            }
            if (!any) {
                md.append("（无——全部断言维度 PASS）\n");
            }
            return md.toString();
        }

        /** 维度单元格：PASS / ±容差 / FAIL / —（未断言或无法比对）。 */
        private static String cell(QuestionResult r, String name) {
            return dimension(r, name).status() == Status.PASS ? "PASS"
                    : dimension(r, name).status() == Status.TOLERATED ? "±容差"
                    : dimension(r, name).status() == Status.FAIL ? "FAIL" : "—";
        }

        private static void putDecimal(ObjectNode node, String key, BigDecimal value) {
            if (value != null) {
                node.put(key, value);
            } else {
                node.putNull(key);
            }
        }

        private static String rate(int numerator, int denominator) {
            if (denominator == 0) {
                return numerator + "/" + denominator + "（—）";
            }
            return String.format(Locale.ROOT, "%d/%d（%.0f%%）", numerator, denominator,
                    100.0 * numerator / denominator);
        }

        private static ArrayNode stringArray(List<String> values) {
            ArrayNode array = JSON.createArrayNode();
            if (values != null) {
                values.forEach(array::add);
            }
            return array;
        }
    }
}
