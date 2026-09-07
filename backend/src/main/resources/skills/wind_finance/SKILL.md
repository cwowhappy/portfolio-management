---
name: wind_finance
description: 使用 Wind AIFin 官方 MCP 获取机构级金融数据与文档：A股/港美股/基金/债券/指数行情与财务、公告年报季报招股书、财经新闻、宏观 EDB 指标、跨标的聚合排名与复合指标。
category: data_source
default_enabled: false
depends_on_provider: wind
---

# Wind 机构级金融数据与文档

## 定位
机构级权威数据 + 文档语义 + 跨标的聚合分析。

## 触发条件
- 用：公告/年报/季报/招股书/财经新闻、宏观/行业/汇率 EDB 指标、跨标的聚合/加权/排名。
- 不用：内置工具已覆盖的 A股行情/K线/财务/新闻/大盘/估值（优先内置工具）；期货/港美股/债券/三表/指数成分等 Tushare 独有（走 `tushare_data`）。

## 覆盖域（7 个 server_type）
| server_type | 覆盖 | 代表工具 |
|---|---|---|
| stock_data | 股票筛选/行情/K线/财务/股东/事件/技术/风险 | get_stock_price_indicators、search_* |
| fund_data | 基金/ETF/LOF 筛选/行情/持仓/业绩 | get_fund_price_indicators |
| index_data | 指数/板块行情/基本面/技术 | get_index_price_indicators |
| bond_data | 债券档案/发债主体/行情估值 | — |
| financial_docs | 公告/年报/季报/招股书/财经新闻 | get_company_announcements、get_financial_news |
| economic_data | 宏观/行业/汇率 EDB 指标 | search_economic_indicator、query_economic_indicator_data |
| analytics_data | 跨标的聚合/加权/排名/复合指标 | — |

## 流程
1. 定路由：按标的类型选 server_type（见上表）。
2. 取数：行情/财务走对应领域专项工具；文档走 financial_docs；宏观先 search_economic_indicator 确认指标代码再 query_economic_indicator_data；聚合/排名走 analytics_data。
3. 读回执：成功读数据；失败按错误码说明，不得用 analytics_data 伪装支持。

## 来源声明
引用本技能数据时标注「数据来源于万得 Wind 金融数据服务」。
