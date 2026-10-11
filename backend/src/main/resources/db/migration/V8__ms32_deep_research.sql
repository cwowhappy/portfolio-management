-- ═════════ V8__ms32_deep_research.sql ═════════

-- MS-32 深度研究工作流研报域（设计规格 features/deep-research/02-设计规格 §二 原文 DDL）：
--   research_report_task        ：研报任务（四态 PENDING→RUNNING→SUCCEEDED/FAILED，§3.2；
--                                  部分唯一索引 uq_report_task_active 钉「同项目至多一个活跃任务」，需求 §三 F01）；
--   research_report            ：研报定稿（确认状态机 PENDING_CONFIRM→CONFIRMED/REJECTED，§3.10）；
--   research_report_anchor     ：数字锚点审计真相（创建后不可变，§3.8；state 三态 VERIFIED/SOURCED/UNVERIFIED）；
--   research_report_assumption ：关键假设（UNIQUE(report_id, seq)，anchor_id 可空 = 门未绑定，§3.5）；
--   report_token_quota         ：用户日 token 配额累计（(user_id, quota_date) 复合主键，§3.9 settle upsert）。

CREATE TABLE research_report_task (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT NOT NULL REFERENCES research_project(id),
    user_id       BIGINT NOT NULL REFERENCES app_user(id),
    status        TEXT NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    stage         TEXT NOT NULL DEFAULT 'COLLECTING'
                  CHECK (stage IN ('COLLECTING','ANALYZING','DRAFTING','VALIDATING')),
    token_usage   BIGINT NOT NULL DEFAULT 0,          -- ReportUsageHook 累计（结算入 quota）
    error_code    TEXT,                                -- QUOTA_TASK_CAP/TIMEOUT/VALIDATION_FAILED/PARSE_FAILED/AGENT_ERROR
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ
);
-- 同项目至多一个活跃任务（需求 §三 F01；「至多一个 active」部分唯一索引先例）
CREATE UNIQUE INDEX uq_report_task_active ON research_report_task (project_id)
    WHERE status IN ('PENDING','RUNNING');

CREATE TABLE research_report (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT NOT NULL REFERENCES research_project(id),
    task_id        BIGINT NOT NULL REFERENCES research_report_task(id),
    user_id        BIGINT NOT NULL REFERENCES app_user(id),
    status         TEXT NOT NULL DEFAULT 'PENDING_CONFIRM'
                   CHECK (status IN ('PENDING_CONFIRM','CONFIRMED','REJECTED')),
    title          TEXT NOT NULL,
    content_md     TEXT NOT NULL,                      -- 校验门修正后的定稿文本（含 ⚠ 校验修正注记行）
    limitations    TEXT,                               -- 数据局限注记（工具失败等）
    confirmed_at   TIMESTAMPTZ,
    rejected_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE research_report_anchor (
    id          BIGSERIAL PRIMARY KEY,
    report_id   BIGINT NOT NULL REFERENCES research_report(id),
    snippet     TEXT NOT NULL,                         -- 数字片段（MS-29 anchor 语义）
    occ         INT NOT NULL,                          -- 同 snippet 第几次出现
    state       TEXT NOT NULL CHECK (state IN ('VERIFIED','SOURCED','UNVERIFIED')),
    tool_name   TEXT,
    args        JSONB,
    as_of       TIMESTAMPTZ,
    as_of_kind  TEXT,                                  -- DATA/GENERATED/CALL（沿 ToolInvocation）
    raw         TEXT                                   -- 工具原值文本
);

CREATE TABLE research_report_assumption (
    id          BIGSERIAL PRIMARY KEY,
    report_id   BIGINT NOT NULL REFERENCES research_report(id),
    seq         INT NOT NULL,
    statement   TEXT NOT NULL,
    anchor_id   BIGINT REFERENCES research_report_anchor(id),  -- NULL = 未绑定（门语义见 §3.5）
    UNIQUE (report_id, seq)
);

CREATE TABLE report_token_quota (
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    quota_date  DATE NOT NULL,
    tokens_used BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, quota_date)
);
