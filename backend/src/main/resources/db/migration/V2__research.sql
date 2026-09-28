-- ═════════ V2__research.sql ═════════

-- V2 research 域（invest-sop M16，2026-09-28 设计规格 §二）；另含 P1 seeding 依赖的
-- wiki_seed_state.sop_seeded_at（P2 计划 Task 1 Interfaces 声明随本迁移交付，见文件尾）。

CREATE TABLE research_project (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id),
    stock_code    VARCHAR(16) NOT NULL,
    stock_name    VARCHAR(64) NOT NULL,
    industry_code VARCHAR(16),
    title         VARCHAR(200) NOT NULL,
    current_stage VARCHAR(32) NOT NULL,            -- ResearchStage：NEW_ANALYSIS/STRATEGY/POSITION/REVIEW
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',   -- S1：ACTIVE/ARCHIVED
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_research_project_user ON research_project(user_id, status, updated_at DESC);

-- S6：仅存手动覆盖；AUTO 完成度由领域服务推导
CREATE TABLE research_stage_record (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES research_project(id),
    stage        VARCHAR(32) NOT NULL,
    manual_state VARCHAR(16),                      -- NULL/COMPLETED/REOPENED
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_research_stage UNIQUE (project_id, stage)
);

CREATE TABLE research_strategy_doc (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT NOT NULL UNIQUE REFERENCES research_project(id),
    state          VARCHAR(16) NOT NULL DEFAULT 'DRAFT',   -- DRAFT/FINALIZED（D13 两级，修订即回 DRAFT 覆盖）
    thesis         TEXT,                            -- 投资逻辑
    valuation_low  NUMERIC(12,4),                   -- 估值区间（D6 手动）
    valuation_high NUMERIC(12,4),
    position_plan  TEXT,                            -- 仓位计划（目标占比叙述；分批结构在 entry_plan）
    buy_conditions TEXT,                            -- 买入条件叙述
    risk_notes     TEXT,                            -- 风险叙述
    finalized_at   TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_strategy_valuation CHECK (valuation_low IS NULL OR valuation_high IS NULL OR valuation_low < valuation_high)
);

CREATE TABLE research_falsifier (
    id             BIGSERIAL PRIMARY KEY,
    strategy_id    BIGINT NOT NULL REFERENCES research_strategy_doc(id),
    kind           VARCHAR(16) NOT NULL,            -- D10：PREDICATE/EVENT
    predicate      VARCHAR(24),                     -- PREDICATE：PRICE_BELOW/PRICE_ABOVE/PE_ABOVE/PB_ABOVE
    threshold      NUMERIC(12,4),
    event_checked  BOOLEAN DEFAULT FALSE,           -- EVENT：人工勾选
    note           VARCHAR(500),
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE research_entry_plan (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT NOT NULL REFERENCES research_project(id),
    win_rate      NUMERIC(6,4),                     -- D23：凯利参数手动输入
    payoff_ratio  NUMERIC(6,4),
    kelly_ratio   NUMERIC(6,4),                     -- 系统算术结果
    note          VARCHAR(500),
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE research_entry_batch (
    id         BIGSERIAL PRIMARY KEY,
    plan_id    BIGINT NOT NULL REFERENCES research_entry_plan(id),
    seq        INT NOT NULL,
    price_low  NUMERIC(12,4) NOT NULL,
    price_high NUMERIC(12,4) NOT NULL,
    quantity   BIGINT NOT NULL,
    amount     NUMERIC(14,2),
    ratio      NUMERIC(6,4) NOT NULL,               -- 预计仓位占比（domain 校验 Σratio ≤ 1）
    CONSTRAINT uk_entry_batch UNIQUE (plan_id, seq)
);

-- NFR-2 append-only：无 version/updated_at，domain 不提供更新用例
CREATE TABLE research_check_record (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES research_project(id),
    check_type      VARCHAR(16) NOT NULL,           -- BUY/ADD/REDUCE/SELL
    items           JSONB NOT NULL,                 -- S4：命中项快照
    result          VARCHAR(16) NOT NULL,           -- CONFIRMED/OVERRIDDEN
    override_reason VARCHAR(500),                   -- OVERRIDDEN 必填（domain 校验）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_check_record_project ON research_check_record(project_id, created_at DESC);

CREATE TABLE research_falsifier_hit (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES research_project(id),
    falsifier_id BIGINT NOT NULL REFERENCES research_falsifier(id),
    basis        VARCHAR(200) NOT NULL,             -- 可解释依据（D10）：如「收盘价 12.34 < 下限 13.00（东财收盘）」
    review_id    BIGINT,                            -- 软引用 research_falsifier_review，评审后回填
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE research_falsifier_review (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES research_project(id),
    conclusion  VARCHAR(16) NOT NULL,               -- HOLD/REDUCE/EXIT/REVISE
    reason      VARCHAR(1000) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()  -- append-only
);

CREATE TABLE research_review (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT NOT NULL REFERENCES research_project(id),
    tier          VARCHAR(16) NOT NULL,             -- D7：MONTHLY/QUARTERLY/WEEKLY
    period_start  DATE NOT NULL,
    period_end    DATE NOT NULL,
    answers       JSONB,                            -- S5：维度作答（字段集待 MS-24）
    narrative     TEXT,
    auto_snapshot JSONB,                            -- F14：写入时定格（计算值 + 口径标注）
    overrides     JSONB,                            -- 用户覆盖值
    trade_ids     BIGINT[],                         -- D11：归因圈选（软引用 trade）
    reflux_state  VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- F16：PENDING/CONFIRMED/REFLOWN
    wiki_entry_id BIGINT,                           -- 回流目标软引用
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_review_project ON research_review(project_id, period_start DESC);

CREATE TABLE research_feedback (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES research_project(id),
    review_id   BIGINT REFERENCES research_review(id),
    stage       VARCHAR(32) NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()  -- 只收集不生效（F16）
);

-- 既有表加列（D12，照 trade_id 软引用先例：无 FK、不级联）
ALTER TABLE journal_entry ADD COLUMN project_id BIGINT;   -- RESEARCH_EVENT 必填，其余类型可空
ALTER TABLE wiki_entry    ADD COLUMN project_id BIGINT;

-- SOP 模板 seeding 幂等标记（P1 Task 5 消费）：照 seeded_at（CONCEPT 先例）另立一列，
-- 老用户已 seeded CONCEPT 不能复用同一行判定
ALTER TABLE wiki_seed_state ADD COLUMN sop_seeded_at TIMESTAMPTZ;
