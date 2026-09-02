-- 投资决策记录：买入/卖出备忘、研究笔记、定期复盘（单表，类型特有字段可空）
-- trade_id 为 M08 交易的软引用（无外键、不级联），交易删除后悬空保留。

CREATE TABLE journal_entry (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES app_user(id),
    type         VARCHAR(24) NOT NULL,
    stock_code   VARCHAR(16),
    stock_name   VARCHAR(64),
    trade_id     BIGINT,
    title        VARCHAR(128) NOT NULL,
    content      TEXT NOT NULL,
    target_price NUMERIC(18,4),
    stop_loss    NUMERIC(18,4),
    period_type  VARCHAR(16),
    period_start DATE,
    period_end   DATE,
    event_date   DATE NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_journal_entry_user ON journal_entry(user_id, event_date DESC);
