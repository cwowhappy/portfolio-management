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
