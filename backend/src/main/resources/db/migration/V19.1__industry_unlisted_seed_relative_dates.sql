-- V19.1：示例种子行日期改为 CURRENT_DATE 相对偏移（MS-10 P1 评审 WARN-1）。
-- V19 原文已在 dev 库跑过、不可原地改 checksum，故以点版本补丁迁移（Flyway 版本 "19.1" 合法，
-- 语义是 V19 的修正）。目的：V19 种子日期是 2026 年固定值，随时间推移会滑出读侧
-- 「近 12 月 / 近 24 月」窗口导致 e2e 与集成断言过期；本迁移把两表 LIKE '示例%' 种子行
-- （未名科技保持 NULL）的日期改为 (CURRENT_DATE - INTERVAL 'N days')，N 为原日期相对
-- 锚点 2026-09-26 的天数——行间相对间隔保持不变（企业 last_funding_date 与对应事件
-- event_date 同偏移，口径一致）。WHERE 同时锁定原固定日期+企业名+轮次/行业码，
-- 确保只动 V19 种子行、永不触及真实导入数据。

-- 策展企业 10 家（未名科技无融资日期保持 NULL，不 UPDATE）
UPDATE industry_unlisted_company
SET last_funding_date = CURRENT_DATE - INTERVAL '103 days'  -- 原 2026-06-15
WHERE industry_code = '801080' AND company_name = '示例华芯科技' AND last_funding_date = DATE '2026-06-15';

UPDATE industry_unlisted_company
SET last_funding_date = CURRENT_DATE - INTERVAL '159 days'  -- 原 2026-04-20
WHERE industry_code = '801080' AND company_name = '示例纳微科技' AND last_funding_date = DATE '2026-04-20';

UPDATE industry_unlisted_company
SET last_funding_date = CURRENT_DATE - INTERVAL '228 days'  -- 原 2026-02-10
WHERE industry_code = '801080' AND company_name = '示例光子科技' AND last_funding_date = DATE '2026-02-10';

UPDATE industry_unlisted_company
SET last_funding_date = CURRENT_DATE - INTERVAL '325 days'  -- 原 2025-11-05
WHERE industry_code = '801080' AND company_name = '示例芯盟科技' AND last_funding_date = DATE '2025-11-05';

UPDATE industry_unlisted_company
SET last_funding_date = CURRENT_DATE - INTERVAL '87 days'   -- 原 2026-07-01
WHERE industry_code = '801150' AND company_name = '示例康源生物' AND last_funding_date = DATE '2026-07-01';

UPDATE industry_unlisted_company
SET last_funding_date = CURRENT_DATE - INTERVAL '131 days'  -- 原 2026-05-18
WHERE industry_code = '801150' AND company_name = '示例瑞晶医药' AND last_funding_date = DATE '2026-05-18';

UPDATE industry_unlisted_company
SET last_funding_date = CURRENT_DATE - INTERVAL '247 days'  -- 原 2026-01-22
WHERE industry_code = '801150' AND company_name = '示例精准医疗科技' AND last_funding_date = DATE '2026-01-22';

UPDATE industry_unlisted_company
SET last_funding_date = CURRENT_DATE - INTERVAL '27 days'   -- 原 2026-08-30
WHERE industry_code = '801150' AND company_name = '示例佑宁制药' AND last_funding_date = DATE '2026-08-30';

UPDATE industry_unlisted_company
SET last_funding_date = CURRENT_DATE - INTERVAL '288 days'  -- 原 2025-12-12
WHERE industry_code = '801150' AND company_name = '示例安塞生物' AND last_funding_date = DATE '2025-12-12';

-- 融资事件 20 条（幂等键 event_date+company_name+round 原值锁定）
UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '103 days'  -- 原 2026-06-15
WHERE event_date = DATE '2026-06-15' AND company_name = '示例华芯科技' AND round = 'B';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '381 days'  -- 原 2025-09-10
WHERE event_date = DATE '2025-09-10' AND company_name = '示例华芯科技' AND round = 'A';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '453 days'  -- 原 2025-06-30
WHERE event_date = DATE '2025-06-30' AND company_name = '示例华芯科技' AND round = 'PRE_A';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '159 days'  -- 原 2026-04-20
WHERE event_date = DATE '2026-04-20' AND company_name = '示例纳微科技' AND round = 'C_PLUS';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '680 days'  -- 原 2024-11-15
WHERE event_date = DATE '2024-11-15' AND company_name = '示例纳微科技' AND round = 'B';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '228 days'  -- 原 2026-02-10
WHERE event_date = DATE '2026-02-10' AND company_name = '示例光子科技' AND round = 'D';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '662 days'  -- 原 2024-12-03
WHERE event_date = DATE '2024-12-03' AND company_name = '示例光子科技' AND round = 'C';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '325 days'  -- 原 2025-11-05
WHERE event_date = DATE '2025-11-05' AND company_name = '示例芯盟科技' AND round = 'STRATEGIC';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '557 days'  -- 原 2025-03-18
WHERE event_date = DATE '2025-03-18' AND company_name = '示例芯盟科技' AND round = 'B_PLUS';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '711 days'  -- 原 2024-10-15
WHERE event_date = DATE '2024-10-15' AND company_name = '示例芯盟科技' AND round = 'A';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '87 days'   -- 原 2026-07-01
WHERE event_date = DATE '2026-07-01' AND company_name = '示例康源生物' AND round = 'B';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '533 days'  -- 原 2025-04-11
WHERE event_date = DATE '2025-04-11' AND company_name = '示例康源生物' AND round = 'A';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '131 days'  -- 原 2026-05-18
WHERE event_date = DATE '2026-05-18' AND company_name = '示例瑞晶医药' AND round = 'C_PLUS';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '577 days'  -- 原 2025-02-26
WHERE event_date = DATE '2025-02-26' AND company_name = '示例瑞晶医药' AND round = 'B';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '673 days'  -- 原 2024-11-22
WHERE event_date = DATE '2024-11-22' AND company_name = '示例瑞晶医药' AND round = 'A';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '247 days'  -- 原 2026-01-22
WHERE event_date = DATE '2026-01-22' AND company_name = '示例精准医疗科技' AND round = 'D';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '696 days'  -- 原 2024-10-30
WHERE event_date = DATE '2024-10-30' AND company_name = '示例精准医疗科技' AND round = 'C';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '27 days'   -- 原 2026-08-30
WHERE event_date = DATE '2026-08-30' AND company_name = '示例佑宁制药' AND round = 'B_PLUS';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '403 days'  -- 原 2025-08-19
WHERE event_date = DATE '2025-08-19' AND company_name = '示例佑宁制药' AND round = 'B';

UPDATE industry_funding_event
SET event_date = CURRENT_DATE - INTERVAL '288 days'  -- 原 2025-12-12
WHERE event_date = DATE '2025-12-12' AND company_name = '示例安塞生物' AND round = 'A';
