package com.portfolio.invest.agent.report;

import com.portfolio.invest.agent.trust.AnchorRecord;
import com.portfolio.invest.agent.trust.NumberExtractor;
import com.portfolio.invest.agent.trust.NumberToken;
import com.portfolio.invest.agent.trust.TrustVerdict;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 关键假设 ↔ 锚点自动绑定（MS-32 B7，设计规格 §3.7）：「关键假设」章节条目逐条
 * {@link NumberExtractor} 提取数值，与锚定批次中 <strong>verified/sourced</strong> 态锚点的
 * snippet 数值比对（相等或容差内，复用 judge 语义——百分比维度一致 + report 档 relative 容差），
 * 首个命中即绑（anchorIndex）；条目无数字或全部不中 → -1（校验门 {@link ReportValidationGate}
 * 转 ASSUMPTION_UNBOUND 回炉反馈）。纯静态无状态、零 Spring 注解。
 *
 * <p>比对口径用 {@link BigDecimal} 精确十进制交叉相乘（{@code |Δ| ≤ relative×|锚定值|}），
 * 勿浮点直比；锚定数值取自 {@code anchor.snippet()} 再提取（snippet 为单数字 token 形态，
 * raw 可能含多数字）。unverified 锚无工具真值背书，不参与绑定（fail-closed 方向宁可少绑）。
 * 提取恒走报告模式（排除行首列表序号，Ruling-12）——语句由切分法剥编号前缀后本无序号，
 * 报告模式为与校验门同口径的一致性护栏。
 */
public final class AssumptionBinder {

    private AssumptionBinder() {
    }

    /** 单条假设的绑定结论：seq 为章节内 1-based 序号；anchorIndex 指向锚定批次（-1 未绑定）。 */
    public record Binding(int seq, String statement, int anchorIndex) {}

    /**
     * 逐条绑定：语句数字 token 按出现序扫锚点，容差内首个命中即绑其下标；全不中/无数字 → -1。
     *
     * @param relative 相对容差（report 档 {@code invest.report.validation.relative}）；null 视为 0（仅精确相等可绑）
     */
    public static List<Binding> bind(List<String> assumptionStatements, List<AnchorRecord> anchors,
            BigDecimal relative) {
        List<String> statements = assumptionStatements == null ? List.of() : assumptionStatements;
        List<AnchorRecord> safeAnchors = anchors == null ? List.of() : anchors;
        BigDecimal tolerance = relative == null ? BigDecimal.ZERO : relative;
        List<Binding> bindings = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            bindings.add(new Binding(i + 1, statements.get(i),
                    firstBoundAnchor(statements.get(i), safeAnchors, tolerance)));
        }
        return List.copyOf(bindings);
    }

    private static int firstBoundAnchor(String statement, List<AnchorRecord> anchors, BigDecimal relative) {
        for (NumberToken token : NumberExtractor.extract(statement, true)) {
            if (!token.dataLike()) {
                continue;
            }
            for (int i = 0; i < anchors.size(); i++) {
                if (matches(token, anchors.get(i), relative)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 绑定判定：锚点为 verified/sourced 态，且 snippet 数值中存在同百分比维度、容差内者。 */
    private static boolean matches(NumberToken token, AnchorRecord anchor, BigDecimal relative) {
        if (anchor.state() != TrustVerdict.VERIFIED && anchor.state() != TrustVerdict.SOURCED) {
            return false;
        }
        for (NumberToken anchorToken : NumberExtractor.extract(anchor.snippet(), true)) {
            if (!anchorToken.dataLike() || anchorToken.percent() != token.percent()) {
                continue;
            }
            BigDecimal diff = token.value().subtract(anchorToken.value()).abs();
            if (diff.compareTo(anchorToken.value().abs().multiply(relative)) <= 0) {
                return true;
            }
        }
        return false;
    }
}
