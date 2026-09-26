package com.portfolio.invest.domain.industry;

import java.util.List;

/**
 * 产业链聚合根（MS-10 F11，决策 #6）：链/环节/成员三级，保存语义为全文档替换
 * （设计规格 §四：stages+members 嵌套整体替换）。id 由库生成（插入时为 null）。
 * 行业关联由成员派生（§九#3），本聚合不携带行业字段。
 */
public record IndustryChain(Long id, String name, String description, List<ChainStage> stages) {
}
