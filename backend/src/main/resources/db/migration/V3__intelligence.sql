-- ═════════ V3__intelligence.sql ═════════

-- M15 智能情报域（MS-20~23，2026-09-29 设计规格 §2）。
-- intelligence_news_raw / intelligence_announcement / intelligence_policy_raw /
-- intelligence_macro_series 为 Python 采集服务（collector）写入的跨服务契约
-- （先例 V1 treasury_yield_curve/stock_valuation_daily 注释口径：表名与列类型不可随意变更）；
-- 其余表由 backend 写入；intelligence_macro_calendar 为迁移 seed + SQL 人工维护（决策 #29）。

-- §2.2：trgm 中文模糊检索（PG16 官方镜像含 contrib）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── 新闻（F01/F02/F03）──────────────────────────────────────────────

CREATE TABLE intelligence_news_raw (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source       VARCHAR(16)  NOT NULL,
    external_id  VARCHAR(64)  NOT NULL,
    title        TEXT         NOT NULL,
    summary      TEXT,
    published_at TIMESTAMPTZ  NOT NULL,
    url          TEXT,
    stock_tags   JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- 源站标的标签原样
    fetched_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);
CREATE INDEX idx_intelligence_news_raw_published ON intelligence_news_raw (published_at DESC);
CREATE INDEX idx_intelligence_news_raw_title_trgm ON intelligence_news_raw USING gin (title gin_trgm_ops);

CREATE TABLE intelligence_news_extract (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    news_raw_id   BIGINT      NOT NULL REFERENCES intelligence_news_raw(id) ON DELETE CASCADE,
    event_type    VARCHAR(32),
    stock_codes   JSONB,
    industry_codes JSONB,
    summary       TEXT,
    direction     VARCHAR(16) CONSTRAINT ck_news_dir CHECK (direction IN ('BULLISH','BEARISH','NEUTRAL')),
    key_numbers   JSONB,
    importance    INT CONSTRAINT ck_news_imp CHECK (importance BETWEEN 0 AND 100),
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING' CONSTRAINT ck_news_st CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    model         VARCHAR(64),
    extracted_at  TIMESTAMPTZ,
    UNIQUE (news_raw_id)
);

-- ── 公告（F06~F09）──────────────────────────────────────────────────

CREATE TABLE intelligence_announcement (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source          VARCHAR(16) NOT NULL,
    external_id     VARCHAR(64) NOT NULL,
    stock_code      VARCHAR(12) NOT NULL,
    stock_name      VARCHAR(32),
    title           TEXT        NOT NULL,
    ann_type_source VARCHAR(64),                -- 源站栏目（决策 #24 栏目映射的预判输入）
    major           BOOLEAN     NOT NULL,       -- 栏目映射预判的重大类型（采集侧，§2.3-6）
    published_at    TIMESTAMPTZ  NOT NULL,
    pdf_url         TEXT,
    fetched_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);
CREATE INDEX idx_intelligence_announcement_published ON intelligence_announcement (published_at DESC);
CREATE INDEX idx_intelligence_announcement_title_trgm ON intelligence_announcement USING gin (title gin_trgm_ops);
CREATE INDEX idx_intelligence_announcement_stock ON intelligence_announcement (stock_code, published_at DESC);

CREATE TABLE intelligence_announcement_extract (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    announcement_id BIGINT NOT NULL REFERENCES intelligence_announcement(id) ON DELETE CASCADE,
    metrics         JSONB,                      -- 六字段 + undisclosed 数组（§4.4 契约，未披露严禁编造）
    ann_types       JSONB,                      -- AnnouncementType 十类并集（栏目预判 ∪ LLM 精判，P2 Task 4 契约）
    pdf_text        TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' CONSTRAINT ck_ann_st CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    model           VARCHAR(64),
    extracted_at    TIMESTAMPTZ,
    UNIQUE (announcement_id)
);

-- ── 政策（F10~F12）──────────────────────────────────────────────────

CREATE TABLE intelligence_policy_raw (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source       VARCHAR(16) NOT NULL,          -- pboc/csrc/mof/stats
    external_id  VARCHAR(64) NOT NULL,
    title        TEXT        NOT NULL,
    url          TEXT,
    published_at TIMESTAMPTZ  NOT NULL,
    content_text TEXT,
    UNIQUE (source, external_id)
);
CREATE INDEX idx_intelligence_policy_raw_published ON intelligence_policy_raw (published_at DESC);

CREATE TABLE intelligence_policy_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_raw_id  BIGINT NOT NULL REFERENCES intelligence_policy_raw(id),   -- 政策长期保留，无级联删除
    direction      VARCHAR(16) CONSTRAINT ck_policy_dir CHECK (direction IN ('EASING','TIGHTENING','NEUTRAL')),
    strength       VARCHAR(16) CONSTRAINT ck_policy_strength CHECK (strength IN ('HIGH','MEDIUM','LOW')),
    affected_areas JSONB,
    summary        TEXT,
    confidence     VARCHAR(8) CONSTRAINT ck_policy_conf CHECK (confidence IN ('HIGH','LOW')),   -- LOW=低置信标注（F12）
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING' CONSTRAINT ck_policy_st CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    model          VARCHAR(64),
    extracted_at   TIMESTAMPTZ,
    UNIQUE (policy_raw_id)
);

-- ── 宏观（F13/F14，决策 #12 五先行指标）────────────────────────────

CREATE TABLE intelligence_macro_series (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    indicator   VARCHAR(16) NOT NULL,           -- CPI/PPI/PMI/LPR/AFMI/M2（国债收益率复用 treasury_yield_curve 不建）
    period      VARCHAR(10) NOT NULL,           -- 月 2026-09 / 日 2026-09-29
    period_type VARCHAR(8)  NOT NULL CONSTRAINT ck_macro_period_type CHECK (period_type IN ('MONTH','DAY')),
    value       NUMERIC(18,4) NOT NULL,
    yoy         NUMERIC(10,4),
    source_url  TEXT,
    source_note VARCHAR(255),
    UNIQUE (indicator, period)
);
CREATE INDEX idx_intelligence_macro_series_indicator ON intelligence_macro_series (indicator, period DESC);

-- 宏观日历（决策 #29）：预期发布日程，预期非承诺（F14）；seed 起本迁移落 2026Q4~2027，
-- 之后每年由 SQL 人工续写（updated_at 随维护刷新）
CREATE TABLE intelligence_macro_calendar (
    indicator     VARCHAR(16) NOT NULL,
    expected_date DATE        NOT NULL,
    frequency     VARCHAR(8)  NOT NULL,
    source_site   VARCHAR(64) NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (indicator, expected_date)
);

-- D19 社融降级留痕：状态变化才插一行（MacroHealthService 巡检判定）
CREATE TABLE intelligence_source_switch (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    indicator    VARCHAR(16) NOT NULL,
    from_source  VARCHAR(32) NOT NULL,
    to_source    VARCHAR(32) NOT NULL,
    reason       TEXT,
    switched_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 简报（F04，决策 #20/#22/#23）────────────────────────────────────

CREATE TABLE intelligence_daily_brief (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trade_date   DATE        NOT NULL,
    content_md   TEXT        NOT NULL,          -- 5+1 节 markdown（生成失败/空简版同留档）
    top_stocks   JSONB,                         -- 决策 #23 检索快照（条目 stock_codes 频次 top 20）
    status       VARCHAR(16) NOT NULL CONSTRAINT ck_brief_st CHECK (status IN ('GENERATED','EMPTY_SIMPLE','FAILED')),
    fail_reason  TEXT,
    model        VARCHAR(64),
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trade_date)
);
CREATE INDEX idx_intelligence_daily_brief_content_trgm ON intelligence_daily_brief USING gin (content_md gin_trgm_ops);

-- ── 订阅与飞书绑定（F16，决策 #18/D8）──────────────────────────────

-- 每用户一行；无行 = 默认（pushEnabled=true、空集），由 domain 缺省实例表达不落库
CREATE TABLE intelligence_subscription (
    user_id      BIGINT PRIMARY KEY REFERENCES app_user(id),
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    industries   JSONB   NOT NULL DEFAULT '[]'::jsonb,   -- 申万一级行业码
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 聚合子表：随 subscription 级联删除（聚合根 save 增删差集，两表事务一致性）
CREATE TABLE intelligence_subscription_stock (
    user_id    BIGINT NOT NULL REFERENCES intelligence_subscription(user_id) ON DELETE CASCADE,
    stock_code VARCHAR(12) NOT NULL,
    stock_name VARCHAR(32),
    added_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, stock_code)
);

-- D8 飞书绑定：重复绑定覆盖本行 open_id；同一 open_id 绑他人由 UNIQUE(open_id) 拒绝
CREATE TABLE intelligence_feishu_binding (
    user_id  BIGINT PRIMARY KEY REFERENCES app_user(id),
    open_id  VARCHAR(64) NOT NULL UNIQUE,
    bound_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- D8 绑定码：6 位数字、10 分钟 TTL、一次性（used_at 非空即已核销）；过期由清理任务扫除
CREATE TABLE intelligence_binding_code (
    code       VARCHAR(6) PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);

-- ── 推送留痕（NFR-5）────────────────────────────────────────────────
-- status 较设计规格 §2.1 的 VARCHAR(8) 加宽为 VARCHAR(32)：P2 Task 7 契约含
-- SKIPPED_NO_BINDING（订阅命中但用户未绑飞书，跳过单发仍留痕）三态。

CREATE TABLE intelligence_push_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 可空 FK：群推 user_id 为 NULL 不受约束；定向推送归属校验与 subscription/
    -- feishu_binding/binding_code 三张同族表一致（e2e-cleanup.sh 已登记本表 DELETE）
    user_id    BIGINT REFERENCES app_user(id),
    push_type  VARCHAR(16) NOT NULL CONSTRAINT ck_push_type CHECK (push_type IN ('BRIEF','ANNOUNCEMENT')),
    target     VARCHAR(128) NOT NULL,           -- open_id 或 chatId
    ref_table  VARCHAR(32),                     -- 幂等查重键（intelligence_announcement / intelligence_daily_brief）
    ref_id     BIGINT,
    status     VARCHAR(32) NOT NULL CONSTRAINT ck_push_status CHECK (status IN ('OK','FAIL','SKIPPED_NO_BINDING')),
    error      TEXT,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 既有表加列（D13：项目级情报提醒开关，决策 #26 默认开）──────────

ALTER TABLE research_project
    ADD COLUMN intelligence_alert_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- ── 宏观日历种子：五指标 × 2026Q4~2027（15 个月 × 5 = 75 行）────────
-- 固定日指标（CPI/PPI 每月 9 日、LPR 每月 20 日、AFMI 每月 12 日）由月份序列拼日；
-- PMI 按月末（31 大月 / 30 小月 / 2 月 28 日，闰年自然 29 日）单独以「次月一日减一天」
-- 推导，杜绝 2027-04-31 这类非法日期。日历为预期非承诺（F14）。

INSERT INTO intelligence_macro_calendar (indicator, expected_date, frequency, source_site)
SELECT r.indicator,
       CASE WHEN r.indicator = 'PMI'
            THEN (m.month_start + INTERVAL '1 month - 1 day')::date
            ELSE (to_char(m.month_start, 'YYYY-MM') || '-' || lpad(r.dom::text, 2, '0'))::date
       END,
       'MONTH',
       r.source_site
FROM generate_series(DATE '2026-10-01', DATE '2027-12-01', INTERVAL '1 month') AS m(month_start)
CROSS JOIN (VALUES ('CPI',  9, '国家统计局'),
                   ('PPI',  9, '国家统计局'),
                   ('PMI', 31, '国家统计局'),   -- dom 仅占位，PMI 走月末分支
                   ('LPR', 20, '中国人民银行'),
                   ('AFMI', 12, '中国人民银行')) AS r(indicator, dom, source_site);
