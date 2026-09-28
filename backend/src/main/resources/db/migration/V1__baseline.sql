-- ═══════════════════════════════════════════════════════════════════════
-- V1 基线（v1 首次发布 squash，2026-09-28）
-- 由原 V1~V21 共 22 个迁移（含点版本 V19.1）按序合并而来；原文件见 git 历史
-- （分支 refactor/v1-flyway-baseline）。合并细则见 docs/deployment/v1/发布计划.md A1：
--   * 原 V3 的 treasury_yield 建表与 V4 的数据迁移+DROP 消除（终态本就不存在该表）；
--   * 原 V19 固定日期种子与 V19.1 相对日期补丁合并为 CURRENT_DATE 相对偏移 INSERT
--     （锚点 2026-09-26，行间间隔与原 V19.1 语义一致）；
--   * 其余语句原序保留。
-- 验收基准：空库执行结果与原迁移链逐表逐行一致（两库 pg_dump diff 为空）。
-- 后续新增迁移从 V2 开始；版本契约见 FlywayMigrationIntegrationTest（断言 ["1"]）。
-- ═══════════════════════════════════════════════════════════════════════

-- ═════════ 原 V1__init.sql ═════════

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE persistent_logins (
    series    VARCHAR(64) PRIMARY KEY,
    username  VARCHAR(64) NOT NULL,
    token     VARCHAR(64) NOT NULL,
    last_used TIMESTAMP   NOT NULL
);

-- ═════════ 原 V2__conversation.sql ═════════

CREATE TABLE conversation (
    id         VARCHAR(64) PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    title      VARCHAR(64) NOT NULL DEFAULT '新会话',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_user ON conversation(user_id);

CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    message_id      VARCHAR(64) NOT NULL,
    role            VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    payload         JSONB NULL,
    created_at      BIGINT NOT NULL
);
CREATE INDEX idx_chat_message_conversation ON chat_message(conversation_id);

-- ═════════ 原 V3__valuation.sql ═════════

-- 估值数据底座：全市场快照 / 行业估值 / 国债收益率 / 指数估值历史 / 申万行业映射
-- 本 schema 为 Python 采集服务（P3）写入的跨服务契约，表名与列类型不可随意变更。

CREATE TABLE valuation_snapshot (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL UNIQUE,
    pe_median NUMERIC(12,4) NOT NULL,
    pb_median NUMERIC(12,4) NOT NULL,
    net_breaker_count INTEGER NOT NULL,
    net_breaker_ratio NUMERIC(8,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE industry_valuation (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    industry_code VARCHAR(16) NOT NULL,
    industry_name VARCHAR(64) NOT NULL,
    pe NUMERIC(12,4),
    pb NUMERIC(12,4),
    roe NUMERIC(12,4),
    dividend_yield NUMERIC(12,4),
    UNIQUE (trading_day, industry_code)
);


CREATE TABLE index_valuation_history (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    index_code VARCHAR(16) NOT NULL,
    index_name VARCHAR(64) NOT NULL,
    pe NUMERIC(12,4),
    pb NUMERIC(12,4),
    dividend_yield NUMERIC(12,4),
    UNIQUE (trading_day, index_code)
);

CREATE TABLE shenwan_industry_mapping (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(16) NOT NULL UNIQUE,
    stock_name VARCHAR(64) NOT NULL,
    industry_code VARCHAR(16) NOT NULL,
    industry_name VARCHAR(64) NOT NULL
);

CREATE INDEX idx_industry_valuation_day ON industry_valuation (trading_day);
CREATE INDEX idx_index_valuation_code ON index_valuation_history (index_code, trading_day);
CREATE INDEX idx_shenwan_industry_code ON shenwan_industry_mapping (industry_code);

-- ═════════ 原 V4__valuation_curve.sql ═════════

-- 国债收益率曲线（多期限）+ 指数成分股（基线合并：旧 treasury_yield 表自始未建，无迁移）。
-- 本 schema 为 Python 采集服务（collector）写入的跨服务契约，表名与列类型不可随意变更。

CREATE TABLE treasury_yield_curve (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    term VARCHAR(16) NOT NULL,          -- '1Y','3Y','5Y','10Y','30Y'
    yield NUMERIC(8,4) NOT NULL,
    UNIQUE (trading_day, term)
);

CREATE TABLE index_constituent (
    id BIGSERIAL PRIMARY KEY,
    index_code VARCHAR(16) NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64),
    weight NUMERIC(12,6),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (index_code, stock_code)
);

CREATE INDEX idx_treasury_yield_curve_term ON treasury_yield_curve (term, trading_day);
CREATE INDEX idx_index_constituent_stock ON index_constituent (stock_code);

-- ═════════ 原 V5__portfolio.sql ═════════

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

-- ═════════ 原 V6__allocation.sql ═════════

-- 资产配置：方案 + 方案权重
-- 本 schema 为配置域跨服务契约，表名与列类型不可随意变更。

CREATE TABLE allocation_plan (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    name       VARCHAR(64) NOT NULL,
    source     VARCHAR(16) NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_allocation_plan_user ON allocation_plan(user_id);

CREATE TABLE allocation_plan_weight (
    id          BIGSERIAL PRIMARY KEY,
    plan_id     BIGINT NOT NULL REFERENCES allocation_plan(id) ON DELETE CASCADE,
    asset_class VARCHAR(16) NOT NULL,
    weight      NUMERIC(18,4) NOT NULL,
    UNIQUE (plan_id, asset_class)
);
CREATE INDEX idx_allocation_plan_weight_plan ON allocation_plan_weight(plan_id);

-- 每用户至多一个生效方案：并发激活时由 DB 兜底（违反则 DataIntegrityViolation → 400）
CREATE UNIQUE INDEX ux_allocation_plan_user_active ON allocation_plan(user_id) WHERE active;

-- ═════════ 原 V7__stock_fundamental.sql ═════════

-- 个股基本面数据底座：估值日快照 / 财务指标季数据
-- 本 schema 为 Python 采集服务（collector）写入的跨服务契约，表名与列类型不可随意变更。
-- total_mv/circ_mv 单位：元（collector 由 tushare 万元换算）；dividend_yield 为 dv_ttm 口径。

CREATE TABLE stock_valuation_daily (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NOT NULL,
    pe_ttm NUMERIC(12,4),
    pb NUMERIC(12,4),
    dividend_yield NUMERIC(12,4),
    total_mv NUMERIC(20,2),
    circ_mv NUMERIC(20,2),
    turnover_rate NUMERIC(12,4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trading_day, stock_code)
);

CREATE TABLE stock_financial (
    id BIGSERIAL PRIMARY KEY,
    report_date DATE NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    roe NUMERIC(12,4),
    roa NUMERIC(12,4),
    gross_margin NUMERIC(12,4),
    debt_to_assets NUMERIC(12,4),
    current_ratio NUMERIC(12,4),
    revenue_yoy NUMERIC(12,4),
    netprofit_yoy NUMERIC(12,4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (report_date, stock_code)
);

CREATE INDEX idx_stock_valuation_daily_day ON stock_valuation_daily (trading_day);
CREATE INDEX idx_stock_valuation_daily_code ON stock_valuation_daily (stock_code);
CREATE INDEX idx_stock_financial_code ON stock_financial (stock_code, report_date);

-- ═════════ 原 V8__journal.sql ═════════

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

-- ═════════ 原 V9__optimistic_locking.sql ═════════

-- 钱账聚合乐观锁：为 5 个聚合对应表增加 version 列（默认 0，NOT NULL），
-- JPA @Version 以「读-改-整行 merge 写」时校验版本，防并发丢更新。

ALTER TABLE portfolio        ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE holding_group    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE position         ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE allocation_plan  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE journal_entry    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- ═════════ 原 V10__mcp.sql ═════════

-- MCP 数据源接入：内置 provider + endpoint 两级目录 + 用户配置
CREATE TABLE mcp_provider (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32) NOT NULL UNIQUE,
    name            VARCHAR(64) NOT NULL,
    auth_type       VARCHAR(16) NOT NULL,
    auth_header     VARCHAR(64),
    auth_secret_enc TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE mcp_endpoint (
    id          BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES mcp_provider(id),
    domain      VARCHAR(32),
    name        VARCHAR(64) NOT NULL,
    url         VARCHAR(512) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mcp_endpoint_provider ON mcp_endpoint(provider_id);

CREATE TABLE mcp_user_config (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    provider_id     BIGINT NOT NULL REFERENCES mcp_provider(id),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_tools  JSONB NOT NULL DEFAULT '[]',
    config_version  INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, provider_id)
);
CREATE INDEX idx_mcp_user_config_user ON mcp_user_config(user_id);

-- seed provider：auth_secret_enc 留 NULL（真实 token 由部署脚本填，绝不进 git）
INSERT INTO mcp_provider (code, name, auth_type, auth_header, auth_secret_enc, remark) VALUES
    ('mx-ds',   '东方财富妙想', 'HEADER', 'em_api_key', NULL, 'A股/港股/美股/债券/基金/指数/宏观/公告'),
    ('tushare', 'Tushare 官方', 'BEARER', NULL, NULL, 'A股/港股/美股行情、财务、宏观、债券、基金、期货、指数'),
    ('wind',    'Wind AIFin',  'BEARER', NULL, NULL, 'A股/港美股行情、财务、基金、债券、指数、宏观');

INSERT INTO mcp_endpoint (provider_id, domain, name, url) VALUES
    (1, NULL, '妙想数据', 'https://mxapi.eastmoney.com/mxds/mcp'),
    (2, NULL, 'Tushare 数据', 'https://api.tushare.pro/mcp/'),
    (3, 'stock',     'Wind 股票', 'https://mcp.wind.com.cn/vserver_stock_data/mcp/'),
    (3, 'fund',      'Wind 基金', 'https://mcp.wind.com.cn/vserver_fund_data/mcp/'),
    (3, 'index',     'Wind 指数', 'https://mcp.wind.com.cn/vserver_index_data/mcp/'),
    (3, 'bond',      'Wind 债券', 'https://mcp.wind.com.cn/vserver_bond_data/mcp/'),
    (3, 'economic',  'Wind 宏观', 'https://mcp.wind.com.cn/vserver_economic_data/mcp/'),
    (3, 'analytics', 'Wind 分析', 'https://mcp.wind.com.cn/vserver_analytics_data/mcp/');

-- ═════════ 原 V11__skill.sql ═════════

-- 系统内置 Skill：skill 目录在 classpath（resources/skills/**/SKILL.md），DB 只存用户选择
CREATE TABLE skill_user_config (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    skill_code  VARCHAR(64) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, skill_code)
);
CREATE INDEX idx_skill_user_config_user ON skill_user_config(user_id);

-- ═════════ 原 V12__risk_assessment.sql ═════════

-- 风险测评：每用户仅存最新一条（user_id 唯一，重测由仓储 upsert 覆盖）
CREATE TABLE risk_assessment (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES app_user(id),
    total_score INT NOT NULL,
    profile     VARCHAR(16) NOT NULL,
    answers     JSONB NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═════════ 原 V13__analytics_close.sql ═════════

-- 收益分析收盘价数据底座（MS-07）：本 schema 为 collector 写入、后端读取的跨服务契约。
-- ① stock_valuation_daily 加收盘价列：tushare daily_basic 同源字段，加列向后兼容（老行 NULL，读侧 forward-fill）。
-- ② index_close_history：基准指数收盘价历史（沪深300/中证500/中证偏股基金指数）。

ALTER TABLE stock_valuation_daily ADD COLUMN close NUMERIC(12,4);

CREATE TABLE index_close_history (
    id BIGSERIAL PRIMARY KEY,
    trading_day DATE NOT NULL,
    index_code VARCHAR(16) NOT NULL,
    index_name VARCHAR(64) NOT NULL,
    close NUMERIC(12,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trading_day, index_code)
);

CREATE INDEX idx_index_close_history_code ON index_close_history (index_code);

-- ═════════ 原 V14__rebalance_watchlist.sql ═════════

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

-- ═════════ 原 V15__stock_financial_revenue.sql ═════════

-- V15__stock_financial_revenue.sql
-- MS-09 行业研究·上市公司深化：个股财务季数据补营收绝对额（F03 行业内营收排名维度）。
-- 本 schema 为 Python 采集服务（collector）写入的跨服务契约：加列可空、纯增量，不改既有列
-- （V7 契约注释口径：表名与列类型不可随意变更）。
-- revenue：报告期累计营业收入，单位元；collector 由 tushare income.revenue 换算写入（单位见采集侧调研报告）。
ALTER TABLE stock_financial ADD COLUMN revenue NUMERIC(20,4);

-- ═════════ 原 V16__wiki.sql ═════════

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

-- ═════════ 原 V17__industry_watch.sql ═════════

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

-- ═════════ 原 V18__etf_screening.sql ═════════

-- ETF 筛选基础表（MS-14 P3，Task 13）：collector etf_basic 周更任务写入
-- 目录/基金名/费率（管理+托管合计年化%）/规模（亿元）/跟踪指数/类别。
-- tracking_error_1y 由 Task 15 误差任务单独回写（本迁移只建列不写入）。
-- DDL 照设计规格说明 §3.1；源口径见 09-调研报告/2026-09-26-ETF数据源探测.md §七
-- （枚举=新浪 fund_etf_category_sina 目录 1685 只，enrich=天天基金移动端 Detail 端点逐只）。

CREATE TABLE etf_basic (
    fund_code             VARCHAR(16) PRIMARY KEY,   -- 6 位无后缀，与 index_close_history.index_code 同格式
    fund_name             VARCHAR(64) NOT NULL,
    fee_rate              NUMERIC(8,4),              -- 管理费+托管费合计（年化%），null=未知
    scale                 NUMERIC(18,4),             -- 亿元，null=未知
    tracking_index_code   VARCHAR(16),               -- 跟踪指数 6 位码，null=未知
    tracking_index_name   VARCHAR(64),
    category              VARCHAR(32) NOT NULL,      -- 宽基/行业/商品/债券/QDII/其他
    tracking_error_1y     NUMERIC(10,6),             -- 近1年日频差值 std×√252，null=样本不足
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═════════ 原 V19__industry_unlisted.sql ═════════

-- 未上市策展与融资事件（MS-10，决策 #1/#3）：全局公共研究数据（读侧公开、写侧登录），
-- 无 user 归属——与 wiki_entry（用户私有）相反，供公开端点/全景卡无歧义消费。
-- DDL 照设计规格 §二 V19（基线合并 V19+V19.1：种子日期取 CURRENT_DATE 相对偏移，
-- 锚点 2026-09-26、行间间隔不变）；种子为示例数据（企业名统一「示例××」前缀防与真实数据混淆），
-- 供 e2e 断言与首启演示，真实首批名单走 CSV 导入装载（决策 #5b）。

CREATE TABLE industry_unlisted_company (
    id                 BIGSERIAL PRIMARY KEY,
    industry_code      VARCHAR(16) NOT NULL,          -- 申万一级，如 801080
    company_name       VARCHAR(128) NOT NULL,
    segment            VARCHAR(64),                   -- 细分赛道文本标签（如 动力电池/CDMO）
    latest_round       VARCHAR(16) NOT NULL,          -- FundingRound 枚举
    last_funding_date  DATE,
    total_funding_yi   NUMERIC(14,2),                 -- 累计融资额（亿元），null=未知
    summary            VARCHAR(256),                  -- 一句话简介
    source_note        VARCHAR(128),                  -- 来源标注（如 爱企查人工核对/LLM草稿已核）
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (industry_code, company_name)              -- CSV upsert 幂等键
);
CREATE INDEX idx_unlisted_company_industry ON industry_unlisted_company(industry_code, last_funding_date DESC);

CREATE TABLE industry_funding_event (
    id             BIGSERIAL PRIMARY KEY,
    event_date     DATE NOT NULL,
    company_name   VARCHAR(128) NOT NULL,
    round          VARCHAR(16) NOT NULL,              -- FundingRound 枚举
    amount_yi      NUMERIC(14,2),                     -- null=未披露
    investors      VARCHAR(256),                      -- 投资方（顿号分隔），null=未知
    industry_code  VARCHAR(16) NOT NULL,
    segment        VARCHAR(64),
    source_title   VARCHAR(256) NOT NULL,             -- 来源标题
    source_url     VARCHAR(512),                      -- 来源 URL（可缺省——月报类无逐条 URL）
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_date, company_name, round)          -- CSV upsert 幂等键（同日同企同轮视为同一事件）
);
CREATE INDEX idx_funding_event_industry ON industry_funding_event(industry_code, event_date DESC);

-- 种子策展企业 10 家（电子 801080 ×5、医药生物 801150 ×5；轮次覆盖 B/B+/C+/D/战略投资/A/未知，
-- 示例未名科技轮次未知且无融资日期）。last_funding_date/latest_round 与下方融资事件种子口径一致。
INSERT INTO industry_unlisted_company
    (industry_code, company_name, segment, latest_round, last_funding_date, total_funding_yi, summary, source_note)
VALUES
    ('801080', '示例华芯科技',   '半导体设备', 'B',         (CURRENT_DATE - INTERVAL '103 days'), 12.50, '半导体刻蚀设备新锐，国产替代主力', '示例种子（V19）'),
    ('801080', '示例纳微科技',   '模拟芯片',   'C_PLUS',    (CURRENT_DATE - INTERVAL '159 days'), 31.00, '高性能模拟芯片设计商',             '示例种子（V19）'),
    ('801080', '示例光子科技',   '光模块',     'D',         (CURRENT_DATE - INTERVAL '228 days'), 80.00, '800G 光模块批量出货中',            '示例种子（V19）'),
    ('801080', '示例芯盟科技',   '先进封装',   'STRATEGIC', (CURRENT_DATE - INTERVAL '325 days'), 62.00, '2.5D 先进封装平台型厂商',          '示例种子（V19）'),
    ('801080', '示例未名科技',   'EDA 软件',   'UNKNOWN',   NULL,         NULL,  'EDA 工具链早期团队，融资信息未披露', '示例种子（V19）'),
    ('801150', '示例康源生物',   'Biotech',    'B',         (CURRENT_DATE - INTERVAL '87 days'),  7.20, 'ADC 管线进入 II 期临床',           '示例种子（V19）'),
    ('801150', '示例瑞晶医药',   'CDMO',       'C_PLUS',    (CURRENT_DATE - INTERVAL '131 days'), 24.50, '小分子 CDMO 产能扩张期',           '示例种子（V19）'),
    ('801150', '示例精准医疗科技', '基因测序',  'D',         (CURRENT_DATE - INTERVAL '247 days'), 67.00, '肿瘤早筛产品已获证',               '示例种子（V19）'),
    ('801150', '示例佑宁制药',   '创新药',     'B_PLUS',    (CURRENT_DATE - INTERVAL '27 days'), 18.30, '自免管线对外授权出海',             '示例种子（V19）'),
    ('801150', '示例安塞生物',   'CXO',        'A',         (CURRENT_DATE - INTERVAL '288 days'),  3.30, '实验室自动化 CXO 服务商',          '示例种子（V19）');

-- 种子融资事件 20 条（两行业近 24 月分布；含 amount_yi=未披露、investors=未知、source_url=缺省的行；
-- 幂等键 event_date+company_name+round 无重复）。口径为公开源人工月度摘录（非全量，决策 #3）。
INSERT INTO industry_funding_event
    (event_date, company_name, round, amount_yi, investors, industry_code, segment, source_title, source_url)
VALUES
    ((CURRENT_DATE - INTERVAL '103 days'), '示例华芯科技',     'B',         8.50,  '深创投、中芯聚源',   '801080', '半导体设备', '睿兽分析 2026-06 月报',    NULL),
    ((CURRENT_DATE - INTERVAL '381 days'), '示例华芯科技',     'A',         3.20,  '中芯聚源',           '801080', '半导体设备', '企查查公开页人工摘录',     'https://example.com/seed/huaxin-a'),
    ((CURRENT_DATE - INTERVAL '453 days'), '示例华芯科技',     'PRE_A',     0.80,  '个人天使',           '801080', '半导体设备', '创业邦公开报道',           NULL),
    ((CURRENT_DATE - INTERVAL '159 days'), '示例纳微科技',     'C_PLUS',    25.00, '高瓴、红杉中国',     '801080', '模拟芯片',   '睿兽分析 2026-04 月报',    NULL),
    ((CURRENT_DATE - INTERVAL '680 days'), '示例纳微科技',     'B',         6.00,  '红杉中国',           '801080', '模拟芯片',   '投资界公开报道',           'https://example.com/seed/nawei-b'),
    ((CURRENT_DATE - INTERVAL '228 days'), '示例光子科技',     'D',         60.00, '国开金融、CPE',      '801080', '光模块',     '睿兽分析 2026-02 月报',    NULL),
    ((CURRENT_DATE - INTERVAL '662 days'), '示例光子科技',     'C',         20.00, NULL,                 '801080', '光模块',     '36氪公开报道',             'https://example.com/seed/guangzi-c'),
    ((CURRENT_DATE - INTERVAL '325 days'), '示例芯盟科技',     'STRATEGIC', 45.00, '产业方战投（未披露）', '801080', '先进封装',  'IT桔子公开页摘录',         NULL),
    ((CURRENT_DATE - INTERVAL '557 days'), '示例芯盟科技',     'B_PLUS',    15.50, '启明创投',           '801080', '先进封装',   '投资界公开报道',           'https://example.com/seed/xinmeng-bplus'),
    ((CURRENT_DATE - INTERVAL '711 days'), '示例芯盟科技',     'A',         1.50,  '深创投',             '801080', '先进封装',   '企查查公开页人工摘录',     NULL),
    ((CURRENT_DATE - INTERVAL '87 days'), '示例康源生物',     'B',         5.20,  '礼来亚洲基金',       '801150', 'Biotech',    '睿兽分析 2026-07 月报',    NULL),
    ((CURRENT_DATE - INTERVAL '533 days'), '示例康源生物',     'A',         2.00,  '泰福资本',           '801150', 'Biotech',    '投资界公开报道',           'https://example.com/seed/kangyuan-a'),
    ((CURRENT_DATE - INTERVAL '131 days'), '示例瑞晶医药',     'C_PLUS',    18.80, '高瓴、夏尔巴投资',   '801150', 'CDMO',       '睿兽分析 2026-05 月报',    NULL),
    ((CURRENT_DATE - INTERVAL '577 days'), '示例瑞晶医药',     'B',         4.50,  '夏尔巴投资',         '801150', 'CDMO',       '36氪公开报道',             NULL),
    ((CURRENT_DATE - INTERVAL '673 days'), '示例瑞晶医药',     'A',         1.20,  '君联资本',           '801150', 'CDMO',       '投资界公开报道',           NULL),
    ((CURRENT_DATE - INTERVAL '247 days'), '示例精准医疗科技', 'D',         52.00, '腾讯投资、CPE',      '801150', '基因测序',   '睿兽分析 2026-01 月报',    NULL),
    ((CURRENT_DATE - INTERVAL '696 days'), '示例精准医疗科技', 'C',         15.00, 'IDG 资本',           '801150', '基因测序',   '投资界公开报道',           'https://example.com/seed/jingzhun-c'),
    ((CURRENT_DATE - INTERVAL '27 days'), '示例佑宁制药',     'B_PLUS',    12.00, '高瓴、奥博资本',     '801150', '创新药',     '睿兽分析 2026-08 月报',    NULL),
    ((CURRENT_DATE - INTERVAL '403 days'), '示例佑宁制药',     'B',         6.30,  '奥博资本',           '801150', '创新药',     'IT桔子公开页摘录',         NULL),
    ((CURRENT_DATE - INTERVAL '288 days'), '示例安塞生物',     'A',         NULL,  '君联资本',           '801150', 'CXO',        '投资界公开报道（金额未披露）', NULL);

-- ═════════ 原 V20__industry_chain.sql ═════════

-- 产业链图谱（MS-10 F11，决策 #6）：链/环节/成员三级；行业关联不建链表——由成员派生
-- （上市成员经 shenwan_industry_mapping、未上市成员经 industry_unlisted_company.industry_code）。
-- DDL 照设计规格 §二 V20；种子 2 条示例链，供 e2e 断言与首启演示（决策 #7 迁移 INSERT）。
-- 成员行业归属即链-行业派生口径：上市成员 stock_code 不加 FK（行情映射为跨服务数据面），
-- 未上市成员经 unlisted_company_id 引 V19 种子行（子查询定位，防 V19.1 改日期后 id 漂移）。

CREATE TABLE industry_chain (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64) NOT NULL UNIQUE,          -- 如 锂电池
    description VARCHAR(256)
);
CREATE TABLE industry_chain_stage (
    id         BIGSERIAL PRIMARY KEY,
    chain_id   BIGINT NOT NULL REFERENCES industry_chain(id) ON DELETE CASCADE,
    tier       VARCHAR(16) NOT NULL,                  -- UPSTREAM/MIDSTREAM/DOWNSTREAM 枚举
    name       VARCHAR(64) NOT NULL,                  -- 环节名（如 锂矿/正极材料/电芯/整车）
    sort_order INT NOT NULL DEFAULT 0,                -- 同 tier 内排序
    UNIQUE (chain_id, tier, name)
);
CREATE TABLE industry_chain_member (
    id                  BIGSERIAL PRIMARY KEY,
    stage_id            BIGINT NOT NULL REFERENCES industry_chain_stage(id) ON DELETE CASCADE,
    member_type         VARCHAR(8) NOT NULL,          -- LISTED/UNLISTED
    stock_code          VARCHAR(16),                  -- LISTED 必填（挂行情台跳转）
    unlisted_company_id BIGINT REFERENCES industry_unlisted_company(id) ON DELETE SET NULL,
    display_name        VARCHAR(128) NOT NULL,        -- 成员展示名快照（未上市取策展名/上市取证券名）
    CHECK ( (member_type = 'LISTED' AND stock_code IS NOT NULL)
         OR (member_type = 'UNLISTED' AND unlisted_company_id IS NOT NULL) )
);
CREATE INDEX idx_chain_member_stage ON industry_chain_member(stage_id);

-- 种子链 1：锂电池（上游×2 环节/中游/下游，成员全上市挂真实 A 股代码——行业归属由
-- shenwan_industry_mapping 派生：电力设备 801730（宁德时代/亿纬锂能/恩捷股份/杉杉股份）、
-- 有色金属 801050（赣锋锂业/天齐锂业）、汽车 801880（长安汽车/赛力斯））。
INSERT INTO industry_chain (id, name, description) VALUES
    (1, '锂电池', '动力电池全产业链：上游锂矿与材料、中游电芯制造、下游新能源整车');
INSERT INTO industry_chain_stage (chain_id, tier, name, sort_order) VALUES
    (1, 'UPSTREAM',   '锂矿',     1),
    (1, 'UPSTREAM',   '锂电材料', 2),
    (1, 'MIDSTREAM',  '电芯',     1),
    (1, 'DOWNSTREAM', '整车',     1);
INSERT INTO industry_chain_member (stage_id, member_type, stock_code, unlisted_company_id, display_name) VALUES
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 1 AND name = '锂矿'),     'LISTED', '002460', NULL, '赣锋锂业'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 1 AND name = '锂矿'),     'LISTED', '002466', NULL, '天齐锂业'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 1 AND name = '锂电材料'), 'LISTED', '002812', NULL, '恩捷股份'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 1 AND name = '锂电材料'), 'LISTED', '600884', NULL, '杉杉股份'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 1 AND name = '电芯'),     'LISTED', '300750', NULL, '宁德时代'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 1 AND name = '电芯'),     'LISTED', '300014', NULL, '亿纬锂能'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 1 AND name = '整车'),     'LISTED', '000625', NULL, '长安汽车'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 1 AND name = '整车'),     'LISTED', '601127', NULL, '赛力斯');

-- 种子链 2：创新药（CXO/Biotech/制药三环节；未上市成员挂 V19 种子策展企业 id——
-- 其 industry_code=801150 医药生物，与上市成员行业一致，链派生归属无歧义）。
INSERT INTO industry_chain (id, name, description) VALUES
    (2, '创新药', '创新药研发生产链：上游 CXO 研发生产服务、中游 Biotech 临床管线、下游商业化制药');
INSERT INTO industry_chain_stage (chain_id, tier, name, sort_order) VALUES
    (2, 'UPSTREAM',   'CXO',    1),
    (2, 'MIDSTREAM',  'Biotech', 1),
    (2, 'DOWNSTREAM', '制药',   1);
INSERT INTO industry_chain_member (stage_id, member_type, stock_code, unlisted_company_id, display_name) VALUES
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 2 AND name = 'CXO'), 'LISTED', '603259', NULL, '药明康德'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 2 AND name = 'CXO'), 'LISTED', '688202', NULL, '美迪西'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 2 AND name = 'CXO'), 'UNLISTED', NULL,
        (SELECT id FROM industry_unlisted_company WHERE company_name = '示例安塞生物'), '示例安塞生物'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 2 AND name = 'Biotech'), 'UNLISTED', NULL,
        (SELECT id FROM industry_unlisted_company WHERE company_name = '示例康源生物'), '示例康源生物'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 2 AND name = 'Biotech'), 'LISTED', '300122', NULL, '智飞生物'),
    ((SELECT id FROM industry_chain_stage WHERE chain_id = 2 AND name = '制药'), 'LISTED', '600276', NULL, '恒瑞医药');

-- 显式 id 插入不推进 BIGSERIAL 序列：三表序列拨到当前 max，防应用层首插撞键。
SELECT setval('industry_chain_id_seq',        (SELECT COALESCE(max(id), 1) FROM industry_chain));
SELECT setval('industry_chain_stage_id_seq',  (SELECT COALESCE(max(id), 1) FROM industry_chain_stage));
SELECT setval('industry_chain_member_id_seq', (SELECT COALESCE(max(id), 1) FROM industry_chain_member));

-- ═════════ 原 V21__account_email.sql ═════════

-- M01-F06 邮箱验证与找回密码：用户表邮箱列 + 验证码表
ALTER TABLE app_user ADD COLUMN email VARCHAR(254);
ALTER TABLE app_user ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
-- 部分唯一索引：存量/管理员行 email 为 NULL 不受约束
CREATE UNIQUE INDEX uk_app_user_email ON app_user(email) WHERE email IS NOT NULL;

CREATE TABLE verification_code (
    id         BIGSERIAL PRIMARY KEY,
    email      VARCHAR(254) NOT NULL,
    purpose    VARCHAR(16)  NOT NULL,
    code_hash  VARCHAR(100) NOT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    used_at    TIMESTAMPTZ,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_verification_code_email ON verification_code(email, purpose);
