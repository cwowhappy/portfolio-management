-- 国债收益率曲线（多期限）+ 指数成分股；10Y 迁移到曲线表后废弃旧 treasury_yield 表。
-- 本 schema 为 Python 采集服务（collector）写入的跨服务契约，表名与列类型不可随意变更。

CREATE TABLE treasury_yield_curve (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    term VARCHAR(16) NOT NULL,          -- '1Y','3Y','5Y','10Y','30Y'
    yield NUMERIC(8,4) NOT NULL,
    UNIQUE (trading_day, term)
);

CREATE TABLE index_constituent (
    id BIGSERIAL PRIMARY KEY,
    index_code VARCHAR(16) NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64),
    weight NUMERIC(12,6),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (index_code, stock_code)
);

-- 既有 10 年期国债收益率迁移到曲线表（term='10Y'）
INSERT INTO treasury_yield_curve (trading_day, term, yield)
SELECT trading_day, '10Y', yield_10y FROM treasury_yield;

-- 读侧已切换到曲线表，废弃旧表
DROP TABLE treasury_yield;

CREATE INDEX idx_treasury_yield_curve_term ON treasury_yield_curve (term, trading_day);
CREATE INDEX idx_index_constituent_stock ON index_constituent (stock_code);
