package com.portfolio.invest.domain.research;

/**
 * 模板改进建议仓库端口（M16-F16，只收集不生效）：<b>仅 insert，不暴露任何读/更新用例</b>
 * ——建议反哺 SOP v2 由人工离线消费，v1 无页面读回需求（domain 亦无更新用例）。
 */
public interface ResearchFeedbackRepository {

    /** 追加一条建议（ResearchFeedback.create 已完成域校验）。 */
    ResearchFeedback insert(ResearchFeedback feedback);
}
