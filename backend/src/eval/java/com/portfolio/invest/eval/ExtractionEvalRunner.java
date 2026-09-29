package com.portfolio.invest.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.portfolio.invest.application.intelligence.IntelligenceChatPort;
import com.portfolio.invest.application.intelligence.NewsExtractor;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ImportanceGrade;
import com.portfolio.invest.infrastructure.intelligence.AgentScopeIntelligenceChatPort;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
 * 抽取质量 eval 回归入口（D21）：题库（{@code src/eval/resources/extraction/*.yaml}）逐题直调
 * {@link NewsExtractor#extractOne}，LLM 通道走生产同款 {@link AgentScopeIntelligenceChatPort}。
 * Model 在本进程直构（无 Spring 上下文）：与 agent/AgentConfig#investModel 同款
 * {@code ModelRegistry.resolve("deepseek:"+model)}；ObjectProvider 以最小匿名实现提供该 Model，
 * 不改生产类可见性。唯一差异：apiKey 显式入 ModelCreationContext（生产靠 provider 回退
 * System.getenv），使仅存在于 .env 的 key 也可用。
 *
 * <p>比对口径（题库文件头注释与本实现互为对照）：stock_codes 全集相等（空数组=断言无标的）；
 * direction 期望非 null 时须一致（null=容忍不确定，不断言）；importance 按 80/50 阈值判档，
 * 跨档但分数落在阈值 ±10 内按容差不计错；event_type 期望非空时须一致（大小写不敏感）。
 * 报告写 {@code backend/build/reports/eval-extraction/eval-report.{json,md}}（汇总四指标：
 * stock 命中率 / direction 一致率 / importance 档位一致率 / parse 成功率）。
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

    private static final Path REPORT_DIR = Path.of("build", "reports", "eval-extraction");

    // ———— 题库 ————

    /** 期望（snake_case 键与题库 YAML 对齐）。 */
    public record Expectation(
            @JsonProperty("stock_codes") List<String> stockCodes,
            Direction direction,
            ImportanceGrade importance,
            @JsonProperty("event_type") String eventType) {
    }

    public record ExtractionQuestion(String id, String title, String summary, Expectation expect) {
    }

    /** 单题单维度结论：PASS 一致 / TOLERATED 跨档但阈值±10 内 / FAIL 不一致 / SKIP 未断言或无法比对。 */
    private enum Status { PASS, TOLERATED, FAIL, SKIP }

    private record DimensionResult(String name, Status status, String detail) {
    }

    /** outcome 取 NewsExtractor 三态 + ERROR（单题框架异常兜底，理论不可达：端口吞异常）。 */
    private record QuestionResult(ExtractionQuestion question, String outcome, boolean pass,
                                  long durationMs, long inputTokens,
                                  NewsExtractor.ExtractedFields fields, List<DimensionResult> dimensions) {
    }

    private record Summary(int total, int success, int parseFailed, int llmUnavailable, int error,
                           int stockPass, int stockAsserted,
                           int directionPass, int directionAsserted,
                           int gradeExact, int gradeTolerated, int gradeAsserted,
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
            System.out.printf("%-40s %-8s %-7s %-10s %s%n", "id", "dir", "grade", "event", "stocks");
            for (ExtractionQuestion q : questions) {
                System.out.printf("%-40s %-8s %-7s %-10s %s%n", q.id(),
                        q.expect().direction() == null ? "-" : q.expect().direction(),
                        q.expect().importance(),
                        q.expect().eventType() == null ? "-" : q.expect().eventType(),
                        q.expect().stockCodes() == null ? List.of() : q.expect().stockCodes());
            }
            System.out.printf("[eval-extraction] --list 干跑通过：装载 %d 题，schema 校验全部通过（未起 LLM）%n",
                    questions.size());
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
        NewsExtractor extractor = new NewsExtractor();

        System.out.printf("[eval-extraction] 题库装载 %d 题；通道=AgentScopeIntelligenceChatPort（deepseek/%s @ %s）%n",
                questions.size(), model, baseUrl);
        List<QuestionResult> results = new ArrayList<>();
        for (ExtractionQuestion q : questions) {
            QuestionResult result = runOne(extractor, chatPort, q);
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
                "[eval-extraction] 汇总：parse 成功率 %d/%d｜stock 命中率 %d/%d｜direction 一致率 %d/%d｜importance 档位一致率（严格 %d/%d，含容差 %d/%d）｜总体 PASS %d/%d%n",
                s.success(), s.total(), s.stockPass(), s.stockAsserted(),
                s.directionPass(), s.directionAsserted(),
                s.gradeExact(), s.gradeAsserted(), s.gradeExact() + s.gradeTolerated(), s.gradeAsserted(),
                s.overallPass(), s.total());
    }

    // ———— 单题执行与比对 ————

    private QuestionResult runOne(NewsExtractor extractor, IntelligenceChatPort chatPort, ExtractionQuestion q) {
        long start = System.currentTimeMillis();
        try {
            NewsExtractor.ExtractionOutcome outcome = extractor.extractOne(chatPort, q.title(), q.summary());
            long duration = System.currentTimeMillis() - start;
            if (outcome.status() != NewsExtractor.OutcomeStatus.SUCCESS) {
                String reason = "抽取未成功（" + outcome.status() + "），字段比对跳过";
                return new QuestionResult(q, outcome.status().name(), false, duration, outcome.inputTokens(),
                        null, List.of(
                        new DimensionResult("stock", Status.SKIP, reason),
                        new DimensionResult("direction", Status.SKIP, reason),
                        new DimensionResult("importance", Status.SKIP, reason),
                        new DimensionResult("event_type", Status.SKIP, reason)));
            }
            List<DimensionResult> dimensions = evaluate(q, outcome.fields());
            boolean pass = dimensions.stream().noneMatch(d -> d.status() == Status.FAIL);
            return new QuestionResult(q, outcome.status().name(), pass, duration, outcome.inputTokens(),
                    outcome.fields(), dimensions);
        } catch (Throwable t) { // 理论不可达（端口吞异常不抛）；防单题框架异常中断整批
            String detail = t.getClass().getSimpleName() + ": " + t.getMessage();
            return new QuestionResult(q, "ERROR", false, System.currentTimeMillis() - start, 0, null,
                    List.of(new DimensionResult("stock", Status.SKIP, detail),
                            new DimensionResult("direction", Status.SKIP, detail),
                            new DimensionResult("importance", Status.SKIP, detail),
                            new DimensionResult("event_type", Status.SKIP, detail)));
        }
    }

    /** 四维度比对（口径见类 javadoc 与题库文件头）。 */
    private static List<DimensionResult> evaluate(ExtractionQuestion q, NewsExtractor.ExtractedFields f) {
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
        require(q.title() != null && !q.title().isBlank(), file, q.id() + ": title 缺失");
        require(q.summary() != null && !q.summary().isBlank(), file, q.id() + ": summary 缺失");
        require(q.expect() != null, file, q.id() + ": expect 缺失");
        require(q.expect().importance() != null, file,
                q.id() + ": expect.importance 缺失（MAJOR/WATCH/IGNORE）");
        require(q.expect().stockCodes() == null || q.expect().stockCodes().stream()
                        .noneMatch(c -> c == null || c.isBlank()),
                file, q.id() + ": expect.stock_codes 须为非空字符串数组");
    }

    private static void require(boolean condition, String file, String message) {
        if (!condition) {
            throw new IllegalStateException("题库 " + file + ": " + message);
        }
    }

    // ———— 汇总 ————

    private static Summary summarize(List<QuestionResult> results) {
        int success = 0;
        int stockPass = 0;
        int directionPass = 0;
        int directionAsserted = 0;
        int gradeExact = 0;
        int gradeTolerated = 0;
        int overallPass = 0;
        for (QuestionResult r : results) {
            if ("SUCCESS".equals(r.outcome())) {
                success++;
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
            if (r.pass()) {
                overallPass++;
            }
        }
        return new Summary(results.size(), success,
                (int) results.stream().filter(r -> "PARSE_FAILED".equals(r.outcome())).count(),
                (int) results.stream().filter(r -> "LLM_UNAVAILABLE".equals(r.outcome())).count(),
                (int) results.stream().filter(r -> "ERROR".equals(r.outcome())).count(),
                stockPass, success, directionPass, directionAsserted,
                gradeExact, gradeTolerated, success, overallPass);
    }

    private static DimensionResult dimension(QuestionResult r, String name) {
        return r.dimensions().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    // ———— 报告（JSON 机读 + Markdown 人读，schema=eval-extraction-report/1） ————

    private static final class Report {

        private final Path outputDir;

        Report(Path outputDir) {
            this.outputDir = outputDir;
        }

        void writeRun(List<QuestionResult> results, String model, String baseUrl) throws IOException {
            ObjectNode root = JSON.createObjectNode();
            root.put("schema", "eval-extraction-report/1");
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
            root.put("schema", "eval-extraction-report/1");
            root.put("generatedAt", ZonedDateTime.now().toString());
            root.put("status", "SKIP");
            root.put("skipReason", reason);
            write(root, "# 新闻抽取质量 eval 报告（eval-extraction）\n\n**SKIP**："
                    + reason + "\n\n运行方式见 rubric/extraction.md。\n");
        }

        void writeError(Throwable t) throws IOException {
            ObjectNode root = JSON.createObjectNode();
            root.put("schema", "eval-extraction-report/1");
            root.put("generatedAt", ZonedDateTime.now().toString());
            root.put("status", "ERROR");
            root.put("error", t.getClass().getName() + ": " + t.getMessage());
            write(root, "# 新闻抽取质量 eval 报告（eval-extraction）\n\n**ERROR**："
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
            return node;
        }

        private ObjectNode questionNode(QuestionResult r) {
            ObjectNode node = JSON.createObjectNode();
            node.put("id", r.question().id());
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
            expect.put("importance", r.question().expect().importance().name());
            if (r.question().expect().eventType() != null) {
                expect.put("event_type", r.question().expect().eventType());
            } else {
                expect.putNull("event_type");
            }
            if (r.fields() != null) {
                ObjectNode actual = node.putObject("actual");
                if (r.fields().eventType() != null) {
                    actual.put("event_type", r.fields().eventType());
                } else {
                    actual.putNull("event_type");
                }
                actual.set("stock_codes", stringArray(r.fields().stockCodes()));
                actual.set("industry_codes", stringArray(r.fields().industryCodes()));
                if (r.fields().direction() != null) {
                    actual.put("direction", r.fields().direction().name());
                } else {
                    actual.putNull("direction");
                }
                actual.put("summary", r.fields().summary());
                actual.set("key_numbers", stringArray(r.fields().keyNumbers()));
                actual.put("importance", r.fields().importance());
                actual.put("grade",
                        ImportanceGrade.grade(r.fields().importance(), MAJOR_AT, WATCH_AT).name());
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
            md.append("# 新闻抽取质量 eval 报告（eval-extraction）\n\n");
            md.append("- 生成时间：").append(TS.format(ZonedDateTime.now())).append('\n');
            md.append("- 被评通道：deepseek/").append(model).append(" @ ").append(baseUrl)
                    .append("（生产同款 AgentScopeIntelligenceChatPort，抽取 temperature 0）\n");
            md.append("- 题库：extraction/*.yaml（").append(s.total()).append(" 题）\n\n");
            md.append("## 汇总\n\n| 指标 | 结果 |\n|---|---|\n");
            md.append("| parse 成功率 | ").append(rate(s.success(), s.total())).append(" |\n");
            md.append("| stock 命中率 | ").append(rate(s.stockPass(), s.stockAsserted())).append(" |\n");
            md.append("| direction 一致率 | ").append(rate(s.directionPass(), s.directionAsserted()))
                    .append(" |\n");
            md.append("| importance 档位一致率 | 严格 ").append(rate(s.gradeExact(), s.gradeAsserted()))
                    .append("｜含阈值±").append(GRADE_TOLERANCE).append("容差 ")
                    .append(rate(s.gradeExact() + s.gradeTolerated(), s.gradeAsserted())).append(" |\n");
            md.append("| 总体 PASS | ").append(rate(s.overallPass(), s.total())).append(" |\n");
            if (s.parseFailed() + s.llmUnavailable() + s.error() > 0) {
                md.append("\n> 异常结局：PARSE_FAILED=").append(s.parseFailed())
                        .append("，LLM_UNAVAILABLE=").append(s.llmUnavailable())
                        .append("，ERROR=").append(s.error())
                        .append("——须排查（口径见 rubric/extraction.md）\n");
            }
            md.append("\n## 逐题\n\n| id | outcome | 判定 | stock | direction | 档位(实得) | event_type | 耗时 | in tok |\n|---|---|---|---|---|---|---|---|---|\n");
            for (QuestionResult r : results) {
                String gradeCell;
                if (r.fields() != null) {
                    gradeCell = r.fields().importance() + "→"
                            + ImportanceGrade.grade(r.fields().importance(), MAJOR_AT, WATCH_AT);
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
            md.append("\n### 命中明细（非 PASS 维度）\n\n");
            boolean any = false;
            for (QuestionResult r : results) {
                for (DimensionResult d : r.dimensions()) {
                    if (d.status() == Status.PASS || d.status() == Status.SKIP) {
                        continue;
                    }
                    md.append("- **").append(r.question().id()).append("** ").append(d.name()).append("（")
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
            return switch (dimension(r, name).status()) {
                case PASS -> "PASS";
                case TOLERATED -> "±容差";
                case FAIL -> "FAIL";
                case SKIP -> "—";
            };
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
