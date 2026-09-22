-- V15__stock_financial_revenue.sql
-- MS-09 行业研究·上市公司深化：个股财务季数据补营收绝对额（F03 行业内营收排名维度）。
-- 本 schema 为 Python 采集服务（collector）写入的跨服务契约：加列可空、纯增量，不改既有列
-- （V7 契约注释口径：表名与列类型不可随意变更）。
-- revenue：报告期累计营业收入，单位元；collector 由 tushare income.revenue 换算写入（单位见采集侧调研报告）。
ALTER TABLE stock_financial ADD COLUMN revenue NUMERIC(20,4);
