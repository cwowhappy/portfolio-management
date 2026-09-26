-- 未上市策展与融资事件（MS-10，决策 #1/#3）：全局公共研究数据（读侧公开、写侧登录），
-- 无 user 归属——与 wiki_entry（用户私有）相反，供公开端点/全景卡无歧义消费。
-- DDL 照设计规格 §二 V19；种子为示例数据（企业名统一「示例××」前缀防与真实数据混淆），
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
    ('801080', '示例华芯科技',   '半导体设备', 'B',         '2026-06-15', 12.50, '半导体刻蚀设备新锐，国产替代主力', '示例种子（V19）'),
    ('801080', '示例纳微科技',   '模拟芯片',   'C_PLUS',    '2026-04-20', 31.00, '高性能模拟芯片设计商',             '示例种子（V19）'),
    ('801080', '示例光子科技',   '光模块',     'D',         '2026-02-10', 80.00, '800G 光模块批量出货中',            '示例种子（V19）'),
    ('801080', '示例芯盟科技',   '先进封装',   'STRATEGIC', '2025-11-05', 62.00, '2.5D 先进封装平台型厂商',          '示例种子（V19）'),
    ('801080', '示例未名科技',   'EDA 软件',   'UNKNOWN',   NULL,         NULL,  'EDA 工具链早期团队，融资信息未披露', '示例种子（V19）'),
    ('801150', '示例康源生物',   'Biotech',    'B',         '2026-07-01',  7.20, 'ADC 管线进入 II 期临床',           '示例种子（V19）'),
    ('801150', '示例瑞晶医药',   'CDMO',       'C_PLUS',    '2026-05-18', 24.50, '小分子 CDMO 产能扩张期',           '示例种子（V19）'),
    ('801150', '示例精准医疗科技', '基因测序',  'D',         '2026-01-22', 67.00, '肿瘤早筛产品已获证',               '示例种子（V19）'),
    ('801150', '示例佑宁制药',   '创新药',     'B_PLUS',    '2026-08-30', 18.30, '自免管线对外授权出海',             '示例种子（V19）'),
    ('801150', '示例安塞生物',   'CXO',        'A',         '2025-12-12',  3.30, '实验室自动化 CXO 服务商',          '示例种子（V19）');

-- 种子融资事件 20 条（两行业近 24 月分布；含 amount_yi=未披露、investors=未知、source_url=缺省的行；
-- 幂等键 event_date+company_name+round 无重复）。口径为公开源人工月度摘录（非全量，决策 #3）。
INSERT INTO industry_funding_event
    (event_date, company_name, round, amount_yi, investors, industry_code, segment, source_title, source_url)
VALUES
    ('2026-06-15', '示例华芯科技',     'B',         8.50,  '深创投、中芯聚源',   '801080', '半导体设备', '睿兽分析 2026-06 月报',    NULL),
    ('2025-09-10', '示例华芯科技',     'A',         3.20,  '中芯聚源',           '801080', '半导体设备', '企查查公开页人工摘录',     'https://example.com/seed/huaxin-a'),
    ('2025-06-30', '示例华芯科技',     'PRE_A',     0.80,  '个人天使',           '801080', '半导体设备', '创业邦公开报道',           NULL),
    ('2026-04-20', '示例纳微科技',     'C_PLUS',    25.00, '高瓴、红杉中国',     '801080', '模拟芯片',   '睿兽分析 2026-04 月报',    NULL),
    ('2024-11-15', '示例纳微科技',     'B',         6.00,  '红杉中国',           '801080', '模拟芯片',   '投资界公开报道',           'https://example.com/seed/nawei-b'),
    ('2026-02-10', '示例光子科技',     'D',         60.00, '国开金融、CPE',      '801080', '光模块',     '睿兽分析 2026-02 月报',    NULL),
    ('2024-12-03', '示例光子科技',     'C',         20.00, NULL,                 '801080', '光模块',     '36氪公开报道',             'https://example.com/seed/guangzi-c'),
    ('2025-11-05', '示例芯盟科技',     'STRATEGIC', 45.00, '产业方战投（未披露）', '801080', '先进封装',  'IT桔子公开页摘录',         NULL),
    ('2025-03-18', '示例芯盟科技',     'B_PLUS',    15.50, '启明创投',           '801080', '先进封装',   '投资界公开报道',           'https://example.com/seed/xinmeng-bplus'),
    ('2024-10-15', '示例芯盟科技',     'A',         1.50,  '深创投',             '801080', '先进封装',   '企查查公开页人工摘录',     NULL),
    ('2026-07-01', '示例康源生物',     'B',         5.20,  '礼来亚洲基金',       '801150', 'Biotech',    '睿兽分析 2026-07 月报',    NULL),
    ('2025-04-11', '示例康源生物',     'A',         2.00,  '泰福资本',           '801150', 'Biotech',    '投资界公开报道',           'https://example.com/seed/kangyuan-a'),
    ('2026-05-18', '示例瑞晶医药',     'C_PLUS',    18.80, '高瓴、夏尔巴投资',   '801150', 'CDMO',       '睿兽分析 2026-05 月报',    NULL),
    ('2025-02-26', '示例瑞晶医药',     'B',         4.50,  '夏尔巴投资',         '801150', 'CDMO',       '36氪公开报道',             NULL),
    ('2024-11-22', '示例瑞晶医药',     'A',         1.20,  '君联资本',           '801150', 'CDMO',       '投资界公开报道',           NULL),
    ('2026-01-22', '示例精准医疗科技', 'D',         52.00, '腾讯投资、CPE',      '801150', '基因测序',   '睿兽分析 2026-01 月报',    NULL),
    ('2024-10-30', '示例精准医疗科技', 'C',         15.00, 'IDG 资本',           '801150', '基因测序',   '投资界公开报道',           'https://example.com/seed/jingzhun-c'),
    ('2026-08-30', '示例佑宁制药',     'B_PLUS',    12.00, '高瓴、奥博资本',     '801150', '创新药',     '睿兽分析 2026-08 月报',    NULL),
    ('2025-08-19', '示例佑宁制药',     'B',         6.30,  '奥博资本',           '801150', '创新药',     'IT桔子公开页摘录',         NULL),
    ('2025-12-12', '示例安塞生物',     'A',         NULL,  '君联资本',           '801150', 'CXO',        '投资界公开报道（金额未披露）', NULL);
