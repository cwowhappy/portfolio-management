-- 开发/联调样例数据：手工执行，非 Flyway 迁移。
-- 用法：psql "$DATABASE_URL" -f backend/src/main/resources/db/seed/valuation-dev-seed.sql

INSERT INTO valuation_snapshot (trading_day, pe_median, pb_median, net_breaker_count, net_breaker_ratio) VALUES
  ('2026-08-25', 18.52, 1.63, 245, 0.0456),
  ('2026-08-26', 18.90, 1.66, 231, 0.0431),
  ('2026-08-27', 19.14, 1.68, 220, 0.0410);

INSERT INTO treasury_yield (trading_day, yield_10y) VALUES
  ('2026-08-25', 2.1800),
  ('2026-08-26', 2.1900),
  ('2026-08-27', 2.2100);

INSERT INTO index_valuation_history (trading_day, index_code, index_name, pe, pb, dividend_yield) VALUES
  ('2026-08-27', '000300', '沪深300', 12.80, 1.42, 2.35),
  ('2026-08-27', '000905', '中证500', 22.10, 1.80, 1.40),
  ('2026-08-27', '000016', '上证50', 10.50, 1.20, 3.10),
  ('2026-08-27', '399006', '创业板指', 30.40, 3.90, 0.90),
  ('2026-08-27', '000688', '科创50', 45.20, 4.10, 0.60);

INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield) VALUES
  ('2026-08-27', '801010', '农林牧渔', 25.10, 2.80, 8.5, 1.20),
  ('2026-08-27', '801080', '电子', 35.60, 3.40, 12.3, 0.80),
  ('2026-08-27', '801780', '银行', 5.90, 0.65, 11.0, 5.10);

INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name) VALUES
  ('600519', '贵州茅台', '801120', '食品饮料'),
  ('000858', '五粮液', '801120', '食品饮料'),
  ('601398', '工商银行', '801780', '银行');
