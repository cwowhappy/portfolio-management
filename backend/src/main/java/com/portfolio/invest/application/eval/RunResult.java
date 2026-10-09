package com.portfolio.invest.application.eval;

import java.util.List;
import java.util.Map;

/**
 * 单跑评测聚合结果（MS-30 B4）：回归判定的输入数据载体，收割侧（Task 6）从报告 JSON
 * 与 eval_run 历史组装；本类为纯数据，无 IO 无 Spring。
 *
 * <p>口径约定：
 * <ul>
 *   <li>totalPass/totalFail/totalError 传报告原始计数（收割侧<b>勿预合并</b>）——通过率
 *       分母 = 三者之和（ERROR 计入分母，需求决策 #10），合并由 {@code EvalRegressionJudge}
 *       统一执行避免双计；</li>
 *   <li>completeness 为收割侧算出的绝对完成率（产出终态结果的题数 / 应跑题数，0~1），
 *       仅不可比分支的「完成率 &lt;80% 告警」消费它；报告 runMeta.completeness 的
 *       FULL/PARTIAL 字符串语义不受影响（落库口径归收割侧）；</li>
 *   <li>byCategory 值为长度 3 的 {@code int[]}：[0]=pass、[1]=fail、[2]=error（与总口径
 *       同构，分类通过率 = pass/(pass+fail+error)）；</li>
 *   <li>rubricVersions 为 rubric 逐文件版本映射（键=asset_key，值=版本号或内容 hash 的
 *       字符串形态——判定只消费「与 baseline 是否全等」，与设计规格 brief 的单数
 *       rubricVersion 不同，逐文件 Map 才能与 §4.2「可比性指纹 = 题库 hash + rubric
 *       各文件版本」对齐）；</li>
 *   <li>outcomes 为题目级明细（设计修正：翻转判定按同题 id 对齐，聚合数字算不出翻转，
 *       收割侧本就持有题目明细，组装成本近零）；</li>
 *   <li>prevRunDegraded 表示「历史序上一跑」是否 DEGRADED（收割侧从上一行
 *       eval_run.alert_status 读出）——纯函数据此区分 NONE 与 RECOVERED（§3.1 恢复判定
 *       的输入通道，judge 签名不含历史故经本字段携带）。</li>
 * </ul>
 *
 * @param completeness     绝对完成率（0~1，收割侧计算，见口径约定）
 * @param totalPass        总 PASS 题数（原始计数）
 * @param totalFail        总 FAIL 题数（原始计数，未合并 ERROR）
 * @param totalError       总 ERROR 题数（原始计数）
 * @param byCategory       分类 → [pass, fail, error]（键为分类常量，如 METRIC_CALC）
 * @param questionBankHash 题库聚合内容 hash（可比性指纹之一；null 视为不可比旧档）
 * @param rubricVersions   rubric 逐文件版本映射（可比性指纹之二，与 baseline 全等才可比）
 * @param outcomes         题目级结果明细（翻转判定对齐用，双侧齐备才参与）
 * @param prevRunDegraded  上一跑（历史序）是否 DEGRADED（恢复判定的输入）
 */
public record RunResult(
        double completeness,
        int totalPass,
        int totalFail,
        int totalError,
        Map<String, int[]> byCategory,
        String questionBankHash,
        Map<String, String> rubricVersions,
        List<QuestionOutcomeLite> outcomes,
        boolean prevRunDegraded) {

    /** 题目级结果明细：{题目 id, 是否 PASS}（翻转 = 同 id 基线 PASS 本跑 FAIL）。 */
    public record QuestionOutcomeLite(String id, boolean pass) {
    }

    public RunResult {
        byCategory = byCategory == null ? Map.of() : Map.copyOf(byCategory);
        rubricVersions = rubricVersions == null ? Map.of() : Map.copyOf(rubricVersions);
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }
}
