package com.portfolio.invest.web;

/**
 * CSV 批量导入模板（设计规格 §1.1）：UTF-8 BOM 头（Excel 双击直接识别编码与列分隔）+
 * 九列表头 + 六类型示例行各一。正文与 {@code CsvImportParserTest#SIX_TYPE_CSV}
 * 逐行同源——含 DEPOSIT/WITHDRAW 两行的列约束矩阵对齐修正（金额在第 8 列、必填分组名），
 * 用户下载的模板必须能原样通过解析层校验，两处样例不允许各自漂移。
 */
public final class PortfolioImportTemplate {

    public static final String CSV = "\uFEFF" + """
            日期,类型,证券代码,证券名称,分组名称,价格,数量,费用/金额,备注
            2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,首次建仓
            2024-06-20,SELL,600519,贵州茅台,主账户,1750.50,50,5.00,
            2024-07-01,CASH_DIVIDEND,600519,贵州茅台,主账户,25.63,,,
            2024-07-01,STOCK_DIVIDEND,600519,贵州茅台,主账户,0.05,,,
            2024-01-03,DEPOSIT,,,主账户,,,200000.00,初始入金
            2024-08-10,WITHDRAW,,,主账户,,,10000.00,出金买房
            """;

    private PortfolioImportTemplate() {}
}
