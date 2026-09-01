-- 个股基本面数据底座：估值日快照 / 财务指标季数据
-- 本 schema 为 Python 采集服务（collector）写入的跨服务契约，表名与列类型不可随意变更。
-- total_mv/circ_mv 单位：元（collector 由 tushare 万元换算）；dividend_yield 为 dv_ttm 口径。

CREATE TABLE stock_valuation_daily (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NOT NULL,
    pe_ttm NUMERIC(12,4),
    pb NUMERIC(12,4),
    dividend_yield NUMERIC(12,4),
    total_mv NUMERIC(20,2),
    circ_mv NUMERIC(20,2),
    turnover_rate NUMERIC(12,4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trading_day, stock_code)
);

CREATE TABLE stock_financial (
    id BIGSERIAL PRIMARY KEY,
    report_date DATE NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    roe NUMERIC(12,4),
    roa NUMERIC(12,4),
    gross_margin NUMERIC(12,4),
    debt_to_assets NUMERIC(12,4),
    current_ratio NUMERIC(12,4),
    revenue_yoy NUMERIC(12,4),
    netprofit_yoy NUMERIC(12,4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (report_date, stock_code)
);

CREATE INDEX idx_stock_valuation_daily_day ON stock_valuation_daily (trading_day);
CREATE INDEX idx_stock_valuation_daily_code ON stock_valuation_daily (stock_code);
CREATE INDEX idx_stock_financial_code ON stock_financial (stock_code, report_date);
