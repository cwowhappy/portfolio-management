-- 估值数据底座：全市场快照 / 行业估值 / 国债收益率 / 指数估值历史 / 申万行业映射
-- 本 schema 为 Python 采集服务（P3）写入的跨服务契约，表名与列类型不可随意变更。

CREATE TABLE valuation_snapshot (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL UNIQUE,
    pe_median NUMERIC(12,4) NOT NULL,
    pb_median NUMERIC(12,4) NOT NULL,
    net_breaker_count INTEGER NOT NULL,
    net_breaker_ratio NUMERIC(8,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE industry_valuation (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    industry_code VARCHAR(16) NOT NULL,
    industry_name VARCHAR(64) NOT NULL,
    pe NUMERIC(12,4),
    pb NUMERIC(12,4),
    roe NUMERIC(12,4),
    dividend_yield NUMERIC(12,4),
    UNIQUE (trading_day, industry_code)
);

CREATE TABLE treasury_yield (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL UNIQUE,
    yield_10y NUMERIC(8,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE index_valuation_history (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    index_code VARCHAR(16) NOT NULL,
    index_name VARCHAR(64) NOT NULL,
    pe NUMERIC(12,4),
    pb NUMERIC(12,4),
    dividend_yield NUMERIC(12,4),
    UNIQUE (trading_day, index_code)
);

CREATE TABLE shenwan_industry_mapping (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(16) NOT NULL UNIQUE,
    stock_name VARCHAR(64) NOT NULL,
    industry_code VARCHAR(16) NOT NULL,
    industry_name VARCHAR(64) NOT NULL
);

CREATE INDEX idx_industry_valuation_day ON industry_valuation (trading_day);
CREATE INDEX idx_index_valuation_code ON index_valuation_history (index_code, trading_day);
CREATE INDEX idx_shenwan_industry_code ON shenwan_industry_mapping (industry_code);
