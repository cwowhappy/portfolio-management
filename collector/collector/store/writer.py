import psycopg

from collector.executor.executor import StoreError

UPSERT_SQL = {
    "valuation_snapshot": """
        INSERT INTO valuation_snapshot (trading_day, pe_median, pb_median, net_breaker_count, net_breaker_ratio)
        VALUES (%s, %s, %s, %s, %s)
        ON CONFLICT (trading_day) DO UPDATE SET
          pe_median=EXCLUDED.pe_median, pb_median=EXCLUDED.pb_median,
          net_breaker_count=EXCLUDED.net_breaker_count, net_breaker_ratio=EXCLUDED.net_breaker_ratio
    """,
    "treasury_yield_curve": """
        INSERT INTO treasury_yield_curve (trading_day, term, yield)
        VALUES (%s, %s, %s)
        ON CONFLICT (trading_day, term) DO UPDATE SET yield=EXCLUDED.yield
    """,
    "index_valuation_history": """
        INSERT INTO index_valuation_history (trading_day, index_code, index_name, pe, pb, dividend_yield)
        VALUES (%s, %s, %s, %s, %s, %s)
        ON CONFLICT (trading_day, index_code) DO UPDATE SET
          pe=EXCLUDED.pe, pb=EXCLUDED.pb, dividend_yield=EXCLUDED.dividend_yield
    """,
    "industry_valuation": """
        INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (trading_day, industry_code) DO UPDATE SET
          pe=EXCLUDED.pe, pb=EXCLUDED.pb, roe=EXCLUDED.roe, dividend_yield=EXCLUDED.dividend_yield
    """,
    "shenwan_industry_mapping": """
        INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name)
        VALUES (%s, %s, %s, %s)
        ON CONFLICT (stock_code) DO UPDATE SET
          industry_code=EXCLUDED.industry_code, industry_name=EXCLUDED.industry_name
    """,
    "index_constituent": """
        INSERT INTO index_constituent (index_code, stock_code, stock_name, weight)
        VALUES (%s, %s, %s, %s)
        ON CONFLICT (index_code, stock_code) DO UPDATE SET
          stock_name=EXCLUDED.stock_name, weight=EXCLUDED.weight
    """,
    # 停牌语义（C1，2026-10-12 审查）：本表只写当日有交易的股票（daily_basic 缺席即停牌），
    # 无 suspended 标志列；下游消费方按「(trading_day, stock_code) 当日无行 = 停牌」解读，
    # 连续停牌期间不得把最近有行日的旧估值当作当日数据。源侧契约见 sources/stock.py docstring。
    "stock_valuation_daily": """
        INSERT INTO stock_valuation_daily (
            trading_day, stock_code, stock_name, pe_ttm, pb, dividend_yield, total_mv, circ_mv, turnover_rate, close
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (trading_day, stock_code) DO UPDATE SET
          stock_name=EXCLUDED.stock_name, pe_ttm=EXCLUDED.pe_ttm, pb=EXCLUDED.pb,
          dividend_yield=EXCLUDED.dividend_yield, total_mv=EXCLUDED.total_mv,
          circ_mv=EXCLUDED.circ_mv, turnover_rate=EXCLUDED.turnover_rate, close=EXCLUDED.close
    """,
    "stock_financial": """
        INSERT INTO stock_financial (
            report_date, stock_code, roe, roa, gross_margin, debt_to_assets, current_ratio,
            revenue_yoy, netprofit_yoy, revenue
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (report_date, stock_code) DO UPDATE SET
          roe=EXCLUDED.roe, roa=EXCLUDED.roa, gross_margin=EXCLUDED.gross_margin,
          debt_to_assets=EXCLUDED.debt_to_assets, current_ratio=EXCLUDED.current_ratio,
          revenue_yoy=EXCLUDED.revenue_yoy, netprofit_yoy=EXCLUDED.netprofit_yoy, revenue=EXCLUDED.revenue
    """,
    "index_close_history": """
        INSERT INTO index_close_history (trading_day, index_code, index_name, close)
        VALUES (%s, %s, %s, %s)
        ON CONFLICT (trading_day, index_code) DO UPDATE SET close=EXCLUDED.close
    """,
    # tracking_error_1y 由 etf_tracking_error 任务（Task 15）单独回写，此处不触碰；
    # updated_at 插入走 DEFAULT now()，更新侧显式刷新。
    "etf_basic": """
        INSERT INTO etf_basic
          (fund_code, fund_name, fee_rate, scale, tracking_index_code, tracking_index_name, category)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (fund_code) DO UPDATE SET
          fund_name=EXCLUDED.fund_name, fee_rate=EXCLUDED.fee_rate, scale=EXCLUDED.scale,
          tracking_index_code=EXCLUDED.tracking_index_code, tracking_index_name=EXCLUDED.tracking_index_name,
          category=EXCLUDED.category, updated_at=now()
    """,
    # MS-20 新闻双任务（news_fast/news_night）同表：冲突键 (source, external_id)。
    # published_at 保首见不更新（发布时刻是源站事实）；fetched_at 是采集观测时刻，刷新 now()。
    "intelligence_news_raw": """
        INSERT INTO intelligence_news_raw
          (source, external_id, title, summary, published_at, url, stock_tags)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (source, external_id) DO UPDATE SET
          title=EXCLUDED.title, summary=EXCLUDED.summary,
          url=EXCLUDED.url, stock_tags=EXCLUDED.stock_tags, fetched_at=now()
    """,
    # MS-21 公告双任务（announcement_night/announcement_morning）同表：冲突键
    # (source, external_id)。published_at 保首见不更新（披露时刻是源站事实）；
    # fetched_at 是采集观测时刻，刷新 now()；major 是栏目映射预判，主备源口径差异
    # （码链 vs 中文栏目关键词）导致的翻转随重跑刷新。
    "intelligence_announcement": """
        INSERT INTO intelligence_announcement
          (source, external_id, stock_code, stock_name, title, ann_type_source, major, published_at, pdf_url)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (source, external_id) DO UPDATE SET
          stock_name=EXCLUDED.stock_name, title=EXCLUDED.title,
          ann_type_source=EXCLUDED.ann_type_source, major=EXCLUDED.major,
          pdf_url=EXCLUDED.pdf_url, fetched_at=now()
    """,
    # MS-22 宏观五指标任务（macro_*）同表：冲突键 (indicator, period)。发布窗口多跑
    # 幂等——value/yoy/source_url/source_note 重跑刷新；period_type 随指标固定（键含
    # indicator 即隐含其月/日频），保首见不更新；V3 实况本表无 fetched_at 列（无观测
    # 时刻可刷）。
    "intelligence_macro_series": """
        INSERT INTO intelligence_macro_series
          (indicator, period, period_type, value, yoy, source_url, source_note)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (indicator, period) DO UPDATE SET
          value=EXCLUDED.value, yoy=EXCLUDED.yoy,
          source_url=EXCLUDED.source_url, source_note=EXCLUDED.source_note
    """,
    # MS-22 政策四任务（policy_*）同表：冲突键 (source, external_id)。published_at 保
    # 首见不更新（发布时刻是源站事实，非采集观测）；V3 实况本表无 fetched_at 列。
    "intelligence_policy_raw": """
        INSERT INTO intelligence_policy_raw
          (source, external_id, title, url, published_at, content_text)
        VALUES (%s, %s, %s, %s, %s, %s)
        ON CONFLICT (source, external_id) DO UPDATE SET
          title=EXCLUDED.title, url=EXCLUDED.url, content_text=EXCLUDED.content_text
    """,
}

TABLE_COLUMNS = {
    "valuation_snapshot": ["trading_day", "pe_median", "pb_median", "net_breaker_count", "net_breaker_ratio"],
    "treasury_yield_curve": ["trading_day", "term", "yield"],
    "index_valuation_history": ["trading_day", "index_code", "index_name", "pe", "pb", "dividend_yield"],
    "industry_valuation": ["trading_day", "industry_code", "industry_name", "pe", "pb", "roe", "dividend_yield"],
    "shenwan_industry_mapping": ["stock_code", "stock_name", "industry_code", "industry_name"],
    "index_constituent": ["index_code", "stock_code", "stock_name", "weight"],
    "stock_valuation_daily": [
        "trading_day",
        "stock_code",
        "stock_name",
        "pe_ttm",
        "pb",
        "dividend_yield",
        "total_mv",
        "circ_mv",
        "turnover_rate",
        "close",
    ],
    "stock_financial": [
        "report_date",
        "stock_code",
        "roe",
        "roa",
        "gross_margin",
        "debt_to_assets",
        "current_ratio",
        "revenue_yoy",
        "netprofit_yoy",
        "revenue",
    ],
    "index_close_history": ["trading_day", "index_code", "index_name", "close"],
    "etf_basic": [
        "fund_code",
        "fund_name",
        "fee_rate",
        "scale",
        "tracking_index_code",
        "tracking_index_name",
        "category",
    ],
    "intelligence_news_raw": [
        "source",
        "external_id",
        "title",
        "summary",
        "published_at",
        "url",
        "stock_tags",
    ],
    "intelligence_announcement": [
        "source",
        "external_id",
        "stock_code",
        "stock_name",
        "title",
        "ann_type_source",
        "major",
        "published_at",
        "pdf_url",
    ],
    "intelligence_macro_series": [
        "indicator",
        "period",
        "period_type",
        "value",
        "yoy",
        "source_url",
        "source_note",
    ],
    "intelligence_policy_raw": [
        "source",
        "external_id",
        "title",
        "url",
        "published_at",
        "content_text",
    ],
}


# 缺口径可空列白名单（C2，2026-10-12 审查）：这些列业务口径允许整条缺失（缺键写 NULL
# 是设计语义而非事故），不触发 fail-fast；白名单外的列缺失说明 converter/calc 输出契约
# 被破坏（如字段改名漏列），必须报错而非静默写 NULL。口径出处：
# - index_valuation_history.dividend_yield：P0-4 估算不可得回退 NULL（sources/index.py 注记）
# - stock_valuation_daily.dividend_yield：备源降级日全 NULL（akshare spot 无 dv_ttm，sources/stock.py 注记）
# - industry_valuation.roe/dividend_yield：左连接并入，上游无数据留 NULL（sources/industry.py 注记）
# - index_constituent.stock_name：converter 显式 default None（成分名可缺）
OPTIONAL_COLUMNS = {
    "index_valuation_history": {"dividend_yield"},
    "stock_valuation_daily": {"dividend_yield"},
    "industry_valuation": {"roe", "dividend_yield"},
    "index_constituent": {"stock_name"},
}


class Store:
    def upsert(self, conn, table: str, records: list[dict]) -> int:
        if not records:
            return 0
        try:
            cols = TABLE_COLUMNS[table]
            sql = UPSERT_SQL[table]
        except KeyError as e:
            raise StoreError(f"未知目标表: {table}") from e
        # C2 fail-fast：白名单外缺列按事故处理——converter/calc 改名漏列时 r.get() 会
        # 静默写全表 NULL，此处拒绝。校验全量键集并集（C6，2026-10-15 审查 P3-6）：不再
        # 假设 records 同质（只查首行是理论缝），任一行缺必需列即拒绝，O(n) 一遍。
        optional = OPTIONAL_COLUMNS.get(table, set())
        present = set().union(*(r.keys() for r in records))
        missing = [c for c in cols if c not in present and c not in optional]
        if missing:
            raise StoreError(f"目标表 {table} 缺列 {missing}：records 键集并集未携带，拒绝静默写 NULL")
        rows = [tuple(r.get(c) for c in cols) for r in records]
        try:
            with conn.cursor() as cur:
                if table == "index_constituent":
                    # 成分股是「每半年全量快照」语义：先删后插，被调出指数的成员不再残留。
                    # 只删本次涉及的 index_code，不动其它指数；删与插在同一事务，失败即回滚。
                    index_codes = sorted({r.get("index_code") for r in records if r.get("index_code") is not None})
                    if index_codes:
                        cur.execute("DELETE FROM index_constituent WHERE index_code = ANY(%s)", (index_codes,))
                cur.executemany(sql, rows)
            conn.commit()
        except psycopg.Error as e:
            # 不回滚的话连接停留在 aborted transaction 状态，
            # 后续 failed run 落库与整任务重试都会失败。
            conn.rollback()
            raise StoreError(f"DB 写入失败: {e}") from e
        return len(rows)
