-- 开发/联调样例数据：手工执行，非 Flyway 迁移。
-- 用法：psql "$DATABASE_URL" -f backend/src/main/resources/db/seed/valuation-dev-seed.sql

INSERT INTO valuation_snapshot (trading_day, pe_median, pb_median, net_breaker_count, net_breaker_ratio) VALUES
  ('2026-08-25', 18.52, 1.63, 245, 0.0456),
  ('2026-08-26', 18.90, 1.66, 231, 0.0431),
  ('2026-08-27', 19.14, 1.68, 220, 0.0410);

INSERT INTO treasury_yield_curve (trading_day, term, yield) VALUES
  ('2026-08-25', '10Y', 2.1800),
  ('2026-08-26', '10Y', 2.1900),
  ('2026-08-27', '10Y', 2.2100);

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

-- 个股基本面样例：供 /screener、/industry 联调与 e2e。
INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name, pe_ttm, pb, dividend_yield, total_mv, circ_mv, turnover_rate) VALUES
  ('2026-08-27', '600519', '贵州茅台', 22.50, 7.80, 2.10, 2100000000000, 2100000000000, 0.35),
  ('2026-08-27', '000858', '五粮液', 18.20, 4.50, 2.80, 580000000000, 580000000000, 0.62),
  ('2026-08-27', '601398', '工商银行', 5.60, 0.62, 5.40, 2200000000000, 2100000000000, 0.18),
  ('2026-08-27', '600036', '招商银行', 9.80, 1.05, 3.90, 980000000000, 980000000000, 0.44),
  ('2026-08-27', '300750', '宁德时代', 28.40, 4.20, 0.60, 1100000000000, 900000000000, 1.25);

INSERT INTO stock_financial (report_date, stock_code, roe, roa, gross_margin, debt_to_assets, current_ratio, revenue_yoy, netprofit_yoy) VALUES
  ('2026-06-30', '600519', 24.50, 18.20, 91.20, 21.30, 3.80, 16.80, 15.20),
  ('2026-06-30', '000858', 20.10, 15.40, 75.60, 30.10, 2.60, 12.50, 11.30),
  ('2026-06-30', '601398', 11.80, 0.95, 0.00, 91.80, 0.90, 2.10, 1.80),
  ('2026-06-30', '600036', 16.40, 1.35, 0.00, 90.20, 1.10, 4.60, 5.20),
  ('2026-06-30', '300750', 19.20, 6.80, 25.30, 58.40, 1.40, 32.60, 28.90);
