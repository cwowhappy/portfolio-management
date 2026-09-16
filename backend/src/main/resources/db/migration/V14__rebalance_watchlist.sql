-- 再平衡与筛选增强（MS-08）：① allocation_plan 加再平衡频率与时间提醒锚点（一列两用：
-- 激活方案或 ack「已完成再平衡」时重置，存量生效方案以迁移日为基线）；
-- ② 自选观察列表（domain/screening 消费，登录用户隔离）。

ALTER TABLE allocation_plan
    ADD COLUMN rebalance_frequency VARCHAR(16) NOT NULL DEFAULT 'OFF',
    ADD COLUMN last_rebalanced_at  TIMESTAMPTZ;

UPDATE allocation_plan SET last_rebalanced_at = now() WHERE active;

CREATE TABLE watchlist_item (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    stock_code VARCHAR(16) NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, stock_code)
);
CREATE INDEX idx_watchlist_user ON watchlist_item(user_id, added_at DESC);
