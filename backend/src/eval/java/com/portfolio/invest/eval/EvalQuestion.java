package com.portfolio.invest.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * 题库条目（src/eval/resources/questions/*.yaml，每文件一个 YAML 数组元素列表）。
 * 五要素：id / category / mode / turns / expect + judge 引用 + stubData 桩注入。
 *
 * <p>{@code category} 四类：single-turn 单轮 | multi-turn 多轮 | boundary 边界 | mcp MCP。
 * {@code mode}：stub（JVM 内桩上下文跑）| real（真实环境 HTTP 轨，本批次只留口子）。
 * null 的 expect 字段表示该维度不评估（SKIP），而非默认通过（装载校验要求至少声明一维，
 * 见 {@link #declaredDimensions()}）。
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
            MemoryFollowUp memoryFollowUp,    // 多轮记忆：指定轮的工具调用仍承接前轮实体
            Boolean chartEvent,               // SSE 是否应出现 ChartSpec（specVersion 标记）
            Boolean disclaimer,               // 正文是否含免责表述
            Boolean refusal,                  // 是否应拒答（不给出明确买卖指令）
            DataFidelity dataFidelity,        // 桩数据数值保真（正文须含桩数据锚点）
            Interrupt interrupt) {            // HITL：RUN_FINISHED 应含权限确认中断

        /**
         * 已声明的断言维度名（与 AssertionEngine 维度一一对应；空集=全维 SKIP 即无效题，
         * 装载校验拒绝）。noRetry 不经 expect 声明（有工具调用即评估），故不在列。
         */
        public List<String> declaredDimensions() {
            List<String> dims = new ArrayList<>();
            if (toolSequence != null && !toolSequence.isEmpty()) dims.add("toolSequence");
            if (entityAlignment != null) dims.add("entityAlignment");
            if (memoryFollowUp != null) dims.add("multiTurnMemory");
            if (chartEvent != null) dims.add("chartEvent");
            if (disclaimer != null) dims.add("disclaimer");
            if (refusal != null) dims.add("refusal");
            if (dataFidelity != null && dataFidelity.answerContains() != null
                    && !dataFidelity.answerContains().isEmpty()) dims.add("dataFidelity");
            if (interrupt != null) dims.add("interrupt");
            return dims;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntityAlignment(String tool, String paramContains) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataFidelity(List<String> answerContains) {}

    /**
     * 多轮记忆断言：第 {@code turnIndex} 轮（1 起，须 ≥ 2）的 TOOL_CALL 仍对准前轮实体——
     * 服务端记忆下的指代承接。{@code toolContains}/{@code paramContains} 至少声明其一
     * （工具名/入参含子串，任一次该轮调用命中即 PASS）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryFollowUp(Integer turnIndex, String toolContains, String paramContains) {}

    /**
     * HITL 中断断言（口径同 McpHitlIntegrationTest）：RUN_FINISHED outcome 含
     * {@code kind}（默认 permission_confirm）中断、metadata.toolName 匹配、中断的
     * toolCallId 对应确有其名的 TOOL_CALL_START、且该写工具未被执行（无 TOOL_CALL_RESULT）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Interrupt(String toolName, String kind) {}

    public boolean isStubMode() {
        return "stub".equals(mode);
    }
}
