package com.portfolio.invest.agent.report;

import com.portfolio.invest.agent.trust.AnchorBatch;
import com.portfolio.invest.agent.trust.AnchorRecord;
import com.portfolio.invest.agent.trust.ConsistencyValidator;
import com.portfolio.invest.agent.trust.CorrectionResult;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustVerdict;
import com.portfolio.invest.config.InvestProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 研报校验门（MS-32 B7，设计规格 §3.5，F03 核心）：fail-closed——数字一致性不通过不出稿，
 * 与对话档 fail-open 刻意相反。内部持 <strong>strict 档</strong> {@link ConsistencyValidator}
 * （容差取 {@code invest.report.validation} 组、独立实例注入，对话档 {@code invest.trust.*} 零改动；
 * 报告模式构造——数字提取排除「关键假设」编号行序号，Ruling-12：序号既不入未溯源统计
 * 也不触发误替换，{@link com.portfolio.invest.agent.trust.NumberExtractor} 默认路径零变更）。
 * 纯 POJO 零 Spring 注解（沿 ConsistencyValidator 风格，可直 new）。
 *
 * <p><strong>判定链（序即设计规格 §3.5，先命中先拦）：</strong></p>
 * <ol>
 *   <li>真值池空（收集轮全失败）→ {@link FailReason#EMPTY_POOL}；</li>
 *   <li>{@link ConsistencyValidator#correct} 后 correctionFailures &gt; 0（重试耗尽降级）→
 *       {@link FailReason#CORRECTION_FAILURES}，反馈含误述清单；</li>
 *   <li>unverifiedRatio（unverified/(verified+sourced+unverified)，分母 0 视为 1.0——
 *       全文无可锚定数字即全未溯源）&gt; {@code unverified-ratio-max} →
 *       {@link FailReason#UNVERIFIED_RATIO}，反馈含未锚定数字清单；</li>
 *   <li>假设语句空（无「## 关键假设」章节或章节为空）→ {@link FailReason#ASSUMPTIONS_MISSING}；</li>
 *   <li>任一假设零绑定 → {@link FailReason#ASSUMPTION_UNBOUND}，反馈含未绑定条目；</li>
 *   <li>全过 → passed=true + correctedMd（修正后文本，含 ⚠ 注记行）+ anchors + bindings。</li>
 * </ol>
 *
 * <p>可归因偏差（如 15.20 vs 1520.33）由 correct 自动修正（correctionFailures=0 仍过门，
 * correctedMd 留 ⚠ 注记行）；容差外不可归因数字计入 unverified 由比例线拦截。未溯源比例
 * 越线判定走 BigDecimal 整数交叉相乘（{@code unverified·1 > max·total}），勿浮点直比。
 */
public final class ReportValidationGate {

    /** 失败因（回炉反馈分流；序非判定序，判定序见类 javadoc）。 */
    public enum FailReason { CORRECTION_FAILURES, UNVERIFIED_RATIO, ASSUMPTIONS_MISSING, ASSUMPTION_UNBOUND, EMPTY_POOL }

    /** 门结论：passed=true 时 failReason 为 null；correctedMd 恒为修正后文本（无偏差=原文直通）。 */
    public record GateResult(boolean passed, String correctedMd, AnchorBatch anchors,
                             List<AssumptionBinder.Binding> bindings, List<String> feedbackLines, FailReason failReason) {}

    /** 「## 关键假设」标题行（允许标题字内侧空白）。 */
    private static final Pattern SECTION_HEADING = Pattern.compile("^##\\s*关键假设\\s*$");

    /** 章节内编号条目行：组 1=序号、组 2=语句正文（剥编号前缀存储，seq 独立成列）。 */
    private static final Pattern NUMBERED_LINE = Pattern.compile("^(\\d+)\\.\\s*(.*)$");

    private final ConsistencyValidator validator;
    private final BigDecimal relative;
    private final double unverifiedRatioMax;

    /**
     * strict 档构造：容差四值 + 未溯源比例线（correction.maxRetries 沿对话档默认 2，
     * 其余 Trust 配置取默认）。
     */
    public ReportValidationGate(BigDecimal relative, BigDecimal absolute, BigDecimal price,
            BigDecimal deviation, double unverifiedRatioMax) {
        this(new InvestProperties.Trust.ToleranceSettings(relative, absolute, price, deviation),
                2, unverifiedRatioMax);
    }

    private ReportValidationGate(InvestProperties.Trust.ToleranceSettings tolerance, int maxRetries,
            double unverifiedRatioMax) {
        InvestProperties.Trust strict = new InvestProperties.Trust();
        strict.setTolerance(tolerance);
        strict.setCorrection(new InvestProperties.Trust.CorrectionSettings(maxRetries));
        this.validator = new ConsistencyValidator(strict, true);
        this.relative = strict.getTolerance().relative();
        this.unverifiedRatioMax = unverifiedRatioMax;
    }

    /**
     * 静态工厂：以 {@code invest.report.validation} 档容差构造（relative/absolute/price 取
     * report 档、deviation 沿 Trust 默认、correction.maxRetries 沿对话档、其余默认），
     * 独立实例注入——对话档 {@code invest.trust.*} 零改动。
     */
    public static ReportValidationGate forReport(InvestProperties props) {
        InvestProperties.Report.Validation validation = props.getReport().getValidation();
        return new ReportValidationGate(
                new InvestProperties.Trust.ToleranceSettings(
                        validation.relative(), validation.absolute(), validation.price(), null),
                props.getTrust().getCorrection().maxRetries(),
                validation.unverifiedRatioMax());
    }

    /** 过门：contentMd 为 payload 解析出的研报正文；assumptionStatements 通常来自 {@link #extractAssumptionStatements}。 */
    public GateResult evaluate(String contentMd, List<String> assumptionStatements, List<ToolInvocation> pool) {
        List<ToolInvocation> safePool = pool == null ? List.of() : pool;
        List<String> statements = assumptionStatements == null ? List.of() : assumptionStatements;
        CorrectionResult result = validator.correct(contentMd, safePool);
        List<AssumptionBinder.Binding> bindings =
                AssumptionBinder.bind(statements, result.batch().anchors(), relative);

        if (safePool.isEmpty()) {
            return fail(result, bindings,
                    List.of("真值池为空：数据收集阶段无任何成功工具调用，无法校验数字一致性"),
                    FailReason.EMPTY_POOL);
        }
        if (result.correctionFailures() > 0) {
            List<String> feedback = new ArrayList<>();
            feedback.add("存在 " + result.correctionFailures() + " 处数字误述自动修正失败（重试上限已到，保留原文）：");
            for (CorrectionResult.Correction correction : result.corrections()) {
                if (correction.degraded()) {
                    feedback.add("- 原文「" + correction.snippet() + "」应为「" + correction.replacement() + "」");
                }
            }
            return fail(result, bindings, feedback, FailReason.CORRECTION_FAILURES);
        }
        AnchorBatch.Stats stats = result.batch().stats();
        int total = stats.verified() + stats.sourced() + stats.unverified();
        if (total == 0 || overRatioLine(stats.unverified(), total)) {
            List<String> feedback = new ArrayList<>();
            feedback.add(total == 0
                    ? "全文没有任何可锚定的数据数字（未溯源比例视为 100%）"
                    : "未溯源数字 " + stats.unverified() + "/" + total + " 超过上限 " + ratioMaxText() + "：");
            for (AnchorRecord anchor : result.batch().anchors()) {
                if (anchor.state() == TrustVerdict.UNVERIFIED) {
                    feedback.add("- 「" + anchor.snippet() + "」无工具数据支撑");
                }
            }
            return fail(result, bindings, feedback, FailReason.UNVERIFIED_RATIO);
        }
        if (statements.isEmpty()) {
            return fail(result, bindings,
                    List.of("缺少「## 关键假设」章节：每条假设须注明来源工具与数字"),
                    FailReason.ASSUMPTIONS_MISSING);
        }
        List<String> feedback = new ArrayList<>();
        feedback.add("以下关键假设未绑定任何数据锚点（每条须引用工具返回数字）：");
        boolean anyUnbound = false;
        for (AssumptionBinder.Binding binding : bindings) {
            if (binding.anchorIndex() < 0) {
                anyUnbound = true;
                feedback.add("- " + binding.seq() + ". " + binding.statement());
            }
        }
        if (anyUnbound) {
            return fail(result, bindings, feedback, FailReason.ASSUMPTION_UNBOUND);
        }
        return new GateResult(true, result.correctedText(), result.batch(), bindings, List.of(), null);
    }

    /**
     * 「关键假设」章节切分：contentMd 中标题行（## 关键假设）之后至下一 ## 前的
     * {@code ^\d+\.\ } 编号行列表（剥编号前缀）；无该标题/标题节为空 → 空列表（门转
     * ASSUMPTIONS_MISSING）。
     */
    public static List<String> extractAssumptionStatements(String contentMd) {
        if (contentMd == null || contentMd.isEmpty()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        boolean inSection = false;
        for (String rawLine : contentMd.split("\n", -1)) {
            String line = rawLine.trim();
            if (SECTION_HEADING.matcher(line).matches()) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("##")) {
                break;
            }
            if (!inSection) {
                continue;
            }
            Matcher numbered = NUMBERED_LINE.matcher(line);
            if (numbered.matches() && !numbered.group(2).isBlank()) {
                statements.add(numbered.group(2).trim());
            }
        }
        return List.copyOf(statements);
    }

    /** 未溯源比例越线：整数交叉相乘 {@code unverified > max × total}（BigDecimal 精确，勿浮点直比）。 */
    private boolean overRatioLine(int unverified, int total) {
        return BigDecimal.valueOf(unverified)
                .compareTo(BigDecimal.valueOf(unverifiedRatioMax).multiply(BigDecimal.valueOf(total))) > 0;
    }

    private String ratioMaxText() {
        return BigDecimal.valueOf(unverifiedRatioMax).stripTrailingZeros().toPlainString();
    }

    private static GateResult fail(CorrectionResult result, List<AssumptionBinder.Binding> bindings,
            List<String> feedbackLines, FailReason failReason) {
        return new GateResult(false, result.correctedText(), result.batch(), bindings,
                List.copyOf(feedbackLines), failReason);
    }
}
