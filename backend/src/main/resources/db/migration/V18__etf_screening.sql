-- ETF 筛选基础表（MS-14 P3，Task 13）：collector etf_basic 周更任务写入
-- 目录/基金名/费率（管理+托管合计年化%）/规模（亿元）/跟踪指数/类别。
-- tracking_error_1y 由 Task 15 误差任务单独回写（本迁移只建列不写入）。
-- DDL 照设计规格说明 §3.1；源口径见 09-调研报告/2026-09-26-ETF数据源探测.md §七
-- （枚举=新浪 fund_etf_category_sina 目录 1685 只，enrich=天天基金移动端 Detail 端点逐只）。

CREATE TABLE etf_basic (
    fund_code             VARCHAR(16) PRIMARY KEY,   -- 6 位无后缀，与 index_close_history.index_code 同格式
    fund_name             VARCHAR(64) NOT NULL,
    fee_rate              NUMERIC(8,4),              -- 管理费+托管费合计（年化%），null=未知
    scale                 NUMERIC(18,4),             -- 亿元，null=未知
    tracking_index_code   VARCHAR(16),               -- 跟踪指数 6 位码，null=未知
    tracking_index_name   VARCHAR(64),
    category              VARCHAR(32) NOT NULL,      -- 宽基/行业/商品/债券/QDII/其他
    tracking_error_1y     NUMERIC(10,6),             -- 近1年日频差值 std×√252，null=样本不足
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
