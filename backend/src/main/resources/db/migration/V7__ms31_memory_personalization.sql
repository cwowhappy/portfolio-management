-- ═════════ V7__ms31_memory_personalization.sql ═════════

-- MS-31 记忆与用户个性化两表（设计规格 §3.1/§4.1 原文 DDL）：
--   user_profile_config  ：每用户一行四开关（无行 = 全关，缺省 opt-in，需求决议 #3；
--                           单行四列而非四行键值，需求决议 #3 表形态裁决）；
--   memory_digest_cursor ：全局单行游标（id=1 CHECK 锁死；seed 首跑窗口 = 24h，
--                           失败粒度 = 整批顺延，决议 #8）。

CREATE TABLE user_profile_config (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL UNIQUE REFERENCES app_user(id),
    style_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    industry_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    watchlist_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    holdings_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE memory_digest_cursor (
    id             INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),   -- 全局单行
    last_success_at TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO memory_digest_cursor (id, last_success_at) VALUES (1, now());  -- 首跑窗口 = 24h
