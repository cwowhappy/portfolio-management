-- 持仓组合管理：组合 / 分组 / 持仓 / 交易 / 分红 / 现金流水
-- 本 schema 为持仓域跨服务契约，表名与列类型不可随意变更。

CREATE TABLE portfolio (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES app_user(id),
    cost_method VARCHAR(16) NOT NULL DEFAULT 'WEIGHTED_AVG',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE holding_group (
    id           BIGSERIAL PRIMARY KEY,
    portfolio_id BIGINT NOT NULL REFERENCES portfolio(id) ON DELETE CASCADE,
    name         VARCHAR(64) NOT NULL,
    type         VARCHAR(16) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_holding_group_portfolio ON holding_group(portfolio_id);

CREATE TABLE position (
    id                       BIGSERIAL PRIMARY KEY,
    portfolio_id             BIGINT NOT NULL REFERENCES portfolio(id) ON DELETE CASCADE,
    group_id                 BIGINT NOT NULL REFERENCES holding_group(id),
    stock_code               VARCHAR(16) NOT NULL,
    stock_name               VARCHAR(64) NOT NULL,
    quantity                 NUMERIC(18,4) NOT NULL DEFAULT 0,
    cost_basis               NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_buy_cost           NUMERIC(18,4) NOT NULL DEFAULT 0,
    cumulative_cash_dividend NUMERIC(18,4) NOT NULL DEFAULT 0,
    realized_pnl             NUMERIC(18,4) NOT NULL DEFAULT 0,
    net_cash_flow            NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (portfolio_id, group_id, stock_code)
);
CREATE INDEX idx_position_group ON position(group_id);

CREATE TABLE trade (
    id          BIGSERIAL PRIMARY KEY,
    position_id BIGINT NOT NULL REFERENCES position(id) ON DELETE CASCADE,
    type        VARCHAR(8) NOT NULL,
    trade_date  DATE NOT NULL,
    price       NUMERIC(18,4) NOT NULL,
    quantity    NUMERIC(18,4) NOT NULL,
    fee         NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trade_position ON trade(position_id);

CREATE TABLE dividend (
    id             BIGSERIAL PRIMARY KEY,
    position_id    BIGINT NOT NULL REFERENCES position(id) ON DELETE CASCADE,
    type           VARCHAR(8) NOT NULL,
    ex_date        DATE NOT NULL,
    cash_per_share NUMERIC(18,6),
    stock_ratio    NUMERIC(18,6),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dividend_position ON dividend(position_id);

CREATE TABLE cash_transaction (
    id         BIGSERIAL PRIMARY KEY,
    group_id   BIGINT NOT NULL REFERENCES holding_group(id) ON DELETE CASCADE,
    type       VARCHAR(16) NOT NULL,
    amount     NUMERIC(18,4) NOT NULL,
    tx_date    DATE NOT NULL,
    note       VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cash_transaction_group ON cash_transaction(group_id);
