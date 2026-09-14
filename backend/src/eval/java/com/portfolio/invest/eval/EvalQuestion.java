package com.portfolio.invest.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 题库条目（src/eval/resources/questions/*.yaml，每文件一个 YAML 数组元素列表）。
 * 五要素：id / category / mode / turns / expect + judge 引用 + stubData 桩注入。
 *
 * <p>{@code category} 四类：single-turn 单轮 | multi-turn 多轮 | boundary 边界 | mcp MCP。
 * {@code mode}：stub（JVM 内桩上下文跑）| real（真实环境 HTTP 轨，本批次只留口子）。
 * null 的 expect 字段表示该维度不评估（SKIP），而非默认通过。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalQuestion(
        String id,
        String category,
        String mode,
        List<String> turns,
        Expect expect,
        String judge,
        EvalStubData stubData) {

    /** 题目类别枚举值（方案 §5.3 四分类）。 */
    public static final List<String> CATEGORIES = List.of("single-turn", "multi-turn", "boundary", "mcp");
    public static final List<String> MODES = List.of("stub", "real");

    /** 断言维度（方案 §5.3 映射），全部可空=SKIP。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(
            List<String> toolSequence,        // 有序工具序列
            String toolSequenceMatch,         // exact（默认）| prefix：expected 是否须为实际序列的前缀
            EntityAlignment entityAlignment,  // 工具入参实体对齐
            Boolean chartEvent,               // SSE 是否应出现 ChartSpec（specVersion 标记）
            Boolean disclaimer,               // 正文是否含免责表述
            Boolean refusal,                  // 是否应拒答（不给出明确买卖指令）
            DataFidelity dataFidelity) {      // 桩数据数值保真（正文须含桩数据锚点）
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntityAlignment(String tool, String paramContains) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataFidelity(List<String> answerContains) {}

    public boolean isStubMode() {
        return "stub".equals(mode);
    }
}
