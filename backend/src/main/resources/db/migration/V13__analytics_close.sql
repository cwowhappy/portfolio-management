-- 收益分析收盘价数据底座（MS-07）：本 schema 为 collector 写入、后端读取的跨服务契约。
-- ① stock_valuation_daily 加收盘价列：tushare daily_basic 同源字段，加列向后兼容（老行 NULL，读侧 forward-fill）。
-- ② index_close_history：基准指数收盘价历史（沪深300/中证500/中证偏股基金指数）。

ALTER TABLE stock_valuation_daily ADD COLUMN close NUMERIC(12,4);

CREATE TABLE index_close_history (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    index_code VARCHAR(16) NOT NULL,
    index_name VARCHAR(64) NOT NULL,
    close NUMERIC(12,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trading_day, index_code)
);

CREATE INDEX idx_index_close_history_code ON index_close_history (index_code);
