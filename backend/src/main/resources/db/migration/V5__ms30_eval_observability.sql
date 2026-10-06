-- ═════════ V5__ms30_eval_observability.sql ═════════

-- MS-30 评测与可观测性加固（B1）：
-- ① eval_run：eval 运行留痕（告警判定以库表为准）——通过/失败/ERROR 计数、分类分布、提示词
--    全资产版本快照、题库 hash、告警状态；恒一基准由部分唯一索引钉死（全表只许一行 baseline=true）。
-- ② prompt_asset_version：提示词资产 append-only 版本链（系统提示词/工具描述/Skill/情报提示词/eval rubric）。
-- ③ tool_invocation_obs / turn_observation：对话旁路观测（工具调用 + 回轮时延/token）；user_id 弱引用
--    无 FK——用户删除随保留期自然过期，不触发 e2e-cleanup.sh DELETE 登记义务（C5②设计性规避）。
-- DDL 逐字落盘自 features/eval-observability/02-设计规格/设计规格说明.md §六；一次性迁移由
-- Flyway schema history 管控单次执行，未用 IF NOT EXISTS。

-- eval 运行留痕（告警留痕以库表为准）
CREATE TABLE eval_run (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    triggered_by     VARCHAR(16)  NOT NULL CHECK (triggered_by IN ('SCHEDULED','MANUAL')),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('RUNNING','COMPLETED','PARTIAL','FAILED')),
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    total_pass       INT NOT NULL DEFAULT 0,
    total_fail       INT NOT NULL DEFAULT 0,     -- 含 ERROR（收割口径）
    total_error      INT NOT NULL DEFAULT 0,     -- 其中框架异常数（单列留痕）
    by_category      JSONB NOT NULL DEFAULT '{}'::jsonb,
    prompt_versions  JSONB NOT NULL DEFAULT '{}'::jsonb,   -- 全资产版本快照
    question_bank_hash VARCHAR(64),
    alert_status     VARCHAR(16) NOT NULL DEFAULT 'NONE' CHECK (alert_status IN ('NONE','DEGRADED','RECOVERED')),
    baseline         BOOLEAN NOT NULL DEFAULT FALSE,
    baseline_candidate BOOLEAN NOT NULL DEFAULT FALSE,
    verdict_reasons  JSONB NOT NULL DEFAULT '[]'::jsonb,
    duration_ms      BIGINT,
    report_path      TEXT
);
CREATE INDEX idx_eval_run_started ON eval_run(started_at DESC);
CREATE UNIQUE INDEX idx_eval_run_single_baseline ON eval_run(baseline) WHERE baseline;  -- 恒一基准

-- 提示词版本登记（append-only 版本链）
CREATE TABLE prompt_asset_version (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_type    VARCHAR(16) NOT NULL CHECK (asset_type IN ('SYSTEM_PROMPT','TOOL_DESC','SKILL','INTEL_PROMPT','EVAL_RUBRIC')),
    asset_key     VARCHAR(128) NOT NULL,          -- 如 'tool.get_quote' / 'skill.tushare_data' / 'rubric.answer-quality'
    version       INT NOT NULL,
    content_hash  VARCHAR(64) NOT NULL,
    note          TEXT,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (asset_type, asset_key, version)
);
CREATE INDEX idx_prompt_asset_latest ON prompt_asset_version(asset_type, asset_key, version DESC);

-- 工具调用观测（user_id 弱引用无 FK：观测旁路数据，用户删除随保留期自然过期——避开 e2e-cleanup 登记义务）
CREATE TABLE tool_invocation_obs (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT,
    conversation_id VARCHAR(64),
    message_id     VARCHAR(64),
    tool_name      VARCHAR(64) NOT NULL,
    args           JSONB,
    result_text    TEXT,                    -- 截断后
    spec_count     INT NOT NULL DEFAULT 0,
    as_of          VARCHAR(32),
    as_of_kind     VARCHAR(16),
    mcp            BOOLEAN NOT NULL DEFAULT FALSE,
    failed         BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms    BIGINT,
    called_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tool_obs_called ON tool_invocation_obs(called_at);
CREATE INDEX idx_tool_obs_conversation ON tool_invocation_obs(conversation_id);

-- 对话轮观测（同上弱引用）
CREATE TABLE turn_observation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT,
    conversation_id VARCHAR(64),
    message_id      VARCHAR(64),
    prompt_tokens   INT,
    completion_tokens INT,
    total_tokens    INT,
    duration_ms     BIGINT,
    tool_count      INT,
    failed          BOOLEAN NOT NULL DEFAULT FALSE,
    error_summary   TEXT,
    trust_stats     JSONB,                  -- anchor/verified/sourced/corrections 子集
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_turn_obs_created ON turn_observation(created_at);
CREATE INDEX idx_turn_obs_conversation ON turn_observation(conversation_id);
