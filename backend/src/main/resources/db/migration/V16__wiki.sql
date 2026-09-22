-- 投资知识库：三类内容条目（单表，类型特有字段可空）+ 原则纪律规则 + 概念预置幂等标记
-- principle_rule 每用户每指标至多一条（UNIQUE），三期 MS-15 预警消费。

CREATE TABLE wiki_entry (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id),
    type          VARCHAR(16)  NOT NULL,            -- BOOK_NOTE / CONCEPT / RESEARCH_NOTE
    title         VARCHAR(200) NOT NULL,            -- 概念词条即术语名本身，不另设 term 列
    content       TEXT NOT NULL,                    -- Markdown
    category      VARCHAR(50),                      -- CONCEPT 专用：分类
    industry_code VARCHAR(16),                      -- RESEARCH_NOTE 专用：申万一级行业代码
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wiki_entry_user_type ON wiki_entry(user_id, type, updated_at DESC);

CREATE TABLE principle_rule (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    metric      VARCHAR(32) NOT NULL,               -- PrincipleMetric 枚举
    threshold   NUMERIC(12,4) NOT NULL,             -- 单位由枚举语义约定（ratio (0,1] / 倍数 >0）
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_principle_rule_user_metric UNIQUE (user_id, metric)
);

CREATE TABLE wiki_seed_state (
    user_id    BIGINT PRIMARY KEY REFERENCES app_user(id),
    seeded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
