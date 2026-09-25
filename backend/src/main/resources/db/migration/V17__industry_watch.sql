-- 行业对比「关注行业」持久化（MS-14 P2，决策 #5）：对比视图 ⭐ 关注落库，登录用户隔离；
-- 结构平移 V14 watchlist_item（stock_code → industry_code）。不设每用户上限
-- （行业总数 31 有界，见需求规格 NFR），UNIQUE(user_id, industry_code) 兜底幂等。

CREATE TABLE industry_watch (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id),
    industry_code VARCHAR(16) NOT NULL,
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, industry_code)
);
CREATE INDEX idx_industry_watch_user ON industry_watch(user_id, added_at DESC);
